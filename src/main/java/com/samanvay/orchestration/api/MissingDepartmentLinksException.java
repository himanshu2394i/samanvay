package com.samanvay.orchestration.api;

import com.samanvay.shared.SamanvayException;
import java.util.List;
import java.util.Map;

public class MissingDepartmentLinksException extends SamanvayException {

    private final List<String> missingDepartments;

    public MissingDepartmentLinksException(String journeyCode, List<String> missingDepartments) {
        super("Missing department links for " + journeyCode + ": " + String.join(", ", missingDepartments));
        this.missingDepartments = List.copyOf(missingDepartments);
    }

    public List<String> missingDepartments() {
        return missingDepartments;
    }

    @Override
    public int status() {
        return 409;
    }

    @Override
    public String problemType() {
        return "orchestration/missing-department-links";
    }

    @Override
    public String reason() {
        return "MISSING_DEPARTMENT_LINKS";
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("missingDepartments", missingDepartments);
    }
}
