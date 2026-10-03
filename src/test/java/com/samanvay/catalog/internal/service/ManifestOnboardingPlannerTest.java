package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.catalog.api.DepartmentManifest;
import com.samanvay.catalog.api.OnboardingPlan;
import com.samanvay.catalog.api.PendingStep;
import com.samanvay.catalog.internal.service.ManifestOnboardingPlanner.ConnectorSpec;
import com.samanvay.catalog.internal.service.ManifestOnboardingPlanner.Env;
import com.samanvay.catalog.internal.service.ManifestOnboardingPlanner.Planned;
import com.samanvay.catalog.internal.service.ManifestOnboardingPlanner.SchemaInfo;
import com.samanvay.catalog.internal.service.ManifestOnboardingPlanner.SourceSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turning a department's published manifest into a plan: data sources grouped by protocol/host/auth, a connector per
 * document with its capabilities and inputs, propose-only mappings onto the seeded central schema, journeys, and the
 * steps only an operator can do. Pure logic, run against the REAL manifests the four departments publish.
 */
class ManifestOnboardingPlannerTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    /** The seeded central schemas (mirrors V203), by category. */
    static final Map<String, SchemaInfo> CENTRAL = Map.of(
            "INCOME_CERTIFICATE", new SchemaInfo("Credential/IncomeCertificate@1",
                    List.of("annualIncome", "annualIncomeDisplay", "holderName", "district", "issuerOffice", "financialYear"), List.of("annualIncome")),
            "CASTE_CERTIFICATE", new SchemaInfo("Credential/CasteCertificate@1", List.of("casteCategory", "caste", "holderName", "issuerOffice"), List.of("casteCategory")),
            "DOMICILE_CERTIFICATE", new SchemaInfo("Credential/DomicileCertificate@1", List.of("district", "state", "holderName", "issuerOffice"), List.of("district")),
            "LAND_PARCEL", new SchemaInfo("LandParcel@1", List.of("surveyNo", "village", "taluka", "district", "areaHectares", "ownerName"), List.of("surveyNo")),
            "MARKS", new SchemaInfo("Credential/Marks@1", List.of("percentage", "board", "exam"), List.of("percentage")),
            "BANK_ACCOUNT", new SchemaInfo("Credential/BankAccount@1", List.of("accountRef", "ifscMasked", "holderName"), List.of("accountRef")),
            "FARMER_RECORD", new SchemaInfo("Credential/FarmerRecord@1", List.of("farmerName", "village", "taluka", "landHectares"), List.of("farmerName")),
            "CROP_RECORD", new SchemaInfo("Credential/CropRecord@1", List.of("crop", "season", "areaHectares"), List.of()));

    static Env env() {
        return new Env(false, null, c -> Optional.ofNullable(CENTRAL.get(c)), c -> Optional.empty(), Set.of());
    }

    static DepartmentManifest manifest(String dept) throws IOException {
        try (var in = ManifestOnboardingPlannerTest.class.getResourceAsStream("/manifests/" + dept + ".json")) {
            return CatalogServices.parseManifest(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    Planned plan(String dept, Env env, Set<String> categories) throws IOException {
        return new ManifestOnboardingPlanner().plan(manifest(dept), "https://" + dept + ".example.gov", categories, env);
    }

    static ConnectorSpec connector(Planned p, String category) {
        return p.connectors().stream().filter(c -> c.category().equals(category)).findFirst().orElseThrow();
    }

    static SourceSpec source(Planned p, String code) {
        return p.sources().stream().filter(s -> s.code().equals(code)).findFirst().orElseThrow();
    }

    // --- Revenue: REST with resolve + SFTP -----------------------------------------------------------------

    @Test
    void revenue_gets_one_rest_source_and_one_sftp_source_each_with_its_own_auth() throws IOException {
        Planned p = plan("revenue", env(), null);
        assertThat(p.sources()).extracting(SourceSpec::code).containsExactlyInAnyOrder("revenue-rest", "revenue-sftp");
        SourceSpec rest = source(p, "revenue-rest");
        assertThat(rest.protocol()).isEqualTo("REST");
        assertThat(rest.baseHost()).isEqualTo("revenue.example.gov");
        assertThat(rest.authType()).isEqualTo("API_KEY");
        assertThat(rest.authConfigRef()).isEqualTo("secret:revenue-rest");
        assertThat(JSON.readTree(rest.authSpecJson()).get("parameters").get(0).get("name").asString()).isEqualTo("X-Api-Key");
        SourceSpec sftp = source(p, "revenue-sftp");
        assertThat(sftp.protocol()).isEqualTo("SFTP_CSV");
        assertThat(sftp.baseHost()).isEqualTo("localhost:2223");
        assertThat(sftp.authType()).isEqualTo("PASSWORD");
    }

    @Test
    void a_post_resolve_declares_its_method_and_carries_the_person_id_in_the_body() throws IOException {
        String json = new String(ManifestOnboardingPlannerTest.class.getResourceAsStream("/manifests/revenue.json").readAllBytes(), StandardCharsets.UTF_8)
                .replace("\"method\":\"GET\",\"path\":\"/v1/persons/{personId}/documents\"", "\"method\":\"POST\",\"path\":\"/v1/documents/search\"");
        Planned p = new ManifestOnboardingPlanner().plan(CatalogServices.parseManifest(json), "https://revenue.example.gov", null, env());
        var resolve = connector(p, "INCOME_CERTIFICATE").capabilities().get("FETCH").get("resolve");
        assertThat(resolve.get("method").asString()).isEqualTo("POST");
        assertThat(resolve.get("body_inputs").asString()).isEqualTo("personId");
        assertThat(resolve.get("path").asString()).startsWith("/v1/documents/search");
    }

    @Test
    void a_rest_document_with_a_resolve_step_gets_a_resolve_capability_and_binds_only_the_person_id() throws IOException {
        ConnectorSpec c = connector(plan("revenue", env(), null), "INCOME_CERTIFICATE");
        var fetch = c.capabilities().get("FETCH");
        assertThat(fetch.get("endpoint").asString()).isEqualTo("/v1/income/{key}");
        assertThat(fetch.get("output_schema").asString()).isEqualTo("Credential/IncomeCertificate@1");
        var resolve = fetch.get("resolve");
        assertThat(resolve.get("path").asString()).isEqualTo("/v1/persons/{personId}/documents?type=INCOME_CERTIFICATE");
        assertThat(resolve.get("list_field").asString()).isEqualTo("documents");
        assertThat(resolve.get("key_field").asString()).isEqualTo("key");
        assertThat(resolve.get("latest_field").asString()).isEqualTo("latest");
        assertThat(resolve.get("select").asString()).isEqualTo("latest");
        assertThat(resolve.get("into").asString()).isEqualTo("key");
        assertThat(JSON.readTree(c.inputsJson())).hasSize(1);
        assertThat(JSON.readTree(c.inputsJson()).get(0).get("name").asString()).isEqualTo("personId");
        assertThat(JSON.readTree(c.inputsJson()).get(0).get("from").asString()).isEqualTo("link.personId");
        assertThat(c.sourceCode()).isEqualTo("revenue-rest");
    }

    @Test
    void an_sftp_document_declares_its_key_column_and_binds_the_person_id_under_that_name() throws IOException {
        ConnectorSpec c = connector(plan("revenue", env(), null), "LAND_PARCEL");
        var fetch = c.capabilities().get("FETCH");
        assertThat(fetch.get("endpoint").asString()).isEqualTo("/outbound/712.csv");
        assertThat(fetch.get("key_column").asString()).isEqualTo("personId");
        assertThat(fetch.has("resolve")).isFalse();
        assertThat(JSON.readTree(c.inputsJson()).get(0).get("name").asString()).isEqualTo("personId");
        assertThat(JSON.readTree(c.inputsJson()).get(0).get("from").asString()).isEqualTo("link.personId");
        assertThat(c.sourceCode()).isEqualTo("revenue-sftp");
    }

    // --- the other three departments -------------------------------------------------------------------------

    @Test
    void dbt_uses_oauth2_and_binds_its_declared_input_to_the_person_id_with_no_resolve() throws IOException {
        Planned p = plan("dbt", env(), null);
        assertThat(source(p, "dbt-rest").authType()).isEqualTo("OAUTH2_CLIENT");
        assertThat(JSON.readTree(source(p, "dbt-rest").authSpecJson()).get("tokenUrl").asString()).isEqualTo("/oauth/token");
        ConnectorSpec c = connector(p, "BANK_ACCOUNT");
        assertThat(c.capabilities().get("FETCH").has("resolve")).isFalse();
        assertThat(JSON.readTree(c.inputsJson()).get(0).get("name").asString()).isEqualTo("dbtId");
        assertThat(JSON.readTree(c.inputsJson()).get(0).get("from").asString()).isEqualTo("link.personId");
    }

    @Test
    void a_post_document_declares_its_method_and_which_inputs_travel_in_the_body_a_get_document_declares_neither() throws IOException {
        var dbt = connector(plan("dbt", env(), null), "BANK_ACCOUNT").capabilities().get("FETCH");
        assertThat(dbt.get("method").asString()).isEqualTo("POST");
        assertThat(dbt.get("body_inputs").asString()).isEqualTo("dbtId");
        var revenue = connector(plan("revenue", env(), null), "INCOME_CERTIFICATE").capabilities().get("FETCH");
        assertThat(revenue.has("method")).isFalse();
        assertThat(revenue.has("body_inputs")).isFalse();
    }

    @Test
    void the_soap_action_and_the_sample_person_the_department_publishes_are_carried_into_the_connector() throws IOException {
        var marks = connector(plan("education", env(), null), "MARKS").capabilities().get("FETCH");
        assertThat(marks.get("soap_action").asString()).isEqualTo("GetMarks");
        assertThat(marks.get("sample_person_id").asString()).isEqualTo("EDU-1001");
        for (String dept : List.of("revenue", "dbt", "education", "agriculture")) {
            assertThat(plan(dept, env(), null).connectors()).as(dept).allSatisfy(c ->
                    assertThat(c.capabilities().get("FETCH").get("sample_person_id").asString()).isNotBlank());
        }
    }

    @Test
    void education_soap_carries_its_request_template_and_ws_security_auth() throws IOException {
        Planned p = plan("education", env(), null);
        assertThat(source(p, "education-soap").protocol()).isEqualTo("SOAP");
        assertThat(source(p, "education-soap").authType()).isEqualTo("WS_SECURITY_USERNAME");
        var fetch = connector(p, "MARKS").capabilities().get("FETCH");
        assertThat(fetch.get("endpoint").asString()).isEqualTo("/marks/service");
        assertThat(fetch.get("template").asString()).contains("{{studentId}}").contains("<soap:Header");
        assertThat(JSON.readTree(connector(p, "MARKS").inputsJson()).get(0).get("name").asString()).isEqualTo("studentId");
    }

    @Test
    void agriculture_gets_a_jdbc_source_reading_a_view_and_an_sftp_source() throws IOException {
        Planned p = plan("agriculture", env(), null);
        assertThat(p.sources()).extracting(SourceSpec::code).containsExactlyInAnyOrder("agriculture-jdbc", "agriculture-sftp");
        assertThat(source(p, "agriculture-jdbc").baseHost()).isEqualTo("localhost:5434");
        assertThat(source(p, "agriculture-jdbc").authType()).isEqualTo("DB_USER");
        var fetch = connector(p, "FARMER_RECORD").capabilities().get("FETCH");
        assertThat(fetch.get("view").asString()).isEqualTo("v_farmer_record");
        assertThat(fetch.get("key_column").asString()).isEqualTo("agri_person_id");
        assertThat(fetch.has("template")).isFalse();
        assertThat(connector(p, "CROP_RECORD").capabilities().get("FETCH").get("key_column").asString()).isEqualTo("agriPersonId");
    }

    // --- readiness and mapping -----------------------------------------------------------------------------

    @Test
    void every_document_of_every_department_is_ready_with_a_mapping_suggestion_for_each_required_central_field() throws IOException {
        for (String dept : List.of("revenue", "dbt", "education", "agriculture")) {
            Planned p = plan(dept, env(), null);
            assertThat(p.plan().documents()).as(dept).isNotEmpty().allSatisfy(d -> {
                assertThat(d.problems()).as(d.category()).isEmpty();
                assertThat(d.unmappedRequired()).as(d.category()).isEmpty();
                assertThat(d.ready()).as(d.category()).isTrue();
                assertThat(d.centralSchemaRef()).as(d.category()).isNotNull();
                assertThat(d.suggestions()).as(d.category()).isNotEmpty().allSatisfy(s -> assertThat(s.approved()).isFalse()); // propose-only
            });
        }
    }

    @Test
    void a_document_whose_category_has_no_central_schema_is_not_ready_and_says_to_seed_it_first() throws IOException {
        Env noIncome = new Env(false, null, c -> "INCOME_CERTIFICATE".equals(c) ? Optional.empty() : Optional.ofNullable(CENTRAL.get(c)),
                c -> Optional.empty(), Set.of());
        var doc = plan("revenue", noIncome, null).plan().documents().stream().filter(d -> d.category().equals("INCOME_CERTIFICATE")).findFirst().orElseThrow();
        assertThat(doc.ready()).isFalse();
        assertThat(doc.centralSchemaRef()).isNull();
        assertThat(doc.problems()).anyMatch(s -> s.contains("seed") && s.contains("INCOME_CERTIFICATE"));
    }

    @Test
    void a_required_central_field_nothing_maps_to_is_listed_and_blocks_readiness() throws IOException {
        var odd = new SchemaInfo("Credential/BankAccount@1", List.of("accountRef", "ifscMasked", "holderName", "swiftBic"), List.of("accountRef", "swiftBic"));
        Env env = new Env(false, null, c -> "BANK_ACCOUNT".equals(c) ? Optional.of(odd) : Optional.ofNullable(CENTRAL.get(c)), c -> Optional.empty(), Set.of());
        var doc = plan("dbt", env, null).plan().documents().get(0);
        assertThat(doc.unmappedRequired()).containsExactly("swiftBic");
        assertThat(doc.ready()).isFalse();
    }

    @Test
    void a_document_with_several_inputs_and_no_resolve_cannot_be_bound_automatically() throws IOException {
        String json = "{\"manifestVersion\":2,\"department\":{\"code\":\"X\",\"name\":\"X\",\"description\":\"d\"},\"documents\":[{\"category\":\"MARKS\","
                + "\"title\":\"m\",\"protocol\":\"REST\",\"method\":\"GET\",\"path\":\"/m\",\"inputs\":[{\"name\":\"a\",\"in\":\"query\",\"required\":true,\"description\":\"\"},"
                + "{\"name\":\"b\",\"in\":\"query\",\"required\":true,\"description\":\"\"}],\"fields\":[{\"name\":\"percentage\",\"type\":\"number\",\"sensitive\":false}]}],\"journeys\":[]}";
        var doc = new ManifestOnboardingPlanner().plan(CatalogServices.parseManifest(json), "https://x.example.gov", null, env()).plan().documents().get(0);
        assertThat(doc.ready()).isFalse();
        assertThat(doc.problems()).anyMatch(s -> s.contains("2 inputs"));
    }

    // --- selection, existing state, journeys -----------------------------------------------------------------

    @Test
    void only_the_ticked_documents_get_connectors_and_only_their_sources() throws IOException {
        Planned p = plan("revenue", env(), Set.of("LAND_PARCEL"));
        assertThat(p.connectors()).extracting(ConnectorSpec::category).containsExactly("LAND_PARCEL");
        assertThat(p.sources()).extracting(SourceSpec::code).containsExactly("revenue-sftp");
        // the plan still shows every document so the admin can tick more
        assertThat(p.plan().documents()).hasSize(4);
    }

    @Test
    void a_category_already_served_by_a_connector_of_this_department_becomes_a_new_version_of_it() throws IOException {
        Env env = new Env(true, null, c -> Optional.ofNullable(CENTRAL.get(c)), c -> "BANK_ACCOUNT".equals(c) ? Optional.of("dbt-bank") : Optional.empty(), Set.of());
        Planned p = plan("dbt", env, null);
        assertThat(connector(p, "BANK_ACCOUNT").connectorId()).isEqualTo("dbt-bank");
        assertThat(p.plan().documents().get(0).newVersionOfExisting()).isTrue();
        Planned fresh = plan("dbt", env(), null);
        assertThat(connector(fresh, "BANK_ACCOUNT").connectorId()).isEqualTo("dbt-bank-account");
        assertThat(fresh.plan().documents().get(0).newVersionOfExisting()).isFalse();
    }

    @Test
    void journeys_are_planned_with_their_required_categories_and_provider_departments_and_existing_ones_are_flagged() throws IOException {
        Planned p = plan("education", new Env(false, null, c -> Optional.ofNullable(CENTRAL.get(c)), c -> Optional.empty(), Set.of("POST_MATRIC_SCHOLARSHIP")), null);
        assertThat(p.plan().journeys()).hasSize(1);
        assertThat(p.plan().journeys().get(0).exists()).isTrue();
        assertThat(p.plan().journeys().get(0).requiredCategories()).containsExactlyInAnyOrder("INCOME_CERTIFICATE", "CASTE_CERTIFICATE", "MARKS", "BANK_ACCOUNT");
        var draft = p.journeys().get(0).draft();
        assertThat(draft.sources()).containsEntry("INCOME_CERTIFICATE", "REVENUE").containsEntry("MARKS", "EDUCATION").containsEntry("BANK_ACCOUNT", "DBT");
        assertThat(draft.requester()).isEqualTo("EDUCATION");
        assertThat(draft.consentPurpose()).isEqualTo("SCHOLARSHIP_ELIGIBILITY");
        Planned fresh = plan("education", env(), null);
        assertThat(fresh.plan().journeys().get(0).exists()).isFalse();
    }

    // --- the steps only an operator can do ------------------------------------------------------------------

    @Test
    void every_authenticated_source_gets_a_provision_secret_step_naming_the_key_and_the_parameters_never_a_value() throws IOException {
        List<PendingStep> steps = plan("dbt", env(), null).plan().pendingSteps();
        PendingStep secret = steps.stream().filter(s -> s.kind().equals("PROVISION_SECRET")).findFirst().orElseThrow();
        assertThat(secret.subject()).isEqualTo("dbt-rest");
        assertThat(secret.data()).containsEntry("secretKey", "source-dbt-rest-credential");
        assertThat(secret.data().get("parameters")).contains("client_id").contains("client_secret");
        assertThat(steps.toString()).doesNotContain("dbt-dev-secret-change-me");
    }

    @Test
    void sftp_and_jdbc_sources_get_a_configure_step_because_their_host_and_pin_are_operator_set() throws IOException {
        List<PendingStep> steps = plan("agriculture", env(), null).plan().pendingSteps();
        PendingStep jdbc = steps.stream().filter(s -> s.kind().equals("CONFIGURE_JDBC")).findFirst().orElseThrow();
        assertThat(jdbc.subject()).isEqualTo("agriculture-jdbc");
        assertThat(jdbc.data()).containsEntry("property", "samanvay.sources.jdbc.sources.agriculture-jdbc.jdbc-url");
        assertThat(jdbc.data().get("host")).isEqualTo("localhost:5434");
        PendingStep sftp = steps.stream().filter(s -> s.kind().equals("CONFIGURE_SFTP")).findFirst().orElseThrow();
        assertThat(sftp.data()).containsEntry("property", "samanvay.sources.sftp.sources.agriculture-sftp");
        assertThat(sftp.data().get("hostKeyFingerprint")).isEqualTo("(not published: capture it from the server and pin it)");
    }

    @Test
    void a_department_that_offers_journeys_gets_a_step_to_issue_its_portal_a_caller_credential() throws IOException {
        List<PendingStep> steps = plan("revenue", env(), null).plan().pendingSteps();
        PendingStep caller = steps.stream().filter(s -> s.kind().equals("ISSUE_CALLER_CREDENTIAL")).findFirst().orElseThrow();
        assertThat(caller.subject()).isEqualTo("REVENUE");
        assertThat(caller.data()).containsEntry("clientId", "dept-revenue").containsEntry("department", "REVENUE")
                .containsEntry("allowedClientsProperty", "samanvay.security.staff.allowed-clients");
        assertThat(caller.data().get("scopes")).contains("source:revenue-rest").contains("source:revenue-sftp");
        assertThat(caller.detail()).contains("department portal").contains("never");
        assertThat(caller.toString()).doesNotContain("secret=");
    }

    @Test
    void a_department_that_offers_no_journeys_needs_no_caller_credential() throws IOException {
        String json = "{\"manifestVersion\":2,\"department\":{\"code\":\"X\",\"name\":\"X\",\"description\":\"d\"},\"documents\":[" + doc("MARKS", "API_KEY", "k1") + "],\"journeys\":[]}";
        Planned p = new ManifestOnboardingPlanner().plan(CatalogServices.parseManifest(json), "https://x.example.gov", null, env());
        assertThat(p.plan().pendingSteps()).noneMatch(s -> s.kind().equals("ISSUE_CALLER_CREDENTIAL"));
    }

    @Test
    void rest_and_soap_sources_must_be_https() throws IOException {
        assertThat(plan("education", env(), null).plan().pendingSteps()).anyMatch(s -> s.kind().equals("CONFIGURE_HTTPS") && s.subject().equals("education-soap"));
    }

    // --- digest and department state ------------------------------------------------------------------------

    @Test
    void the_digest_is_stable_for_the_same_manifest_and_changes_when_it_changes() throws IOException {
        DepartmentManifest m = manifest("dbt");
        assertThat(ManifestOnboardingPlanner.digest(m)).isEqualTo(ManifestOnboardingPlanner.digest(manifest("dbt"))).hasSize(64);
        assertThat(ManifestOnboardingPlanner.digest(m)).isNotEqualTo(ManifestOnboardingPlanner.digest(manifest("revenue")));
        String altered = JSON.writeValueAsString(m).replace("accountRef", "accountNo");
        assertThat(ManifestOnboardingPlanner.digest(CatalogServices.parseManifest(altered))).isNotEqualTo(ManifestOnboardingPlanner.digest(m));
    }

    @Test
    void a_known_department_whose_manifest_digest_differs_is_flagged_as_changed() throws IOException {
        DepartmentManifest m = manifest("dbt");
        String digest = ManifestOnboardingPlanner.digest(m);
        Env same = new Env(true, digest, c -> Optional.ofNullable(CENTRAL.get(c)), c -> Optional.empty(), Set.of());
        Env stale = new Env(true, "0".repeat(64), c -> Optional.ofNullable(CENTRAL.get(c)), c -> Optional.empty(), Set.of());
        OnboardingPlan unchanged = new ManifestOnboardingPlanner().plan(m, "https://dbt.example.gov", null, same).plan();
        OnboardingPlan changed = new ManifestOnboardingPlanner().plan(m, "https://dbt.example.gov", null, stale).plan();
        assertThat(unchanged.departmentExists()).isTrue();
        assertThat(unchanged.changedSinceOnboarding()).isFalse();
        assertThat(changed.changedSinceOnboarding()).isTrue();
        assertThat(plan("dbt", env(), null).plan().changedSinceOnboarding()).isFalse(); // never onboarded: nothing to have changed
    }

    @Test
    void a_department_that_exists_but_was_never_onboarded_from_a_manifest_is_told_apart_from_one_that_was() throws IOException {
        DepartmentManifest m = manifest("dbt");
        Env seededOnly = new Env(true, null, c -> Optional.ofNullable(CENTRAL.get(c)), c -> Optional.empty(), Set.of());
        Env onboarded = new Env(true, ManifestOnboardingPlanner.digest(m), c -> Optional.ofNullable(CENTRAL.get(c)), c -> Optional.empty(), Set.of());
        OnboardingPlan seeded = new ManifestOnboardingPlanner().plan(m, "https://dbt.example.gov", null, seededOnly).plan();
        assertThat(seeded.departmentExists()).isTrue();
        assertThat(seeded.onboardedFromManifest()).isFalse();
        assertThat(new ManifestOnboardingPlanner().plan(m, "https://dbt.example.gov", null, onboarded).plan().onboardedFromManifest()).isTrue();
        assertThat(plan("dbt", env(), null).plan().onboardedFromManifest()).isFalse();
    }

    // --- the identity block must live on the department's own host ---------------------------------------------

    @Test
    void identity_urls_on_the_manifests_own_host_are_accepted() throws IOException {
        assertThat(ManifestOnboardingPlanner.identityHostProblem(manifest("revenue"), "http://localhost:8091")).isEmpty();
        assertThat(ManifestOnboardingPlanner.identityHostProblem(manifest("revenue"), "http://LOCALHOST:9999/")).isEmpty();
    }

    @Test
    void a_login_or_key_url_on_another_host_is_refused_with_a_message_naming_it() throws IOException {
        var problem = ManifestOnboardingPlanner.identityHostProblem(manifest("revenue"), "https://revenue.example.gov");
        assertThat(problem).isPresent();
        assertThat(problem.get()).contains("localhost").contains("revenue.example.gov");
    }

    @Test
    void keys_served_from_an_attackers_host_are_refused_even_when_the_login_page_is_on_the_departments_host() throws IOException {
        String json = new String(ManifestOnboardingPlannerTest.class.getResourceAsStream("/manifests/revenue.json").readAllBytes(), StandardCharsets.UTF_8)
                .replace("http://localhost:8091/.well-known/jwks.json", "https://evil.example.net/jwks.json");
        var problem = ManifestOnboardingPlanner.identityHostProblem(CatalogServices.parseManifest(json), "http://localhost:8091");
        assertThat(problem).isPresent();
        assertThat(problem.get()).contains("evil.example.net");
    }

    @Test
    void a_department_without_an_identity_block_has_nothing_to_check() {
        String json = "{\"manifestVersion\":2,\"department\":{\"code\":\"X\",\"name\":\"X\",\"description\":\"d\"},\"documents\":[],\"journeys\":[]}";
        assertThat(ManifestOnboardingPlanner.identityHostProblem(CatalogServices.parseManifest(json), "https://x.example.gov")).isEmpty();
    }

    // --- journeys carry the address of the department's own portal ---------------------------------------------

    static String withPortal(String portalUrl) {
        return "{\"manifestVersion\":2,\"department\":{\"code\":\"X\",\"name\":\"X\",\"description\":\"d\"},\"documents\":[],\"journeys\":["
                + "{\"code\":\"J1\",\"name\":\"Journey\",\"description\":\"d\",\"referencePrefix\":\"JJ\",\"slaHours\":24,"
                + "\"consentPurpose\":\"P\",\"requester\":\"X\",\"requiredCategories\":[],\"portalUrl\":" + (portalUrl == null ? "null" : "\"" + portalUrl + "\"") + "}]}";
    }

    @Test
    void a_journeys_portal_address_on_the_manifests_own_host_is_kept_in_the_plan() {
        var m = CatalogServices.parseManifest(withPortal("https://x.example.gov/portal/"));
        assertThat(ManifestOnboardingPlanner.identityHostProblem(m, "https://x.example.gov")).isEmpty();
        var plan = new ManifestOnboardingPlanner().plan(m, "https://x.example.gov", null, env()).plan();
        assertThat(plan.journeys()).singleElement().satisfies(j -> assertThat(j.portalUrl()).isEqualTo("https://x.example.gov/portal/"));
    }

    @Test
    void a_journeys_portal_address_on_another_host_is_refused() {
        var problem = ManifestOnboardingPlanner.identityHostProblem(CatalogServices.parseManifest(withPortal("https://evil.example.net/portal/")), "https://x.example.gov");
        assertThat(problem).isPresent();
        assertThat(problem.get()).contains("evil.example.net");
    }

    @Test
    void a_portal_address_that_is_not_http_or_https_is_refused() {
        assertThat(ManifestOnboardingPlanner.identityHostProblem(CatalogServices.parseManifest(withPortal("javascript:alert(1)")), "https://x.example.gov")).isPresent();
    }

    @Test
    void a_journey_without_a_portal_address_is_still_fine() {
        assertThat(ManifestOnboardingPlanner.identityHostProblem(CatalogServices.parseManifest(withPortal(null)), "https://x.example.gov")).isEmpty();
    }

    // --- grouping -------------------------------------------------------------------------------------------

    @Test
    void documents_on_the_same_protocol_and_host_but_different_auth_get_separate_sources() throws IOException {
        String json = "{\"manifestVersion\":2,\"department\":{\"code\":\"X\",\"name\":\"X\",\"description\":\"d\"},\"documents\":["
                + doc("MARKS", "API_KEY", "k1") + "," + doc("BANK_ACCOUNT", "API_KEY", "k2") + "],\"journeys\":[]}";
        Planned p = new ManifestOnboardingPlanner().plan(CatalogServices.parseManifest(json), "https://x.example.gov", null, env());
        assertThat(p.sources()).extracting(SourceSpec::code).containsExactlyInAnyOrder("x-rest", "x-rest-2");
    }

    static String doc(String category, String scheme, String param) {
        return "{\"category\":\"" + category + "\",\"title\":\"t\",\"protocol\":\"REST\",\"method\":\"GET\",\"path\":\"/" + category.toLowerCase()
                + "\",\"inputs\":[{\"name\":\"id\",\"in\":\"query\",\"required\":true,\"description\":\"\"}],\"fields\":[{\"name\":\"percentage\",\"type\":\"number\",\"sensitive\":false}],"
                + "\"auth\":{\"scheme\":\"" + scheme + "\",\"parameters\":[{\"name\":\"" + param + "\",\"in\":\"header\",\"secret\":true}]}}";
    }
}
