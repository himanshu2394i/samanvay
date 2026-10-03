package com.samanvay.connector.internal.demo;

import com.samanvay.catalog.api.CatalogOnboarding;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.ConnectorTestReport;
import com.samanvay.catalog.api.JourneyWrite;
import com.samanvay.catalog.api.ManifestOnboarding;
import com.samanvay.catalog.api.OnboardRequest;
import com.samanvay.catalog.api.OnboardingPlan;
import com.samanvay.catalog.api.OnboardingResult;
import com.samanvay.connector.api.ConnectorResult;
import com.samanvay.connector.api.ConnectorRuntime;
import com.samanvay.shared.PrincipalRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DEMO/DEV ONLY (profiles {@code dev} and {@code demo}): on startup, onboard the four department services from their manifests and
 * publish every connector whose TRIAL fetch really works, so the demo journeys run against them. It does what an admin does in the
 * console, once, in the background:
 *
 * <ul>
 *   <li>a department that is not running yet is retried a few times, then reported unreachable (never a startup failure);
 *   <li>a department already onboarded from its manifest and unchanged is left alone (idempotent across restarts);
 *   <li>onboarding accepts the suggested field matches (a demo convenience; production onboarding needs an admin's approval);
 *   <li>a connector is published only if its configuration test passes AND a trial fetch for the department's published FAKE sample
 *       person succeeds; otherwise it stays a draft with the reason logged (typically a secret the operator has not provisioned yet).
 * </ul>
 *
 * The bank-check journeys and anything else that does not come from a manifest are untouched.
 */
@Component
@Profile({"dev", "demo"})
@ConditionalOnProperty(name = "samanvay.demo.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class DemoDepartmentBootstrap implements ApplicationRunner {

    public enum Status { UNREACHABLE, UP_TO_DATE, ONBOARDED, FAILED }

    public record Outcome(
            String url, Status status, String message, List<String> published, List<String> leftDraft, List<String> journeysPublished) {}

    private static final Logger log = LoggerFactory.getLogger(DemoDepartmentBootstrap.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final PrincipalRef BOOTSTRAP = new PrincipalRef(PrincipalRef.Kind.ADMIN, "demo-bootstrap");

    private final ManifestOnboarding onboarding;
    private final CatalogOnboarding wizard;
    private final JourneyWrite journeys;
    private final ConnectorCatalog catalog;
    private final ConnectorRuntime runtime;
    private final List<String> urls;
    private final int attempts;
    private final Duration retryDelay;

    @Autowired
    DemoDepartmentBootstrap(
            ManifestOnboarding onboarding,
            CatalogOnboarding wizard,
            JourneyWrite journeys,
            ConnectorCatalog catalog,
            ConnectorRuntime runtime,
            @Value("${samanvay.demo.departments:http://localhost:8091,http://localhost:8092,http://localhost:8093,http://localhost:8094}") List<String> urls,
            @Value("${samanvay.demo.bootstrap.attempts:6}") int attempts,
            @Value("${samanvay.demo.bootstrap.retry-delay:PT5S}") String retryDelay) {
        this(onboarding, wizard, journeys, catalog, runtime, urls, attempts, Duration.parse(retryDelay.trim()));
    }

    public DemoDepartmentBootstrap(
            ManifestOnboarding onboarding,
            CatalogOnboarding wizard,
            JourneyWrite journeys,
            ConnectorCatalog catalog,
            ConnectorRuntime runtime,
            List<String> urls,
            int attempts,
            Duration retryDelay) {
        this.onboarding = onboarding;
        this.wizard = wizard;
        this.journeys = journeys;
        this.catalog = catalog;
        this.runtime = runtime;
        this.urls = urls.stream().map(String::trim).filter(u -> !u.isEmpty()).toList();
        this.attempts = Math.max(1, attempts);
        this.retryDelay = retryDelay;
    }

    /** Runs in the background so a department that is slow to start never delays or fails the application's own startup. */
    @Override
    public void run(ApplicationArguments args) {
        Thread.ofVirtual().name("demo-department-bootstrap").start(() -> {
            try {
                run().forEach(o -> log.info("demo department bootstrap {}: {} {}", o.url(), o.status(), o.message() == null ? "" : o.message()));
            } catch (RuntimeException e) {
                log.warn("demo department bootstrap stopped: {}", e.toString());
            }
        });
    }

    /** One pass over every configured department. Never throws for a department's failure: each is reported. */
    public List<Outcome> run() {
        List<Outcome> out = new ArrayList<>();
        for (String url : urls) {
            out.add(bootstrapOne(url));
        }
        return out;
    }

    private Outcome bootstrapOne(String url) {
        OnboardingPlan plan = planWithRetries(url);
        if (plan == null) {
            return new Outcome(url, Status.UNREACHABLE, "not reachable or no manifest (is the department running?)", List.of(), List.of(), List.of());
        }
        if (plan.pinnedKeyThumbprint() != null && plan.manifestKeyThumbprint() != null
                && !plan.pinnedKeyThumbprint().equals(plan.manifestKeyThumbprint())) {
            return new Outcome(url, Status.FAILED, plan.departmentCode() + ": its manifest signing key changed; an admin must confirm the new key in the staff console",
                    List.of(), List.of(), List.of());
        }
        if (plan.departmentExists() && plan.onboardedFromManifest() && !plan.changedSinceOnboarding()) {
            return new Outcome(url, Status.UP_TO_DATE, plan.departmentCode() + " is already onboarded", List.of(), List.of(), List.of());
        }
        List<String> ready = plan.documents().stream().filter(OnboardingPlan.DocumentPlan::ready).map(OnboardingPlan.DocumentPlan::category).toList();
        if (ready.isEmpty()) {
            return new Outcome(url, Status.FAILED, plan.departmentCode() + ": no document is ready to onboard", List.of(), List.of(), List.of());
        }
        OnboardingResult result;
        try {
            // Demo convenience (like accepting the suggested mappings): approve a signing key the first time it is seen.
            // A key that CHANGED after one was pinned is never auto-approved; an admin must confirm it.
            String approvedKey = plan.pinnedKeyThumbprint() == null ? plan.manifestKeyThumbprint() : null;
            result = onboarding.onboard(new OnboardRequest(url, plan.manifestDigest(), ready, true, Map.of(), approvedKey));
        } catch (RuntimeException e) {
            return new Outcome(url, Status.FAILED, message(e), List.of(), List.of(), List.of());
        }
        List<String> published = new ArrayList<>();
        List<String> leftDraft = new ArrayList<>();
        for (String ref : result.connectorRefs()) {
            publishIfItWorks(ref, published, leftDraft);
        }
        List<String> journeysPublished = new ArrayList<>();
        for (String code : result.journeysCreated()) {
            try {
                journeys.publishJourney(code);
                journeysPublished.add(code);
            } catch (RuntimeException e) {
                leftDraft.add("journey " + code + ": " + message(e));
            }
        }
        return new Outcome(url, Status.ONBOARDED, plan.departmentCode() + ": " + published.size() + " connector(s) published, " + leftDraft.size() + " left as draft",
                List.copyOf(published), List.copyOf(leftDraft), List.copyOf(journeysPublished));
    }

    private OnboardingPlan planWithRetries(String url) {
        for (int i = 1; i <= attempts; i++) {
            try {
                return onboarding.plan(url);
            } catch (RuntimeException e) {
                if (i < attempts && !retryDelay.isZero()) {
                    try {
                        Thread.sleep(retryDelay.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private void publishIfItWorks(String ref, List<String> published, List<String> leftDraft) {
        try {
            ConnectorTestReport report = wizard.test(ref);
            if (!report.passed()) {
                leftDraft.add(ref + ": configuration test failed " + report.failures());
                return;
            }
            String sample = samplePerson(ref);
            if (sample == null) {
                leftDraft.add(ref + ": the department publishes no sample person to trial");
                return;
            }
            ConnectorResult trial = runtime.trial(ref, sample, BOOTSTRAP);
            if (trial instanceof ConnectorResult.Success) {
                wizard.publish(ref, report);
                published.add(ref);
            } else {
                leftDraft.add(ref + ": trial fetch did not succeed (" + trial.getClass().getSimpleName() + ")");
            }
        } catch (RuntimeException e) {
            leftDraft.add(ref + ": " + message(e));
        }
    }

    private String samplePerson(String ref) {
        JsonNode fetch = JSON.readTree(catalog.byRef(ref).capabilitiesJson()).get("FETCH");
        JsonNode s = fetch == null ? null : fetch.get("sample_person_id");
        return s == null || s.asString().isBlank() ? null : s.asString();
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
