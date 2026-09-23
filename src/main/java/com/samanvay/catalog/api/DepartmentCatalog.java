package com.samanvay.catalog.api;

import java.util.List;
import java.util.Optional;

public interface DepartmentCatalog {
    Optional<Department> byCode(String code);

    List<Department> all();

    Department register(DepartmentDraft draft);
}
