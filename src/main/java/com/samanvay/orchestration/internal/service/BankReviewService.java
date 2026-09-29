package com.samanvay.orchestration.internal.service;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.connector.api.BankCheckReview;
import com.samanvay.orchestration.internal.domain.BankReviewEntity;
import com.samanvay.orchestration.internal.repository.BankReviewRepository;
import com.samanvay.shared.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The officer side of a bank check that did not auto-accept. A future journey step
 * calls {@link #open} when {@link BankCheckReview.Decision#OFFICER_REVIEW} is
 * returned; an officer then lists open reviews, may ask the citizen for a passbook,
 * and approves or rejects with a reason. The bank holder's name is never stored or
 * shown; the review carries only the masked account, the machine reason and the
 * matcher version, plus a hash of the uploaded file. The file is deleted on
 * decision (and by the retention job); the hash and the decision are kept.
 */
@Service
public class BankReviewService {

    private static final String PENDING = "PENDING_OFFICER";
    private static final String DOCUMENT_REQUESTED = "DOCUMENT_REQUESTED";
    private static final String APPROVED = "APPROVED";
    private static final String REJECTED = "REJECTED";
    private static final List<String> OPEN = List.of(PENDING, DOCUMENT_REQUESTED);

    private final BankReviewRepository reviews;
    private final PassbookStore store;
    private final BankReviewProperties properties;
    private final AuditService audit;
    private final Clock clock;

    BankReviewService(BankReviewRepository reviews, PassbookStore store, BankReviewProperties properties,
            AuditService audit, Clock clock) {
        this.reviews = reviews;
        this.store = store;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    /** Called by the journey when a bank check needs an officer. Returns the review id. */
    @Transactional
    public UUID open(String applicationId, UUID citizenId, BankCheckReview review, String accountMasked,
            String matcherVersion) {
        UUID id = UUID.randomUUID();
        reviews.save(new BankReviewEntity(id, applicationId, citizenId, accountMasked,
                review.reason().name(), matcherVersion, PENDING, clock.instant()));
        // Opening is journey-triggered, not an officer action.
        recordSystem("BANK_REVIEW_OPENED", id, citizenId, applicationId, Map.of("reason", review.reason().name()));
        return id;
    }

    @Transactional(readOnly = true)
    public List<BankReviewView> openReviews() {
        return reviews.findByStatusInOrderByCreatedAtAsc(OPEN).stream().map(BankReviewView::of).toList();
    }

    @Transactional
    public void requestDocument(UUID id, String officer) {
        BankReviewEntity r = openOrThrow(id);
        r.setStatus(DOCUMENT_REQUESTED);
        record("BANK_REVIEW_DOCUMENT_REQUESTED", officer, id, r.getCitizenId(), r.getApplicationId(), Map.of());
    }

    @Transactional
    public void attachPassbook(UUID id, byte[] content, String officer) {
        BankReviewEntity r = openOrThrow(id);
        PassbookUpload.Kind kind = PassbookUpload.validate(content); // 400 on bad type/size, no content echoed
        store.deleteQuietly(r.getDocumentPath());                    // replace any earlier upload
        PassbookStore.Stored stored = store.write(content, kind);
        r.setDocumentPath(stored.path());
        r.setDocumentHash(stored.sha256());
        r.setDocumentUploadedAt(clock.instant());
        record("BANK_REVIEW_PASSBOOK_UPLOADED", officer, id, r.getCitizenId(), r.getApplicationId(),
                Map.of("documentHash", stored.sha256()));
    }

    @Transactional
    public void approve(UUID id, String reason, String officer) {
        decide(id, APPROVED, reason, "BANK_REVIEW_APPROVED", officer);
    }

    @Transactional
    public void reject(UUID id, String reason, String officer) {
        decide(id, REJECTED, reason, "BANK_REVIEW_REJECTED", officer);
    }

    private void decide(UUID id, String status, String reason, String action, String officer) {
        BankReviewEntity r = openOrThrow(id);
        store.deleteQuietly(r.getDocumentPath()); // keep only the decision and the hash
        r.setDocumentPath(null);
        r.setStatus(status);
        r.setDecisionReason(reason);
        r.setDecidedBy(officer);
        r.setDecidedAt(clock.instant());
        record(action, officer, id, r.getCitizenId(), r.getApplicationId(),
                reason == null ? Map.of() : Map.of("decisionReason", reason));
    }

    /** Retention: delete the file of any review whose upload is older than the window; keep the hash. */
    @Transactional
    public int purgeExpiredDocuments() {
        Instant cutoff = clock.instant().minus(properties.retention());
        List<BankReviewEntity> stale = reviews.withFileUploadedBefore(cutoff);
        for (BankReviewEntity r : stale) {
            store.deleteQuietly(r.getDocumentPath());
            r.setDocumentPath(null);
            recordSystem("BANK_REVIEW_DOCUMENT_PURGED", r.getId(), r.getCitizenId(), r.getApplicationId(),
                    Map.of("documentHash", String.valueOf(r.getDocumentHash())));
        }
        return stale.size();
    }

    private BankReviewEntity openOrThrow(UUID id) {
        BankReviewEntity r = reviews.findById(id).orElseThrow(() -> new NotFoundException("bank review not found"));
        if (!OPEN.contains(r.getStatus())) {
            throw new NotFoundException("bank review is already decided");
        }
        return r;
    }

    private void record(String action, String officer, UUID reviewId, UUID citizenId, String applicationId,
            Map<String, Object> meta) {
        Map<String, Object> m = new java.util.HashMap<>(meta);
        m.put("reviewId", reviewId.toString());
        m.put("applicationId", applicationId);
        audit.record(new AuditEntry(ActorType.OFFICER, officer, action,
                citizenId.toString(), "bank-review", null, null, null, Outcome.ALLOWED, null, m));
    }

    private void recordSystem(String action, UUID reviewId, UUID citizenId, String applicationId,
            Map<String, Object> meta) {
        Map<String, Object> m = new java.util.HashMap<>(meta);
        m.put("reviewId", reviewId.toString());
        m.put("applicationId", applicationId);
        audit.record(new AuditEntry(ActorType.SYSTEM, "retention-job", action,
                citizenId.toString(), "bank-review", null, null, null, Outcome.ALLOWED, null, m));
    }
}
