package com.samanvay.ops.internal.service;

import com.samanvay.catalog.api.Capability;
import com.samanvay.catalog.api.CatalogDiscovery;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.ConnectorDefinition;
import com.samanvay.catalog.api.DataSourceHealth;
import com.samanvay.catalog.api.JourneyCatalog;
import com.samanvay.catalog.api.JourneyDefinition;
import com.samanvay.connector.api.TrialHistory;
import com.samanvay.ops.internal.service.JourneyStatusView.Category;
import com.samanvay.ops.internal.service.JourneyStatusView.Counts;
import com.samanvay.ops.internal.service.JourneyStatusView.LogRow;
import com.samanvay.ops.internal.service.JourneyStatusView.Recent;
import com.samanvay.ops.internal.service.JourneyStatusView.Trial;
import com.samanvay.orchestration.api.JourneyActivity;
import com.samanvay.shared.DataCategory;
import com.samanvay.tracking.api.ApplicationTracking;
import com.samanvay.tracking.api.ApplicationView;
import com.samanvay.tracking.api.StepView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The per-journey staff page: the journey's definition, whether each required category has a published connector
 * whose source is not down, its recent applications, and their step records as the journey log. Read-only, built
 * from the catalog, connector, orchestration and tracking APIs.
 */
@Service
public class JourneyStatusService {

    static final int RECENT = 20;
    static final int LOG = 100;
    static final Duration WINDOW = Duration.ofDays(7);

    private final JourneyCatalog journeys;
    private final ConnectorCatalog connectors;
    private final CatalogDiscovery discovery;
    private final TrialHistory trials;
    private final JourneyActivity activity;
    private final ApplicationTracking tracking;
    private final Clock clock;

    JourneyStatusService(
            JourneyCatalog journeys,
            ConnectorCatalog connectors,
            CatalogDiscovery discovery,
            TrialHistory trials,
            JourneyActivity activity,
            ApplicationTracking tracking,
            Clock clock) {
        this.journeys = journeys;
        this.connectors = connectors;
        this.discovery = discovery;
        this.trials = trials;
        this.activity = activity;
        this.tracking = tracking;
        this.clock = clock;
    }

    /** @throws com.samanvay.catalog.api.JourneyNotFoundException (404) for an unknown code */
    public JourneyStatusView status(String code) {
        JourneyDefinition journey = journeys.byCode(code);
        Map<String, String> health = new HashMap<>();
        for (DataSourceHealth h : discovery.listDataSources()) {
            health.put(h.code(), h.healthStatus());
        }
        Map<String, ConnectorDefinition> serving = new LinkedHashMap<>();
        List<Category> categories = new ArrayList<>();
        for (String category : journey.requiredCategories()) {
            Optional<ConnectorDefinition> connector = serving(journey, category);
            connector.ifPresent(c -> serving.put(category, c));
            categories.add(category(journey, category, connector, health));
        }

        JourneyActivity.Activity a = activity.activity(code, clock.instant().minus(WINDOW), RECENT);
        List<Recent> recent = new ArrayList<>();
        List<LogRow> log = new ArrayList<>();
        for (JourneyActivity.Recent r : a.recent()) {
            Optional<ApplicationView> app = tracking.byInstanceId(r.instanceId());
            recent.add(new Recent(r.instanceId(), app.map(ApplicationView::referenceNo).orElse(null), r.status(), r.startedAt()));
            app.ifPresent(v -> tracking.steps(v.referenceNo()).forEach(s -> log.add(row(v.referenceNo(), s, r, serving.get(s.stepCode())))));
        }
        log.sort(Comparator.comparing(LogRow::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return new JourneyStatusView(
                journey.code(),
                journey.name(),
                journey.policy() == null ? null : journey.policy().requester(),
                journey.status(),
                journeys.portalUrl(code).orElse(null),
                categories,
                new Counts(a.running(), a.completed(), a.failed(), a.startedSince()),
                recent,
                log.size() > LOG ? List.copyOf(log.subList(0, LOG)) : log);
    }

    private Optional<ConnectorDefinition> serving(JourneyDefinition journey, String category) {
        String department = department(journey, category);
        return department == null
                ? Optional.empty()
                : connectors.resolve(department, DataCategory.of(category), Capability.FETCH);
    }

    private static String department(JourneyDefinition journey, String category) {
        try {
            return journey.policy().sourceDepartment(category);
        } catch (RuntimeException e) {
            return null; // the journey names no provider for this category
        }
    }

    private Category category(
            JourneyDefinition journey, String category, Optional<ConnectorDefinition> connector, Map<String, String> health) {
        String department = department(journey, category);
        if (connector.isEmpty()) {
            return new Category(category, department, null, "NONE", null, "UNKNOWN", null, false);
        }
        ConnectorDefinition c = connector.get();
        String source = connectors.dataSourceFor(c).code();
        String sourceHealth = health.getOrDefault(source, "UNKNOWN");
        Trial trial = trials.last(c.ref()).map(t -> new Trial(t.at(), t.outcome())).orElse(null);
        boolean working = SourceWorking.working(c.status() == com.samanvay.catalog.api.ConnectorStatus.PUBLISHED, sourceHealth,
                connectors.dataSourceFor(c).protocol(), trial == null ? null : trial.outcome());
        return new Category(category, department, c.ref(), c.status().name(), source, sourceHealth, trial, working);
    }

    /** The connector version the instance pinned at start, else today's serving connector (older instances pinned none). */
    private static LogRow row(String referenceNo, StepView s, JourneyActivity.Recent instance, ConnectorDefinition serving) {
        String connector = null;
        if (serving != null) {
            Integer pinned = instance.pinnedVersions().get(serving.connectorId());
            connector = pinned == null ? serving.ref() : serving.connectorId() + "@" + pinned;
        }
        Instant at = s.completedAt() != null ? s.completedAt() : s.startedAt();
        Long latency = s.startedAt() != null && s.completedAt() != null
                ? Math.max(0, Duration.between(s.startedAt(), s.completedAt()).toMillis())
                : null;
        String error = "COMPLETED".equals(s.status()) ? null : s.outcome().orElse(s.status());
        return new LogRow(at, referenceNo, s.stepCode(), s.departmentCode(), connector, s.status(), latency, error);
    }
}
