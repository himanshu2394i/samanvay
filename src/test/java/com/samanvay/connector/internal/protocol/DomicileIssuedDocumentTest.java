package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.connector.api.IssuedField;
import com.samanvay.connector.api.IssuedRecord;
import org.junit.jupiter.api.Test;

/**
 * The demo issued-records preview shows the domicile certificate the same way
 * it shows income/caste: district and issue date. (Docker-free; the fetch path is covered by ConsentRecordRevocationIT.)
 */
class DomicileIssuedDocumentTest {

    private final IssuedDocumentsImpl docs = new IssuedDocumentsImpl(new MockDepartmentBackend());

    @Test
    void domicilePreviewShowsDistrictAndIssueDateWhenComplete() {
        IssuedRecord record = docs.preview("DOMICILE_CERTIFICATE", "REVENUE", "COMPLETE");
        assertThat(record.title()).isEqualTo("Domicile certificate");
        assertThat(record.fetchStatus()).isEqualTo("RECEIVED");
        assertThat(record.fields())
                .contains(new IssuedField("District", "Pune"), new IssuedField("Issued on", "2024-06-15"));
    }

    @Test
    void domicilePreviewHasNoFieldsUntilComplete() {
        assertThat(docs.preview("DOMICILE_CERTIFICATE", "REVENUE", "WAITING").fields()).isEmpty();
    }
}
