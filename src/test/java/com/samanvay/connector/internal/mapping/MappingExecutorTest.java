package com.samanvay.connector.internal.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.catalog.api.FieldMapping;
import com.samanvay.catalog.api.MappingDefinition;
import com.samanvay.catalog.api.TransformCall;
import com.samanvay.connector.api.UnknownTransformException;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MappingExecutorTest {

    @Test
    void appliesKnownTransformsAndRejectsUnknown() {
        MappingExecutor exec = new MappingExecutor();
        var def = new MappingDefinition(
                "m",
                "c",
                List.of(new FieldMapping("name", "name", List.of(new TransformCall("upper", List.of())))));
        var src = JsonMapper.builder().build().readTree("{\"name\":\"ramesh\"}");
        assertThat(exec.apply(def, src).get("name").asString()).isEqualTo("RAMESH");
        var bad = new MappingDefinition(
                "m", "c", List.of(new FieldMapping("name", "name", List.of(new TransformCall("eval", List.of())))));
        assertThatThrownBy(() -> exec.apply(bad, src)).isInstanceOf(UnknownTransformException.class);
    }
}
