package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.catalog.api.FieldMapping;
import com.samanvay.catalog.api.MappingDraft;
import com.samanvay.catalog.api.TransformCall;
import com.samanvay.catalog.api.UnknownTransformException;
import com.samanvay.catalog.internal.repository.MappingRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MappingServiceTest {

    @Test
    void unknownTransformRejectedAtSave() {
        MappingCatalogService catalog = new MappingCatalogService(Mockito.mock(MappingRepository.class));
        MappingDraft draft = new MappingDraft(
                "map-x@1",
                "x@1",
                List.of(new FieldMapping("a", "b", List.of(new TransformCall("eval", List.of())))));
        assertThatThrownBy(() -> catalog.save(draft)).isInstanceOf(UnknownTransformException.class);
    }
}
