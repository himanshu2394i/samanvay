package com.samanvay.catalog.internal.web;

import static com.samanvay.shared.InvalidRequestException.requirePresent;
import static com.samanvay.shared.InvalidRequestException.requireText;

import com.samanvay.catalog.api.CatalogDiscovery;
import com.samanvay.catalog.api.CatalogOnboarding;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.DataSourceHealth;
import com.samanvay.catalog.api.DepartmentManifest;
import com.samanvay.catalog.api.ConnectorDefinition;
import com.samanvay.catalog.api.ConnectorDraft;
import com.samanvay.catalog.api.ConnectorTestReport;
import com.samanvay.catalog.api.DataSourceDefinition;
import com.samanvay.catalog.api.DataSourceDraft;
import com.samanvay.catalog.api.Department;
import com.samanvay.catalog.api.DepartmentCatalog;
import com.samanvay.catalog.api.DepartmentDraft;
import com.samanvay.catalog.api.JourneyCatalog;
import com.samanvay.catalog.api.JourneyDefinition;
import com.samanvay.catalog.api.JourneyDraft;
import com.samanvay.catalog.api.JourneyWrite;
import com.samanvay.catalog.api.ImportPreview;
import com.samanvay.catalog.api.ManifestOnboarding;
import com.samanvay.catalog.api.OnboardRequest;
import com.samanvay.catalog.api.OnboardingPlan;
import com.samanvay.catalog.api.OnboardingResult;
import com.samanvay.catalog.api.MappingDefinition;
import com.samanvay.catalog.api.MappingDraft;
import com.samanvay.catalog.api.SchemaAdmin;
import com.samanvay.catalog.api.SchemaCatalog;
import com.samanvay.catalog.api.SchemaDraft;
import com.samanvay.catalog.api.SchemaSummary;
import com.samanvay.catalog.api.SpecImport;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog")
class CatalogController {

    private final DepartmentCatalog departments;
    private final JourneyCatalog journeys;
    private final JourneyWrite journeyWrite;
    private final ConnectorCatalog connectors;
    private final CatalogOnboarding onboarding;
    private final CatalogDiscovery discovery;
    private final SpecImport importer;
    private final SchemaCatalog schemas;
    private final SchemaAdmin schemaAdmin;
    private final ManifestOnboarding manifestOnboarding;

    CatalogController(
            DepartmentCatalog departments,
            JourneyCatalog journeys,
            JourneyWrite journeyWrite,
            ConnectorCatalog connectors,
            CatalogOnboarding onboarding,
            CatalogDiscovery discovery,
            SpecImport importer,
            SchemaCatalog schemas,
            SchemaAdmin schemaAdmin,
            ManifestOnboarding manifestOnboarding) {
        this.manifestOnboarding = manifestOnboarding;
        this.departments = departments;
        this.journeys = journeys;
        this.journeyWrite = journeyWrite;
        this.connectors = connectors;
        this.onboarding = onboarding;
        this.discovery = discovery;
        this.importer = importer;
        this.schemas = schemas;
        this.schemaAdmin = schemaAdmin;
    }

    @GetMapping("/departments")
    List<Department> departments() {
        return departments.all();
    }

    @GetMapping("/journeys")
    List<JourneyDefinition> journeys() {
        return journeys.all();
    }

    @GetMapping("/journeys/{code}")
    JourneyDefinition journey(@PathVariable String code) {
        return journeys.byCode(code);
    }

    @GetMapping("/connectors")
    List<ConnectorDefinition> connectors() {
        return connectors.published();
    }

    @PostMapping("/journeys")
    JourneyDefinition createJourney(@RequestBody JourneyDraft draft) {
        requireText(draft.code(), "code");
        requireText(draft.name(), "name");
        return journeyWrite.createJourney(draft);
    }

    @PostMapping("/journeys/{code}/publish")
    JourneyDefinition publishJourney(@PathVariable String code) {
        return journeyWrite.publishJourney(code);
    }

    @PostMapping("/departments")
    Department registerDepartment(@RequestBody DepartmentDraft draft) {
        requireText(draft.code(), "code");
        requireText(draft.name(), "name");
        return onboarding.registerDepartment(draft);
    }

    @PostMapping("/data-sources")
    DataSourceDefinition registerDataSource(@RequestBody DataSourceDraft draft) {
        return onboarding.registerDataSource(draft);
    }

    @PostMapping("/connectors")
    ConnectorDefinition createDraft(@RequestBody ConnectorDraft draft) {
        requireText(draft.connectorId(), "connectorId");
        requireText(draft.dataSourceCode(), "dataSourceCode");
        requirePresent(draft.category(), "category");
        requireText(draft.capabilitiesJson(), "capabilitiesJson");
        return onboarding.createDraft(draft);
    }

    @PostMapping("/mappings")
    MappingDefinition saveMapping(@RequestBody MappingDraft draft) {
        requireText(draft.ref(), "ref");
        requireText(draft.connectorRef(), "connectorRef");
        requirePresent(draft.rules(), "rules");
        return onboarding.saveMapping(draft);
    }

    @PostMapping("/connectors/{ref}/test")
    ConnectorTestReport test(@PathVariable String ref) {
        return onboarding.test(ref);
    }

    @PostMapping("/connectors/{ref}/publish")
    ConnectorDefinition publish(@PathVariable String ref, @RequestBody ConnectorTestReport report) {
        return onboarding.publish(ref, report);
    }

    @GetMapping("/schemas")
    List<String> schemaRefs() {
        return schemas.refs();
    }

    /** The central schema with each field's type and whether it is required (the admin screen). */
    @GetMapping("/schema-details")
    List<SchemaSummary> schemaDetails() {
        return schemaAdmin.summaries();
    }

    /** Adds a schema (or a new version of one); never changes an existing ref. */
    @PostMapping("/schemas")
    SchemaSummary addSchema(@RequestBody SchemaDraft draft) {
        return schemaAdmin.create(draft);
    }

    @PostMapping("/discover")
    DepartmentManifest discover(@RequestBody DiscoverBody body) {
        requireText(body.baseUrl(), "baseUrl");
        return discovery.discover(body.baseUrl());
    }

    record DiscoverBody(String baseUrl) {}

    /** Reviews what onboarding a department from its manifest would do. Changes nothing. */
    @PostMapping("/onboard/plan")
    OnboardingPlan onboardPlan(@RequestBody DiscoverBody body) {
        requireText(body.baseUrl(), "baseUrl");
        return manifestOnboarding.plan(body.baseUrl());
    }

    /** Onboards what the admin reviewed and ticked (all DRAFT, one transaction). */
    @PostMapping("/onboard")
    OnboardingResult onboard(@RequestBody OnboardRequest body) {
        requireText(body.baseUrl(), "baseUrl");
        requireText(body.manifestDigest(), "manifestDigest");
        return manifestOnboarding.onboard(body);
    }

    @GetMapping("/data-sources")
    List<DataSourceHealth> dataSources() {
        return discovery.listDataSources();
    }

    @PostMapping("/data-sources/{code}/probe")
    DataSourceHealth probe(@PathVariable String code) {
        return discovery.probe(code);
    }

    @PostMapping("/import/openapi")
    ImportPreview importOpenApi(@RequestBody ImportBody body) {
        requireText(body.spec(), "spec");
        requireText(body.operationId(), "operationId");
        requireText(body.targetSchemaRef(), "targetSchemaRef");
        return importer.preview(body.spec(), body.operationId(), body.targetSchemaRef());
    }

    record ImportBody(String spec, String operationId, String targetSchemaRef) {}
}
