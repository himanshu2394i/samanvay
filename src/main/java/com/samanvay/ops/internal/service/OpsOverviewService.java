package com.samanvay.ops.internal.service;

import com.samanvay.catalog.api.CatalogDiscovery;
import com.samanvay.catalog.api.ConnectorDefinition;
import com.samanvay.catalog.api.DataSourceHealth;
import com.samanvay.catalog.api.FieldMapping;
import com.samanvay.catalog.api.JourneyDefinition;
import com.samanvay.catalog.api.OnboardedCatalog;
import com.samanvay.catalog.api.OnboardedCatalog.OnboardedDepartment;
import com.samanvay.catalog.api.OnboardedCatalog.OnboardedDocument;
import com.samanvay.connector.api.TrialHistory;
import com.samanvay.ops.internal.service.OpsOverviewView.Counts;
import com.samanvay.ops.internal.service.OpsOverviewView.DataSource;
import com.samanvay.ops.internal.service.OpsOverviewView.Department;
import com.samanvay.ops.internal.service.OpsOverviewView.Document;
import com.samanvay.ops.internal.service.OpsOverviewView.Journey;
import com.samanvay.ops.internal.service.OpsOverviewView.Mapping;
import com.samanvay.ops.internal.service.OpsOverviewView.Need;
import com.samanvay.ops.internal.service.OpsOverviewView.Trial;
import com.samanvay.orchestration.api.JourneyActivity;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The staff overview: onboarded departments with their data sources, documents (mapped onto the central schema) and
 * journeys. Read-only, built from the catalog, connector and orchestration APIs; the same "working" rule as the journey page.
 */
@Service
public class OpsOverviewService {

    private final OnboardedCatalog onboarded;
    private final CatalogDiscovery discovery;
    private final TrialHistory trials;
    private final JourneyActivity activity;
    private final Clock clock;

    OpsOverviewService(
            OnboardedCatalog onboarded,
            CatalogDiscovery discovery,
            TrialHistory trials,
            JourneyActivity activity,
            Clock clock) {
        this.onboarded = onboarded;
        this.discovery = discovery;
        this.trials = trials;
        this.activity = activity;
        this.clock = clock;
    }

    public OpsOverviewView overview() {
        Map<String, String> health = new HashMap<>();
        Map<String, String> protocols = new HashMap<>();
        for (DataSourceHealth h : discovery.listDataSources()) {
            health.put(h.code(), h.healthStatus());
            protocols.put(h.code(), h.protocol());
        }
        List<OnboardedDepartment> departments = onboarded.departments();
        // what each onboarded department serves, by category: the only connectors that may make an onboarded journey ready
        Map<String, Map<String, OnboardedDocument>> served = new HashMap<>();
        for (OnboardedDepartment d : departments) {
            Map<String, OnboardedDocument> byCategory = new HashMap<>();
            d.documents().forEach(doc -> byCategory.put(doc.connector().category().code(), doc));
            served.put(d.code(), byCategory);
        }
        List<Department> out = new ArrayList<>();
        for (OnboardedDepartment d : departments) {
            out.add(new Department(
                    d.code(),
                    d.name(),
                    d.pinnedKeyThumbprint(),
                    d.loginUrl(),
                    d.dataSources().stream().map(s -> new DataSource(s.code(), s.protocol(), s.baseHost(), s.healthStatus(), s.detail())).toList(),
                    d.documents().stream().map(doc -> document(doc, health, protocols)).toList(),
                    d.journeys().stream().map(j -> journey(j, health, protocols, served)).toList()));
        }
        return new OpsOverviewView(clock.instant(), out);
    }

    private Document document(OnboardedDocument doc, Map<String, String> health, Map<String, String> protocols) {
        ConnectorDefinition c = doc.connector();
        String sourceHealth = health.getOrDefault(c.dataSourceCode(), "UNKNOWN");
        boolean published = c.status() == com.samanvay.catalog.api.ConnectorStatus.PUBLISHED;
        Trial trial = trials.last(c.ref()).map(t -> new Trial(t.at(), t.outcome())).orElse(null);
        Set<String> required = Set.copyOf(doc.requiredFields());
        List<Mapping> mappings = doc.rules().stream().map(r -> new Mapping(r.source(), r.target(), required.contains(r.target()))).toList();
        Set<String> mapped = doc.rules().stream().map(FieldMapping::target).collect(Collectors.toSet());
        return new Document(
                c.category().code(),
                title(c.category().code()),
                c.ref(),
                c.status().name(),
                c.dataSourceCode(),
                sourceHealth,
                trial,
                SourceWorking.working(published, sourceHealth, protocols.get(c.dataSourceCode()), trial == null ? null : trial.outcome()),
                doc.centralSchemaRef(),
                mappings,
                doc.requiredFields().stream().filter(r -> !mapped.contains(r)).toList(),
                doc.pendingUpdate() == null ? null : doc.pendingUpdate().ref());
    }

    /**
     * An onboarded journey is ready and working only through what manifest onboarding created for the provider department: its
     * onboarded document for the category, PUBLISHED. A seeded or hand-made connector of the same department never counts.
     */
    private Journey journey(JourneyDefinition j, Map<String, String> health, Map<String, String> protocols,
            Map<String, Map<String, OnboardedDocument>> served) {
        List<Need> needs = new ArrayList<>();
        boolean ready = true;
        for (String category : j.requiredCategories()) {
            String department = department(j, category);
            OnboardedDocument doc = department == null ? null : served.getOrDefault(department, Map.of()).get(category);
            boolean published = doc != null && doc.connector().status() == com.samanvay.catalog.api.ConnectorStatus.PUBLISHED;
            ready &= published;
            boolean working = published && document(doc, health, protocols).working();
            needs.add(new Need(category, department, working));
        }
        JourneyActivity.Activity a = activity.activity(j.code(), clock.instant().minus(JourneyStatusService.WINDOW), 0);
        return new Journey(j.code(), j.name(), j.status(), ready, needs, new Counts(a.running(), a.completed(), a.failed(), a.startedSince()));
    }

    private static String department(JourneyDefinition journey, String category) {
        try {
            return journey.policy().sourceDepartment(category);
        } catch (RuntimeException e) {
            return null; // the journey names no provider for this category
        }
    }

    /** "BANK_ACCOUNT" to "Bank account": the manifest's own title is not stored, the category is. */
    static String title(String category) {
        String words = category.toLowerCase(Locale.ROOT).replace('_', ' ');
        return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
