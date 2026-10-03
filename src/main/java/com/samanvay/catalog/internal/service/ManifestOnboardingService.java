package com.samanvay.catalog.internal.service;

import com.samanvay.catalog.api.CatalogDiscovery;
import com.samanvay.catalog.api.CatalogOnboarding;
import com.samanvay.catalog.api.ConnectorDraft;
import com.samanvay.catalog.api.DataSourceDraft;
import com.samanvay.catalog.api.DepartmentDraft;
import com.samanvay.catalog.api.DepartmentIdentity;
import com.samanvay.catalog.api.DepartmentManifest;
import com.samanvay.catalog.api.DiscoveredManifest;
import com.samanvay.catalog.api.FieldMapping;
import com.samanvay.catalog.api.JourneyWrite;
import com.samanvay.catalog.api.ManifestOnboarding;
import com.samanvay.catalog.api.MappingDraft;
import com.samanvay.catalog.api.MappingSuggestion;
import com.samanvay.catalog.api.OnboardRequest;
import com.samanvay.catalog.api.OnboardingPlan;
import com.samanvay.catalog.api.OnboardingResult;
import com.samanvay.catalog.api.SchemaCatalog;
import com.samanvay.catalog.internal.domain.ConnectorEntity;
import com.samanvay.catalog.internal.domain.DataSourceEntity;
import com.samanvay.catalog.internal.domain.DepartmentEntity;
import com.samanvay.catalog.internal.repository.ConnectorRepository;
import com.samanvay.catalog.internal.repository.DataSourceRepository;
import com.samanvay.catalog.internal.repository.DepartmentRepository;
import com.samanvay.catalog.internal.repository.JourneyRepository;
import com.samanvay.catalog.internal.service.ManifestOnboardingPlanner.ConnectorSpec;
import com.samanvay.catalog.internal.service.ManifestOnboardingPlanner.Env;
import com.samanvay.catalog.internal.service.ManifestOnboardingPlanner.Planned;
import com.samanvay.catalog.internal.service.ManifestOnboardingPlanner.SchemaInfo;
import com.samanvay.catalog.internal.service.ManifestOnboardingPlanner.SourceSpec;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.InvalidRequestException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Onboards a department in one go from its published manifest (docs/FINAL-CHANGES.md section 10). {@link #plan} fetches the
 * manifest and says what would happen; {@link #onboard} creates exactly what an admin reviewed and ticked, all as DRAFTs,
 * in ONE transaction, after validating everything up front, so a refusal leaves nothing behind.
 *
 * <p>The admin reviews a plan and onboarding refuses if the manifest has changed since (digest). Mappings are propose-only:
 * an admin accepts the suggestions or supplies their own. An existing connector for the same department and category gets
 * a NEW VERSION (resolution picks the highest published version, so the live one keeps serving until the new one is published).
 */
@Service
class ManifestOnboardingService implements ManifestOnboarding {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int DEFAULT_SLA_MS = 3000;

    private final CatalogDiscovery discovery;
    private final CatalogOnboarding wizard;
    private final JourneyWrite journeyWrite;
    private final SchemaCatalog schemas;
    private final DepartmentRepository departments;
    private final DataSourceRepository dataSources;
    private final ConnectorRepository connectors;
    private final JourneyRepository journeys;
    private final ManifestOnboardingPlanner planner = new ManifestOnboardingPlanner();
    private final boolean allowUnsigned;

    ManifestOnboardingService(
            CatalogDiscovery discovery,
            CatalogOnboarding wizard,
            JourneyWrite journeyWrite,
            SchemaCatalog schemas,
            DepartmentRepository departments,
            DataSourceRepository dataSources,
            ConnectorRepository connectors,
            JourneyRepository journeys,
            @org.springframework.beans.factory.annotation.Value("${samanvay.catalog.allow-unsigned-manifests:false}") boolean allowUnsigned) {
        this.allowUnsigned = allowUnsigned;
        this.discovery = discovery;
        this.wizard = wizard;
        this.journeyWrite = journeyWrite;
        this.schemas = schemas;
        this.departments = departments;
        this.dataSources = dataSources;
        this.connectors = connectors;
        this.journeys = journeys;
    }

    @Override
    @Transactional(readOnly = true)
    public OnboardingPlan plan(String baseUrl) {
        DiscoveredManifest found = discovery.fetch(baseUrl);
        DepartmentManifest m = found.manifest();
        ManifestOnboardingPlanner.identityHostProblem(m, baseUrl).ifPresent(p -> {
            throw new InvalidRequestException(p);
        });
        String pinned = pinnedKey(m);
        ManifestTrust.checkPlan(Optional.ofNullable(found.keyThumbprint()), pinned, allowUnsigned);
        return planner.plan(m, baseUrl, null, env(m)).plan().withManifestKey(found.keyThumbprint(), pinned);
    }

    private String pinnedKey(DepartmentManifest m) {
        return departments.findById(m.department().code()).map(DepartmentEntity::getManifestKeyThumbprint).orElse(null);
    }

    @Override
    @Transactional
    public OnboardingResult onboard(OnboardRequest req) {
        if (req == null || req.baseUrl() == null || req.baseUrl().isBlank()) {
            throw new InvalidRequestException("baseUrl is required");
        }
        DiscoveredManifest found = discovery.fetch(req.baseUrl());
        DepartmentManifest m = found.manifest();
        ManifestOnboardingPlanner.identityHostProblem(m, req.baseUrl()).ifPresent(p -> {
            throw new InvalidRequestException(p);
        });
        Optional<String> signedBy = Optional.ofNullable(found.keyThumbprint());
        ManifestTrust.checkPlan(signedBy, pinnedKey(m), allowUnsigned);
        ManifestTrust.checkOnboard(signedBy, pinnedKey(m), req.approvedManifestKey());
        String digest = ManifestOnboardingPlanner.digest(m);
        if (!digest.equals(req.manifestDigest())) {
            throw new InvalidRequestException("The department's manifest has changed since you reviewed it. Review the new plan and try again.");
        }
        Set<String> selected = new LinkedHashSet<>(req.categories() == null ? List.of() : req.categories());
        if (selected.isEmpty()) {
            throw new InvalidRequestException("Tick at least one document to onboard");
        }
        Set<String> known = m.documents().stream().map(DepartmentManifest.Document::category).collect(Collectors.toSet());
        for (String c : selected) {
            if (!known.contains(c)) {
                throw new InvalidRequestException("The department does not publish a document " + c);
            }
        }

        Planned planned = planner.plan(m, req.baseUrl(), selected, env(m));
        // Validate everything before the first write.
        for (var d : planned.plan().documents()) {
            if (selected.contains(d.category()) && !d.ready()) {
                throw new InvalidRequestException("Document " + d.category() + " is not ready to onboard: "
                        + String.join(" ", d.problems()) + (d.unmappedRequired().isEmpty() ? "" : " No mapping covers required " + d.unmappedRequired() + "."));
            }
        }
        Map<String, List<FieldMapping>> rulesByCategory = new HashMap<>();
        for (ConnectorSpec c : planned.connectors()) {
            rulesByCategory.put(c.category(), rulesFor(c, req));
        }

        String dept = m.department().code();
        List<String> skipped = new ArrayList<>();
        saveDepartment(m, digest, found.keyThumbprint());

        List<String> createdSources = new ArrayList<>();
        for (SourceSpec s : planned.sources()) {
            if (dataSources.existsById(s.code())) {
                skipped.add("data source " + s.code() + " already exists");
            } else {
                wizard.registerDataSource(new DataSourceDraft(s.code(), dept, s.protocol(), s.baseHost(), s.authType(), s.authConfigRef(), s.authSpecJson()));
                createdSources.add(s.code());
            }
        }

        List<String> connectorRefs = new ArrayList<>();
        List<String> mappingRefs = new ArrayList<>();
        for (ConnectorSpec c : planned.connectors()) {
            int version = connectors.findByConnectorId(c.connectorId()).stream().mapToInt(ConnectorEntity::getVersion).max().orElse(0) + 1;
            String mappingRef = "map-" + c.connectorId() + "@" + version;
            ObjectNode caps = c.capabilities().deepCopy();
            ((ObjectNode) caps.get("FETCH")).put("mapping_ref", mappingRef);
            var draft = wizard.createDraft(new ConnectorDraft(c.connectorId(), c.sourceCode(), DataCategory.of(c.category()), caps.toString(), c.inputsJson(), c.slaMs()));
            wizard.saveMapping(new MappingDraft(mappingRef, draft.ref(), rulesByCategory.get(c.category())));
            connectorRefs.add(draft.ref());
            mappingRefs.add(mappingRef);
        }

        List<String> createdJourneys = new ArrayList<>();
        for (var j : planned.journeys()) {
            if (j.exists()) {
                skipped.add("journey " + j.draft().code() + " already exists");
            } else {
                journeyWrite.createJourney(j.draft());
                createdJourneys.add(j.draft().code());
            }
        }
        return new OnboardingResult(dept, List.copyOf(createdSources), List.copyOf(connectorRefs), List.copyOf(mappingRefs),
                List.copyOf(createdJourneys), List.copyOf(skipped), planned.plan().pendingSteps());
    }

    /** The admin's own mapping for the category, or the suggestions if they accepted them; otherwise refused. */
    private static List<FieldMapping> rulesFor(ConnectorSpec c, OnboardRequest req) {
        List<FieldMapping> own = req.mappings() == null ? null : req.mappings().get(c.category());
        if (own != null && !own.isEmpty()) {
            return own;
        }
        if (req.acceptSuggestedMappings() && !c.suggestions().isEmpty()) {
            return c.suggestions().stream().map(ManifestOnboardingService::asRule).toList();
        }
        throw new InvalidRequestException("No mapping was approved for " + c.category() + ": accept the suggested mapping or supply your own.");
    }

    private static FieldMapping asRule(MappingSuggestion s) {
        return new FieldMapping(s.source(), s.target(), List.of());
    }

    private void saveDepartment(DepartmentManifest m, String digest, String keyThumbprint) {
        String code = m.department().code();
        DepartmentIdentity identity = m.identity() == null || m.identity().personIdType() == null ? null
                : new DepartmentIdentity(m.identity().personIdType(), m.identity().loginUrl(), m.identity().jwksUrl(), m.identity().assertionIssuer());
        if (departments.findById(code).isEmpty()) {
            wizard.registerDepartment(new DepartmentDraft(code, m.department().name(), null, null, DEFAULT_SLA_MS, identity));
        }
        DepartmentEntity e = departments.findById(code).orElseThrow();
        if (identity != null) {
            e.setIdentitySpec(JSON.writeValueAsString(identity));
        }
        e.setManifestDigest(digest);
        if (keyThumbprint != null) {
            e.setManifestKeyThumbprint(keyThumbprint); // pinned: later manifests must be signed by this key
        }
        departments.save(e);
    }

    // --- current catalog state the plan depends on --------------------------------------------------------------

    private Env env(DepartmentManifest m) {
        String code = m.department().code();
        Optional<DepartmentEntity> dept = departments.findById(code);
        Map<String, SchemaInfo> byCategory = centralSchemasByCategory();
        Set<String> deptSources = dataSources.findAll().stream().filter(d -> code.equals(d.getDepartmentCode())).map(DataSourceEntity::getCode).collect(Collectors.toSet());
        Map<String, String> connectorByCategory = new HashMap<>();
        Map<String, Integer> bestVersion = new HashMap<>();
        for (ConnectorEntity c : connectors.findAll()) {
            if (deptSources.contains(c.getDataSourceCode()) && c.getVersion() >= bestVersion.getOrDefault(c.getDataCategory(), 0)) {
                bestVersion.put(c.getDataCategory(), c.getVersion());
                connectorByCategory.put(c.getDataCategory(), c.getConnectorId());
            }
        }
        Set<String> existingJourneys = journeys.findAll().stream().map(j -> j.getCode()).collect(Collectors.toCollection(HashSet::new));
        return new Env(dept.isPresent(), dept.map(DepartmentEntity::getManifestDigest).orElse(null),
                c -> Optional.ofNullable(byCategory.get(c)), c -> Optional.ofNullable(connectorByCategory.get(c)), existingJourneys);
    }

    /** category -> its central schema (the highest version when several), found by the schema's own "x-category". */
    private Map<String, SchemaInfo> centralSchemasByCategory() {
        Map<String, SchemaInfo> out = new HashMap<>();
        Map<String, Integer> versions = new HashMap<>();
        for (String ref : schemas.refs()) {
            JsonNode def = JSON.readTree(schemas.definition(ref));
            JsonNode cat = def.get("x-category");
            if (cat == null || def.get("properties") == null) {
                continue;
            }
            int version = versionOf(ref);
            if (version >= versions.getOrDefault(cat.asString(), 0)) {
                versions.put(cat.asString(), version);
                List<String> props = new ArrayList<>();
                def.get("properties").properties().forEach(e -> props.add(e.getKey()));
                List<String> required = new ArrayList<>();
                if (def.get("required") != null) {
                    def.get("required").forEach(r -> required.add(r.asString()));
                }
                out.put(cat.asString(), new SchemaInfo(ref, props, required));
            }
        }
        return out;
    }

    private static int versionOf(String ref) {
        int at = ref.lastIndexOf('@');
        try {
            return at < 0 ? 0 : Integer.parseInt(ref.substring(at + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
