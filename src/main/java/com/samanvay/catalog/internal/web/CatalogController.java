package com.samanvay.catalog.internal.web;

import com.samanvay.catalog.api.CatalogOnboarding;
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
    private final CatalogOnboarding onboarding;
    private final SpecImport importer;
    private final SchemaCatalog schemas;

    CatalogController(
            DepartmentCatalog departments,
            JourneyCatalog journeys,
            CatalogOnboarding onboarding,
            SpecImport importer,
            SchemaCatalog schemas) {
        this.departments = departments;
        this.journeys = journeys;
        this.onboarding = onboarding;
        this.importer = importer;
        this.schemas = schemas;
    }

    @GetMapping("/departments")
    List<Department> departments() {
        return departments.all();
    }

    @GetMapping("/journeys/{code}")
    JourneyDefinition journey(@PathVariable String code) {
        return journeys.byCode(code);
    }

    @PostMapping("/departments")
    Department registerDepartment(@RequestBody DepartmentDraft draft) {
        return onboarding.registerDepartment(draft);
    }

    @PostMapping("/data-sources")
    DataSourceDefinition registerDataSource(@RequestBody DataSourceDraft draft) {
        return onboarding.registerDataSource(draft);
    }

    @PostMapping("/connectors")
    ConnectorDefinition createDraft(@RequestBody ConnectorDraft draft) {
        return onboarding.createDraft(draft);
    }

    @PostMapping("/mappings")
    MappingDefinition saveMapping(@RequestBody MappingDraft draft) {
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

    @PostMapping("/import/openapi")
    ImportPreview importOpenApi(@RequestBody ImportBody body) {
        return importer.preview(body.spec(), body.operationId(), body.targetSchemaRef());
    }

    record ImportBody(String spec, String operationId, String targetSchemaRef) {}
}
