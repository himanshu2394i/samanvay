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
import com.samanvay.catalog.api.MappingDefinition;
import com.samanvay.catalog.api.MappingDraft;
import com.samanvay.catalog.api.SchemaCatalog;
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

    CatalogController(
            DepartmentCatalog departments,
            JourneyCatalog journeys,
            JourneyWrite journeyWrite,
            ConnectorCatalog connectors,
            CatalogOnboarding onboarding,
            CatalogDiscovery discovery,
            SpecImport importer,
            SchemaCatalog schemas) {
        this.departments = departments;
        this.journeys = journeys;
        this.journeyWrite = journeyWrite;
        this.connectors = connectors;
        this.onboarding = onboarding;
        this.discovery = discovery;
        this.importer = importer;
        this.schemas = schemas;
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

    @PostMapping("/discover")
    DepartmentManifest discover(@RequestBody DiscoverBody body) {
        requireText(body.baseUrl(), "baseUrl");
        return discovery.discover(body.baseUrl());
    }

    record DiscoverBody(String baseUrl) {}

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
