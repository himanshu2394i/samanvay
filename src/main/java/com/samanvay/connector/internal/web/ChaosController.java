package com.samanvay.connector.internal.web;

import com.samanvay.connector.api.DepartmentChaos;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("demo")
@RestController
@RequestMapping("/api/connector/chaos")
class ChaosController {

    private final DepartmentChaos chaos;

    ChaosController(DepartmentChaos chaos) {
        this.chaos = chaos;
    }

    @GetMapping("/{dataSourceCode}")
    ChaosView state(@PathVariable String dataSourceCode) {
        return new ChaosView(dataSourceCode, chaos.killed(dataSourceCode));
    }

    @PostMapping("/{dataSourceCode}/kill")
    void kill(@PathVariable String dataSourceCode) {
        chaos.kill(dataSourceCode);
    }

    @PostMapping("/{dataSourceCode}/revive")
    void revive(@PathVariable String dataSourceCode) {
        chaos.revive(dataSourceCode);
    }

    record ChaosView(String dataSourceCode, boolean killed) {}
}
