package com.samanvay.orchestration.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.SamanvayApplication;
import com.samanvay.connector.api.BankCheckAdapter.AccountStatus;
import com.samanvay.connector.api.BankCheckAdapter.BankCheckAnswer;
import com.samanvay.connector.api.BankCheckAdapter.NameMatch;
import com.samanvay.connector.api.BankCheckReview;
import com.samanvay.shared.InvalidRequestException;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = SamanvayApplication.class)
class BankReviewWorkflowIT extends PostgresIntegrationTest {

    @Autowired BankReviewService reviews;
    @Autowired JdbcTemplate jdbc;

    static final String OFFICER = "officer-test-1";

    private static byte[] pdf(int size) {
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) 'x');
        b[0] = 0x25; b[1] = 0x50; b[2] = 0x44; b[3] = 0x46; // %PDF
        return b;
    }

    private UUID openPartialReview() {
        UUID citizen = UUID.randomUUID();
        BankCheckReview review = BankCheckReview.of(new BankCheckAnswer(AccountStatus.VALID, NameMatch.PARTIAL));
        return reviews.open("APP-" + citizen.toString().substring(0, 8), citizen, review, "XXXXXX1234", "name-match-v1");
    }

    private String path(UUID id) {
        return jdbc.queryForObject("SELECT document_path FROM orchestration_bank_review WHERE id = ?", String.class, id);
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT status, document_hash, document_path, decided_by FROM orchestration_bank_review WHERE id = ?", id);
    }

    private int auditCount(UUID id, String action) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_entry WHERE action = ? AND meta->>'reviewId' = ?",
                Integer.class, action, id.toString());
    }

    @Test
    void openReviewShowsMaskedAccountAndReasonButNoHolderName() {
        UUID id = openPartialReview();
        BankReviewView view = reviews.openReviews().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
        assertThat(view.accountMasked()).isEqualTo("XXXXXX1234");
        assertThat(view.reason()).isEqualTo("NAME_PARTIAL");
        assertThat(view.reasonText()).isEqualTo("Only part of the name matched");
        assertThat(view.matcherVersion()).isEqualTo("name-match-v1");
        assertThat(view.status()).isEqualTo("PENDING_OFFICER");
        assertThat(view.hasDocument()).isFalse();
        assertThat(auditCount(id, "BANK_REVIEW_OPENED")).isEqualTo(1);
    }

    @Test
    void aValidPassbookIsStoredAndHashed_aBadOneIsRefused() {
        UUID id = openPartialReview();

        reviews.attachPassbook(id, pdf(20 * 1024), OFFICER);
        Map<String, Object> after = row(id);
        assertThat(after.get("document_hash")).asString().hasSize(64); // sha-256 hex
        assertThat(Files.exists(Path.of((String) after.get("document_path")))).isTrue();
        assertThat(auditCount(id, "BANK_REVIEW_PASSBOOK_UPLOADED")).isEqualTo(1);

        // A non-PDF/JPEG/PNG (plain text) is refused; the stored file is unchanged.
        byte[] bad = new byte[20 * 1024];
        Arrays.fill(bad, (byte) 'z');
        assertThatThrownBy(() -> reviews.attachPassbook(id, bad, OFFICER)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void approvingDeletesTheFileButKeepsTheHashAndDecision() {
        UUID id = openPartialReview();
        reviews.attachPassbook(id, pdf(20 * 1024), OFFICER);
        String file = path(id);
        assertThat(Files.exists(Path.of(file))).isTrue();

        reviews.approve(id, "Passbook confirms the account holder", OFFICER);

        Map<String, Object> decided = row(id);
        assertThat(decided.get("status")).isEqualTo("APPROVED");
        assertThat(decided.get("decided_by")).isEqualTo(OFFICER);
        assertThat(decided.get("document_path")).as("file path cleared").isNull();
        assertThat(decided.get("document_hash")).as("hash kept").asString().hasSize(64);
        assertThat(Files.exists(Path.of(file))).as("the file itself is deleted").isFalse();
        assertThat(auditCount(id, "BANK_REVIEW_APPROVED")).isEqualTo(1);
        // A decided review is no longer open and cannot be acted on again.
        assertThat(reviews.openReviews().stream().noneMatch(v -> v.id().equals(id))).isTrue();
        assertThatThrownBy(() -> reviews.reject(id, "x", OFFICER)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void retentionPurgeDeletesTheFileAfterTheWindowButKeepsTheHash() {
        UUID id = openPartialReview();
        reviews.attachPassbook(id, pdf(20 * 1024), OFFICER);
        String file = path(id);
        // Backdate the upload beyond the 30-day window.
        jdbc.update("UPDATE orchestration_bank_review SET document_uploaded_at = now() - interval '40 days' WHERE id = ?", id);

        int purged = reviews.purgeExpiredDocuments();

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(Files.exists(Path.of(file))).isFalse();
        Map<String, Object> after = row(id);
        assertThat(after.get("document_path")).isNull();
        assertThat(after.get("document_hash")).asString().hasSize(64);
        assertThat(auditCount(id, "BANK_REVIEW_DOCUMENT_PURGED")).isEqualTo(1);
    }
}
