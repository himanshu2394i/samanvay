package com.samanvay.catalog.internal.service;

import com.samanvay.catalog.api.ConnectorNotFoundException;
import com.samanvay.catalog.api.MappingCatalog;
import com.samanvay.catalog.api.MappingDefinition;
import com.samanvay.catalog.api.MappingDraft;
import com.samanvay.catalog.api.UnknownTransformException;
import com.samanvay.catalog.internal.domain.MappingEntity;
import com.samanvay.catalog.internal.repository.MappingRepository;
import com.samanvay.shared.MappingTransforms;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MappingCatalogService implements MappingCatalog {

    private final MappingRepository mappings;

    MappingCatalogService(MappingRepository mappings) {
        this.mappings = mappings;
    }

    @Override
    public MappingDefinition byRef(String mappingRef) {
        MappingEntity e = mappings.findById(mappingRef).orElseThrow(() -> new ConnectorNotFoundException(mappingRef));
        return CatalogMappingParser.parse(e.getRef(), e.getConnectorRef(), e.getRules());
    }

    @Override
    @Transactional
    public MappingDefinition save(MappingDraft draft) {
        draft.rules().forEach(rule -> rule.transforms().forEach(t -> {
            if (!MappingTransforms.NAMES.contains(t.fn())) {
                throw new UnknownTransformException(t.fn());
            }
        }));
        MappingEntity e = new MappingEntity();
        e.setRef(draft.ref());
        e.setConnectorRef(draft.connectorRef());
        e.setRules(CatalogMappingParser.toJson(draft.rules()));
        mappings.save(e);
        return new MappingDefinition(draft.ref(), draft.connectorRef(), draft.rules());
    }
}
