package com.samanvay.catalog.internal.service;

import com.samanvay.catalog.api.DepartmentManifest;
import com.samanvay.catalog.api.DepartmentManifest.Document;
import com.samanvay.catalog.api.JourneyDraft;
import com.samanvay.catalog.api.MappingSuggestion;
import com.samanvay.catalog.api.OnboardingPlan;
import com.samanvay.catalog.api.OnboardingPlan.DocumentPlan;
import com.samanvay.catalog.api.OnboardingPlan.JourneyPlan;
import com.samanvay.catalog.api.PendingStep;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns a department's published manifest into a plan (docs/FINAL-CHANGES.md sections 3 and 10): which data sources to
 * register (grouped by protocol, host and auth), a connector per document with its capabilities and inputs, propose-only
 * field mappings onto the SEEDED central schema, the journeys it publishes, and the steps only an operator can do.
 *
 * <p>Pure: it reads nothing and changes nothing; the current catalog state comes in through {@link Env}. Source codes are
 * assigned over ALL documents, so they do not depend on which documents the admin ticks.
 *
 * <p>Every document's one input is bound to the citizen's person ID at that department ({@code link.personId}); a document
 * that needs more than that cannot be bound automatically and is flagged. A document with a {@code resolve} step binds only
 * the person ID, and the runtime fills the document key from the resolve answer.
 */
final class ManifestOnboardingPlanner {

    private static final JsonMapper JSON = JsonMapper.builder()
            .configure(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final int DEFAULT_SLA_MS = 5000;
    private static final String NO_PINNED_KEY = "(not published: capture it from the server and pin it)";

    /** A seeded central schema: its ref, its fields and which of them are required. */
    record SchemaInfo(String ref, List<String> properties, List<String> required) {}

    /** The current catalog state the plan depends on. */
    record Env(
            boolean departmentExists,
            String storedDigest,
            Function<String, Optional<SchemaInfo>> schemaByCategory,
            Function<String, Optional<String>> existingConnectorId,
            Set<String> existingJourneys) {}

    record SourceSpec(String code, String protocol, String baseHost, String authType, String authConfigRef, String authSpecJson) {}

    record ConnectorSpec(
            String category,
            String connectorId,
            String sourceCode,
            ObjectNode capabilities,
            String inputsJson,
            int slaMs,
            String schemaRef,
            List<MappingSuggestion> suggestions) {}

    record JourneySpec(JourneyDraft draft, boolean exists) {}

    record Planned(OnboardingPlan plan, List<SourceSpec> sources, List<ConnectorSpec> connectors, List<JourneySpec> journeys) {}

    private final MappingSuggestor suggestor = new MappingSuggestor();

    /** SHA-256 (hex) of the manifest as Samanvay understands it; it identifies exactly what an admin reviewed. */
    static String digest(DepartmentManifest m) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(m).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @param selected the ticked categories; null means every document */
    Planned plan(DepartmentManifest m, String baseUrl, Set<String> selected, Env env) {
        String dept = m.department().code();
        String deptLower = dept.toLowerCase(Locale.ROOT);
        String digest = digest(m);
        String restHost = authority(baseUrl);

        // Source codes over ALL documents, so a subset selection never renames a source.
        Map<String, SourceSpec> groups = new LinkedHashMap<>();
        Map<String, String> sourceCodeByCategory = new LinkedHashMap<>();
        Map<String, Integer> perProtocol = new LinkedHashMap<>();
        for (Document d : m.documents()) {
            String host = hostFor(d, restHost);
            String authJson = authJson(d);
            String key = d.protocol() + "|" + host + "|" + authJson;
            SourceSpec spec = groups.get(key);
            if (spec == null) {
                String base = deptLower + "-" + shortProtocol(d.protocol());
                int n = perProtocol.merge(d.protocol(), 1, Integer::sum);
                String code = n == 1 ? base : base + "-" + n;
                String scheme = d.auth() == null || d.auth().scheme() == null ? "NONE" : d.auth().scheme();
                spec = new SourceSpec(code, d.protocol(), host, scheme, "NONE".equals(scheme) ? "secret:none" : "secret:" + code, authJson);
                groups.put(key, spec);
            }
            sourceCodeByCategory.put(d.category(), spec.code());
        }
        Map<String, SourceSpec> sourceByCode = groups.values().stream().collect(Collectors.toMap(SourceSpec::code, s -> s, (a, b) -> a, LinkedHashMap::new));

        List<DocumentPlan> docPlans = new ArrayList<>();
        List<ConnectorSpec> connectorSpecs = new ArrayList<>();
        for (Document d : m.documents()) {
            String sourceCode = sourceCodeByCategory.get(d.category());
            List<String> problems = new ArrayList<>();
            Optional<SchemaInfo> schema = env.schemaByCategory().apply(d.category());
            if (schema.isEmpty()) {
                problems.add("No central schema is seeded for category " + d.category() + ": seed it first, then onboard.");
            }
            ObjectNode fetch = F.objectNode();
            ArrayNode inputs = F.arrayNode();
            buildAccess(d, fetch, inputs, problems);
            if (m.sample() != null && m.sample().personId() != null && !m.sample().personId().isBlank()) {
                fetch.put("sample_person_id", m.sample().personId());
            }
            schema.ifPresent(s -> fetch.put("output_schema", s.ref()));
            fetch.putArray("error_paths");

            List<MappingSuggestion> suggestions = List.of();
            List<String> unmapped = List.of();
            if (schema.isPresent()) {
                suggestions = suggestor.suggest(d.fields().stream().map(DepartmentManifest.Field::name).toList(), schema.get().properties());
                Set<String> covered = suggestions.stream().map(MappingSuggestion::target).collect(Collectors.toSet());
                unmapped = schema.get().required().stream().filter(r -> !covered.contains(r)).toList();
            }
            Optional<String> existing = env.existingConnectorId().apply(d.category());
            String connectorId = existing.orElse(deptLower + "-" + d.category().toLowerCase(Locale.ROOT).replace('_', '-'));
            boolean ready = problems.isEmpty() && unmapped.isEmpty() && schema.isPresent();
            docPlans.add(new DocumentPlan(d.category(), d.title(), d.protocol(), sourceCode, connectorId, existing.isPresent(),
                    schema.map(SchemaInfo::ref).orElse(null), suggestions, unmapped, List.copyOf(problems), ready));
            if (selected == null || selected.contains(d.category())) {
                ObjectNode caps = F.objectNode();
                caps.set("FETCH", fetch);
                connectorSpecs.add(new ConnectorSpec(d.category(), connectorId, sourceCode, caps, inputs.toString(), DEFAULT_SLA_MS,
                        schema.map(SchemaInfo::ref).orElse(null), suggestions));
            }
        }

        List<SourceSpec> sources = connectorSpecs.stream().map(ConnectorSpec::sourceCode).distinct().map(sourceByCode::get).toList();

        List<JourneySpec> journeys = new ArrayList<>();
        List<JourneyPlan> journeyPlans = new ArrayList<>();
        for (DepartmentManifest.Journey j : m.journeys() == null ? List.<DepartmentManifest.Journey>of() : m.journeys()) {
            List<String> cats = j.requiredCategories().stream().map(DepartmentManifest.RequiredCategory::category).toList();
            Map<String, String> src = new LinkedHashMap<>();
            j.requiredCategories().forEach(rc -> src.put(rc.category(), rc.department()));
            boolean exists = env.existingJourneys().contains(j.code());
            journeys.add(new JourneySpec(new JourneyDraft(j.code(), j.name(), j.referencePrefix(), j.slaHours(), j.consentPurpose(), j.requester(), cats, src), exists));
            journeyPlans.add(new JourneyPlan(j.code(), j.name(), exists, cats));
        }

        boolean changed = env.departmentExists() && env.storedDigest() != null && !env.storedDigest().equals(digest);
        OnboardingPlan plan = new OnboardingPlan(dept, m.department().name(), digest, env.departmentExists(), env.storedDigest() != null, changed, List.copyOf(docPlans),
                List.copyOf(journeyPlans), pendingSteps(m, sources));
        return new Planned(plan, List.copyOf(sources), List.copyOf(connectorSpecs), List.copyOf(journeys));
    }

    // --- per-protocol capabilities and inputs ---------------------------------------------------------------

    private static void buildAccess(Document d, ObjectNode fetch, ArrayNode inputs, List<String> problems) {
        switch (d.protocol()) {
            case "REST" -> {
                fetch.put("endpoint", d.path());
                if ("POST".equalsIgnoreCase(d.method())) {
                    fetch.put("method", "POST");
                    String body = (d.inputs() == null ? List.<DepartmentManifest.Input>of() : d.inputs()).stream()
                            .filter(i -> "body".equals(i.in())).map(DepartmentManifest.Input::name).collect(Collectors.joining(","));
                    if (!body.isEmpty()) {
                        fetch.put("body_inputs", body);
                    }
                }
                DepartmentManifest.Resolve r = d.lookup() == null ? null : d.lookup().resolve();
                if (r != null) {
                    String into = d.inputs().stream().filter(i -> "path".equals(i.in())).map(DepartmentManifest.Input::name).findFirst().orElse(null);
                    if (into == null) {
                        problems.add("The document has a resolve step but its path has no {input} to receive the resolved key.");
                    } else {
                        ObjectNode resolve = fetch.putObject("resolve");
                        resolve.put("path", r.path() + queryString(r.query()));
                        if ("POST".equalsIgnoreCase(r.method())) {
                            // the person ID goes in the body (it does not belong in a URL), the same as a POST document call
                            resolve.put("method", "POST");
                            resolve.put("body_inputs", "personId");
                        }
                        resolve.put("list_field", r.listField() == null ? "documents" : r.listField());
                        resolve.put("key_field", r.keyField() == null ? "key" : r.keyField());
                        resolve.put("select", r.latestField() == null ? "first" : "latest");
                        if (r.latestField() != null) {
                            resolve.put("latest_field", r.latestField());
                        }
                        resolve.put("into", into);
                    }
                    inputs.add(personInput("personId"));
                } else {
                    bindSingleInput(d, inputs, problems);
                }
            }
            case "SOAP" -> {
                DepartmentManifest.Soap soap = d.access() == null ? null : d.access().soap();
                fetch.put("endpoint", soap != null && soap.endpoint() != null ? soap.endpoint() : d.path());
                if (soap != null && soap.soapAction() != null && !soap.soapAction().isBlank()) {
                    fetch.put("soap_action", soap.soapAction());
                }
                if (soap == null || soap.requestTemplate() == null) {
                    problems.add("A SOAP document must publish access.soap.requestTemplate.");
                } else {
                    fetch.put("template", soap.requestTemplate());
                }
                bindSingleInput(d, inputs, problems);
            }
            case "SFTP_CSV" -> {
                DepartmentManifest.Sftp sftp = d.access() == null ? null : d.access().sftp();
                if (sftp == null || sftp.keyColumn() == null) {
                    problems.add("An SFTP document must publish access.sftp with a keyColumn.");
                    return;
                }
                fetch.put("endpoint", sftp.directory() + "/" + sftp.fileNamePattern());
                fetch.put("key_column", sftp.keyColumn());
                inputs.add(personInput(sftp.keyColumn()));
            }
            case "JDBC" -> {
                DepartmentManifest.Jdbc jdbc = d.access() == null ? null : d.access().jdbc();
                if (jdbc == null || jdbc.readOnlyView() == null || jdbc.keyColumn() == null) {
                    problems.add("A JDBC document must publish access.jdbc with a readOnlyView and a keyColumn.");
                    return;
                }
                fetch.put("endpoint", "/");
                fetch.put("view", jdbc.readOnlyView());
                fetch.put("key_column", jdbc.keyColumn());
                inputs.add(personInput(jdbc.keyColumn()));
            }
            default -> problems.add("Unsupported protocol " + d.protocol() + ".");
        }
    }

    private static void bindSingleInput(Document d, ArrayNode inputs, List<String> problems) {
        List<DepartmentManifest.Input> declared = d.inputs() == null ? List.of() : d.inputs();
        if (declared.size() != 1) {
            problems.add("The document declares " + declared.size() + " inputs; only a single person-ID input can be bound automatically.");
            return;
        }
        inputs.add(personInput(declared.get(0).name()));
    }

    private static ObjectNode personInput(String name) {
        ObjectNode n = F.objectNode();
        n.put("name", name);
        n.put("from", "link.personId");
        n.put("required", true);
        return n;
    }

    private static String queryString(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        return "?" + query.entrySet().stream()
                .map(e -> e.getKey() + "=" + java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    // --- grouping -------------------------------------------------------------------------------------------

    private static String hostFor(Document d, String restHost) {
        DepartmentManifest.Access a = d.access();
        if ("SFTP_CSV".equals(d.protocol()) && a != null && a.sftp() != null) {
            return a.sftp().host() + ":" + a.sftp().port();
        }
        if ("JDBC".equals(d.protocol()) && a != null && a.jdbc() != null) {
            return a.jdbc().host() + ":" + a.jdbc().port();
        }
        return restHost;
    }

    /**
     * The identity block says whose signature Samanvay trusts for "this person is X", so its login and key URLs must be on
     * the same host as the manifest the admin typed in. Otherwise a manifest could name someone else's keys.
     * ponytail: host-only match; a department that serves login from another host needs an operator allow-list.
     */
    static Optional<String> identityHostProblem(DepartmentManifest m, String baseUrl) {
        if (m.identity() == null) {
            return Optional.empty();
        }
        String expected = hostOf(baseUrl);
        for (String url : new String[] {m.identity().loginUrl(), m.identity().jwksUrl()}) {
            if (url == null || url.isBlank()) {
                continue;
            }
            String host = hostOf(url);
            if (host == null || !host.equals(expected)) {
                return Optional.of("The department's login/key address " + url + " is not on the manifest's own host (" + expected
                        + "), so it cannot be trusted to sign who a person is.");
            }
        }
        return Optional.empty();
    }

    private static String hostOf(String url) {
        try {
            String h = URI.create(url.trim()).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String authority(String baseUrl) {
        String a = URI.create(baseUrl.trim()).getAuthority();
        if (a == null) {
            throw new IllegalArgumentException("baseUrl must include a scheme and host");
        }
        return a;
    }

    private static String shortProtocol(String protocol) {
        return switch (protocol) {
            case "SFTP_CSV" -> "sftp";
            default -> protocol.toLowerCase(Locale.ROOT);
        };
    }

    private static String authJson(Document d) {
        return d.auth() == null ? "{}" : JSON.writeValueAsString(d.auth());
    }

    // --- operator steps -------------------------------------------------------------------------------------

    private static List<PendingStep> pendingSteps(DepartmentManifest m, List<SourceSpec> sources) {
        List<PendingStep> steps = new ArrayList<>();
        for (SourceSpec s : sources) {
            JsonNode auth = JSON.readTree(s.authSpecJson());
            if (!"NONE".equals(s.authType())) {
                List<String> names = new ArrayList<>();
                if (auth.get("parameters") != null) {
                    auth.get("parameters").forEach(p -> names.add(p.get("name").asString()));
                }
                steps.add(new PendingStep("PROVISION_SECRET", s.code(),
                        "Provision the credential the department gave you into the SecretStore. Values are never entered here.",
                        Map.of("secretKey", "source-" + s.code() + "-credential", "parameters", String.join(", ", names),
                                "format", "a JSON object of strings keyed by those parameter names")));
            }
            switch (s.protocol()) {
                case "REST", "SOAP" -> steps.add(new PendingStep("CONFIGURE_HTTPS", s.code(),
                        "Samanvay calls REST and SOAP sources over HTTPS. Make sure " + s.baseHost() + " serves HTTPS. In dev/demo only, map the source to a local URL.",
                        Map.of("host", s.baseHost(), "devOverrideProperty", "samanvay.sources.department-service.urls." + s.code())));
                case "SFTP_CSV" -> steps.add(sftpStep(m, s));
                case "JDBC" -> steps.add(jdbcStep(m, s));
                default -> { }
            }
        }
        if (m.journeys() != null && !m.journeys().isEmpty()) {
            steps.add(callerCredentialStep(m, sources));
        }
        return List.copyOf(steps);
    }

    /**
     * The other direction of credential: the department's PORTAL calls Samanvay (to start journeys and request consent) as a
     * client-credentials client in the staff realm carrying the department claim and one source scope per data source. An
     * identity-provider admin creates it; the secret is handed to the department out of band and never appears here.
     */
    private static PendingStep callerCredentialStep(DepartmentManifest m, List<SourceSpec> sources) {
        String code = m.department().code();
        String scopes = sources.stream().map(s -> "source:" + s.code()).collect(Collectors.joining(", "));
        return new PendingStep("ISSUE_CALLER_CREDENTIAL", code,
                "Create a client-credentials client in the staff realm for the " + m.department().name() + " department portal so it can call Samanvay. "
                        + "Give the department its client id and secret out of band: the secret is never entered or shown here.",
                Map.of("clientId", "dept-" + code.toLowerCase(Locale.ROOT), "department", code, "role", "department",
                        "scopes", scopes, "allowedClientsProperty", "samanvay.security.staff.allowed-clients"));
    }

    private static PendingStep sftpStep(DepartmentManifest m, SourceSpec s) {
        DepartmentManifest.Sftp sftp = m.documents().stream()
                .filter(d -> d.access() != null && d.access().sftp() != null && (d.access().sftp().host() + ":" + d.access().sftp().port()).equals(s.baseHost()))
                .map(d -> d.access().sftp()).findFirst().orElse(null);
        String pin = sftp == null || sftp.hostKeyFingerprint() == null || sftp.hostKeyFingerprint().isBlank() ? NO_PINNED_KEY : sftp.hostKeyFingerprint();
        return new PendingStep("CONFIGURE_SFTP", s.code(),
                "SFTP hosts and host-key pins are operator-set (a manifest cannot redirect Samanvay). Configure this source and restart.",
                Map.of("property", "samanvay.sources.sftp.sources." + s.code(), "host", s.baseHost(),
                        "remotePath", sftp == null ? "" : sftp.directory() + "/" + sftp.fileNamePattern(), "hostKeyFingerprint", pin));
    }

    private static PendingStep jdbcStep(DepartmentManifest m, SourceSpec s) {
        DepartmentManifest.Jdbc jdbc = m.documents().stream()
                .filter(d -> d.access() != null && d.access().jdbc() != null && (d.access().jdbc().host() + ":" + d.access().jdbc().port()).equals(s.baseHost()))
                .map(d -> d.access().jdbc()).findFirst().orElse(null);
        return new PendingStep("CONFIGURE_JDBC", s.code(),
                "Database URLs are operator-set. Configure the JDBC URL (TLS required) for this source and restart; it reads only the published view.",
                Map.of("property", "samanvay.sources.jdbc.sources." + s.code() + ".jdbc-url", "host", s.baseHost(),
                        "database", jdbc == null ? "" : jdbc.database(), "view", jdbc == null ? "" : jdbc.readOnlyView()));
    }
}
