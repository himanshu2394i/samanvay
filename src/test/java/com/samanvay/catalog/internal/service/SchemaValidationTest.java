package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.catalog.internal.domain.SchemaEntity;
import com.samanvay.catalog.internal.repository.SchemaRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** A central schema checks required fields; one that lists none must still refuse an empty answer, or a missing record would pass as valid. */
class SchemaValidationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private CatalogServices catalog(String definition) {
        SchemaRepository schemas = mock(SchemaRepository.class);
        when(schemas.findById("S@1")).thenReturn(Optional.of(SchemaEntity.of("S@1", "S", 1, definition)));
        return new CatalogServices(null, null, null, null, null, schemas, null, ev -> {});
    }

    @Test
    void a_schema_with_no_required_list_rejects_an_empty_or_all_null_document() {
        CatalogServices c = catalog("{\"type\":\"object\",\"properties\":{\"crop\":{\"type\":\"string\"}}}");

        assertThat(c.validate("S@1", JSON.readTree("{}")).valid()).isFalse();
        assertThat(c.validate("S@1", JSON.readTree("{\"crop\":null}")).valid()).isFalse();
        assertThat(c.validate("S@1", JSON.readTree("{\"crop\":\"wheat\"}")).valid()).isTrue();
    }

    @Test
    void a_schema_with_an_empty_required_list_is_treated_the_same() {
        CatalogServices c = catalog("{\"type\":\"object\",\"required\":[]}");

        assertThat(c.validate("S@1", JSON.readTree("{}")).valid()).isFalse();
        assertThat(c.validate("S@1", JSON.readTree("{\"a\":1}")).valid()).isTrue();
    }

    @Test
    void a_schema_with_required_fields_still_names_the_missing_one() {
        CatalogServices c = catalog("{\"type\":\"object\",\"required\":[\"annualIncome\"]}");

        var bad = c.validate("S@1", JSON.readTree("{\"holderName\":\"A\"}"));
        assertThat(bad.valid()).isFalse();
        assertThat(bad.errors()).containsExactly("missing annualIncome");
        assertThat(c.validate("S@1", JSON.readTree("{\"annualIncome\":\"1\"}")).valid()).isTrue();
    }
}
