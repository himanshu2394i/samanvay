package com.samanvay.catalog.internal.web;

import com.samanvay.catalog.api.Department;
import com.samanvay.catalog.api.DepartmentCatalog;
import com.samanvay.catalog.api.JourneyCatalog;
import com.samanvay.catalog.api.JourneyDefinition;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog")
class CatalogController {

    private final DepartmentCatalog departments;
    private final JourneyCatalog journeys;

    CatalogController(DepartmentCatalog departments, JourneyCatalog journeys) {
        this.departments = departments;
        this.journeys = journeys;
    }

    @GetMapping("/departments")
    List<Department> departments() {
        return departments.all();
    }

    @GetMapping("/journeys/{code}")
    JourneyDefinition journey(@PathVariable String code) {
        return journeys.byCode(code);
    }
}
