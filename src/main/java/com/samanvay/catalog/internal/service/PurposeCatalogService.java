package com.samanvay.catalog.internal.service;

import com.samanvay.catalog.api.Purpose;
import com.samanvay.catalog.api.PurposeCatalog;
import com.samanvay.catalog.internal.repository.PurposeRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
class PurposeCatalogService implements PurposeCatalog {

    private final PurposeRepository purposes;

    PurposeCatalogService(PurposeRepository purposes) {
        this.purposes = purposes;
    }

    @Override
    public Optional<Purpose> byCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return purposes.findById(code).map(p -> new Purpose(
                p.getCode(),
                p.getText(),
                p.getRefUri(),
                p.getCategoryType(),
                "ACTIVE".equals(p.getStatus()),
                p.getRequesterDepartment(),
                list(p.getDataCategories()),
                list(p.getDataTypes()),
                Purpose.RequesterRule.valueOf(p.getRequesterRule()),
                p.getMaxDurationDays(),
                p.getDurationRule(),
                Purpose.Frequency.fromCode(p.getFrequency()),
                p.getLabelEn(),
                p.getLabelMr(),
                Purpose.LabelStatus.valueOf(p.getLabelEnStatus()),
                Purpose.LabelStatus.valueOf(p.getLabelMrStatus()),
                p.isSeparateOptIn()));
    }

    private static List<String> list(String[] values) {
        return values == null ? List.of() : List.of(values);
    }
}
