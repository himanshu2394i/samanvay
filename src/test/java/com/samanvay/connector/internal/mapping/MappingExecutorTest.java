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

    static final tools.jackson.databind.JsonNode NULLS = JsonMapper.builder().build().readTree("{\"a\":null,\"nested\":{\"b\":null}}");

    @Test
    void aJsonNullStaysNullInsteadOfBecomingAnEmptyOrTextualValue() {
        var def = new MappingDefinition("m", "c", List.of(new FieldMapping("a", "x", List.of()), new FieldMapping("nested.b", "y", List.of()),
                new FieldMapping("missing", "z", List.of())));

        var out = new MappingExecutor().apply(def, NULLS);

        assertThat(out.get("x").isNull()).isTrue();
        assertThat(out.get("y").isNull()).isTrue();
        assertThat(out.get("z").isNull()).isTrue();
    }

    @Test
    void transformsOnAnAbsentValueStayAbsentRatherThanFailing() {
        var def = new MappingDefinition("m", "c", List.of(new FieldMapping("a", "x", List.of(new TransformCall("trim", List.of()),
                new TransformCall("date_parse", List.of("yyyy-MM-dd")), new TransformCall("mask", List.of("2"))))));

        assertThat(new MappingExecutor().apply(def, NULLS).get("x").isNull()).isTrue();
    }

    @Test
    void lookupIsNotATransformBecauseThereIsNothingToLookUpIn() {
        assertThat(MappingExecutor.registryNames()).doesNotContain("lookup");
        assertThat(com.samanvay.shared.MappingTransforms.NAMES).doesNotContain("lookup");
        var def = new MappingDefinition("m", "c", List.of(new FieldMapping("a", "x", List.of(new TransformCall("lookup", List.of("table"))))));
        assertThatThrownBy(() -> new MappingExecutor().apply(def, NULLS)).isInstanceOf(UnknownTransformException.class);
    }
}
