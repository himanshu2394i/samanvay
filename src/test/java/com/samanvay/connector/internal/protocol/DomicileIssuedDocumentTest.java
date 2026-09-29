package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.connector.api.IssuedField;
import com.samanvay.connector.api.IssuedRecord;
import org.junit.jupiter.api.Test;

/**
 * The demo issued-documents sandbox exposes the domicile certificate the same way
 * it exposes income/caste: listed under REVENUE, previewed with district and issue
 * date. (Docker-free; the fetch path is covered by ConsentRecordRevocationIT.)
 */
class DomicileIssuedDocumentTest {

    private final IssuedDocumentsImpl docs = new IssuedDocumentsImpl(new MockDepartmentBackend());

    @Test
    void revenueLockerListsDomicile() {
        assertThat(docs.lockerForDepartment("REVENUE"))
                .anySatisfy(d -> assertThat(d.title()).isEqualTo("Domicile certificate"));
    }

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
