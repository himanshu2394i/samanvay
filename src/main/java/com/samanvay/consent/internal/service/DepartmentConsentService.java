package com.samanvay.consent.internal.service;

import com.samanvay.catalog.api.DepartmentCatalog;
import com.samanvay.catalog.api.JourneyCatalog;
import com.samanvay.catalog.api.JourneyDefinition;
import com.samanvay.catalog.api.Purpose;
import com.samanvay.catalog.api.PurposeCatalog;
import com.samanvay.consent.api.AuthProof;
import com.samanvay.consent.api.ConsentArtifact;
import com.samanvay.consent.api.ConsentRequest;
import com.samanvay.consent.api.ConsentRequestDraft;
import com.samanvay.consent.api.ConsentService;
import com.samanvay.consent.api.ConsentStatementInvalidException;
import com.samanvay.consent.api.ConsentWording;
import com.samanvay.consent.api.UnknownPurposeException;
import com.samanvay.consent.internal.domain.ConsentRequestEntity;
import com.samanvay.consent.internal.repository.ConsentRequestRepository;
import com.samanvay.consent.internal.service.ConsentStatementVerifier.VerifiedStatement;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.shared.NotFoundException;
import com.samanvay.shared.PrincipalRef;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consent collected on a department's own portal. Step one gives the department the exact wording and a one-time nonce; step two takes
 * the statement the department signed after the citizen confirmed, checks it against the open request, and only then grants. Anything
 * that does not match is one {@link ConsentStatementInvalidException} and nothing is written (the whole call rolls back).
 */
@Service
public class DepartmentConsentService {

    static final Duration NONCE_TTL = Duration.ofMinutes(10);
    private static final Duration DEFAULT_VALIDITY = Duration.ofDays(365);

    private final ConsentService consents;
    private final PurposeCatalog purposes;
    private final JourneyCatalog journeys;
    private final DepartmentCatalog departments;
    private final IdentityLinking linking;
    private final ConsentRequestRepository requests;
    private final ConsentStatementVerifier verifier;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    DepartmentConsentService(
            ConsentService consents,
            PurposeCatalog purposes,
            JourneyCatalog journeys,
            DepartmentCatalog departments,
            IdentityLinking linking,
            ConsentRequestRepository requests,
            ConsentStatementVerifier verifier,
            JdbcClient jdbc,
            Clock clock) {
        this.consents = consents;
        this.purposes = purposes;
        this.journeys = journeys;
        this.departments = departments;
        this.linking = linking;
        this.requests = requests;
        this.verifier = verifier;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public ConsentWording request(String department, UUID citizenId, String journeyCode, PrincipalRef by) {
        JourneyDefinition journey = journeys.byCode(journeyCode);
        if (!department.equals(journey.policy().requester())) {
            throw new AccessDeniedException(department + " does not run " + journeyCode);
        }
        // 404, not 403: 403 is for a role that may not call; here the citizen is simply not one of this department's.
        if (citizenId == null || linking.activeLink(citizenId, department).isEmpty()) {
            throw new NotFoundException("citizen");
        }
        Purpose purpose = purposes.byCode(journey.policy().purpose()).filter(Purpose::active)
                .orElseThrow(() -> new UnknownPurposeException(journey.policy().purpose()));
        ConsentRequest request = consents.request(new ConsentRequestDraft(citizenId, department, purpose.code(), by));
        requests.flush(); // the request was saved through JPA; the nonce row below references it by JDBC
        String nonce = nonce();
        Instant expires = clock.instant().plus(NONCE_TTL);
        jdbc.sql("INSERT INTO consent_statement_nonce (request_id, nonce, expires_at) VALUES (:id, :nonce, :expires)")
                .param("id", request.id()).param("nonce", nonce).param("expires", Timestamp.from(expires)).update();
        Set<String> providerCodes = new LinkedHashSet<>();
        journey.requiredCategories().forEach(c -> providerCodes.add(journey.policy().sourceDepartment(c)));
        List<ConsentWording.Provider> providers = providerCodes.stream()
                .map(code -> new ConsentWording.Provider(code, departments.byCode(code).map(d -> d.name()).orElse(code))).toList();
        Duration validity = purpose.maxDurationDays() == null ? DEFAULT_VALIDITY
                : DEFAULT_VALIDITY.compareTo(Duration.ofDays(purpose.maxDurationDays())) < 0 ? DEFAULT_VALIDITY : Duration.ofDays(purpose.maxDurationDays());
        return new ConsentWording(request.id(), purpose.code(), request.purposeText(), request.categories(), providers, (int) validity.toDays(), nonce, expires);
    }

    @Transactional
    public ConsentArtifact grant(String department, String statement, PrincipalRef by) {
        VerifiedStatement v = verifier.verify(statement, department);
        ConsentRequestEntity req = requests.findById(v.requestId()).orElseThrow(() -> ConsentStatementVerifier.refuse("no such request"));
        Set<String> asked = new HashSet<>(Arrays.asList(req.getDataCategories()));
        if (!department.equals(req.getRequesterId()) || !req.getSubjectCitizenId().equals(v.citizenId()) || !"PENDING".equals(req.getStatus())
                || !req.getPurposeCode().equals(v.purpose()) || !asked.equals(v.categories())) {
            throw ConsentStatementVerifier.refuse("statement does not match the open request");
        }
        Instant now = clock.instant();
        int used = jdbc.sql("UPDATE consent_statement_nonce SET used_at = :now WHERE request_id = :id AND nonce = :nonce"
                        + " AND used_at IS NULL AND expires_at > :now")
                .param("now", Timestamp.from(now)).param("id", req.getId()).param("nonce", v.nonce()).update();
        if (used != 1) {
            throw ConsentStatementVerifier.refuse("nonce is wrong, used or expired");
        }
        ConsentArtifact artifact = consents.grant(req.getId(), v.citizenId(), new AuthProof("dept-signed:" + v.jti()), by);
        requests.flush();
        try {
            jdbc.sql("INSERT INTO consent_evidence (consent_id, statement, jti, department_code, key_thumbprint, created_at)"
                            + " VALUES (:consent, :statement, :jti, :dept, :thumb, :now)")
                    .param("consent", artifact.id()).param("statement", statement.trim()).param("jti", v.jti())
                    .param("dept", department).param("thumb", v.thumbprint()).param("now", Timestamp.from(now)).update();
        } catch (DataIntegrityViolationException e) {
            throw ConsentStatementVerifier.refuse("statement already used");
        }
        return artifact;
    }

    private String nonce() {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
