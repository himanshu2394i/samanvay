package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.shared.InvalidRequestException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** When may Samanvay rely on a manifest: signed by the pinned key, or (only where allowed) first seen unsigned. */
class ManifestTrustTest {

    static final Optional<String> KEY_A = Optional.of("keyA");
    static final Optional<String> UNSIGNED = Optional.empty();

    // --- plan: can the admin even review this? ---------------------------------------------------------------

    @Test
    void an_unsigned_manifest_is_refused_unless_unsigned_manifests_are_allowed() {
        assertThatThrownBy(() -> ManifestTrust.checkPlan(UNSIGNED, null, false)).isInstanceOf(InvalidRequestException.class).hasMessageContaining("not signed");
        assertThatCode(() -> ManifestTrust.checkPlan(UNSIGNED, null, true)).doesNotThrowAnyException();
    }

    @Test
    void a_department_that_was_pinned_can_never_go_back_to_unsigned_even_where_unsigned_is_allowed() {
        assertThatThrownBy(() -> ManifestTrust.checkPlan(UNSIGNED, "keyA", true)).isInstanceOf(InvalidRequestException.class).hasMessageContaining("signed before");
    }

    @Test
    void a_signed_manifest_can_be_reviewed_whether_or_not_the_key_is_pinned_yet() {
        assertThatCode(() -> ManifestTrust.checkPlan(KEY_A, null, false)).doesNotThrowAnyException();
        assertThatCode(() -> ManifestTrust.checkPlan(KEY_A, "keyA", false)).doesNotThrowAnyException();
        assertThatCode(() -> ManifestTrust.checkPlan(KEY_A, "other", false)).doesNotThrowAnyException(); // reviewable; onboarding needs approval
    }

    // --- onboard: has the admin approved THIS key? -----------------------------------------------------------

    @Test
    void a_first_seen_key_needs_the_admins_approval_of_exactly_that_thumbprint() {
        assertThatThrownBy(() -> ManifestTrust.checkOnboard(KEY_A, null, null)).isInstanceOf(InvalidRequestException.class).hasMessageContaining("keyA");
        assertThatThrownBy(() -> ManifestTrust.checkOnboard(KEY_A, null, "keyB")).isInstanceOf(InvalidRequestException.class);
        assertThatCode(() -> ManifestTrust.checkOnboard(KEY_A, null, "keyA")).doesNotThrowAnyException();
    }

    @Test
    void the_pinned_key_needs_no_new_approval() {
        assertThatCode(() -> ManifestTrust.checkOnboard(KEY_A, "keyA", null)).doesNotThrowAnyException();
    }

    @Test
    void a_changed_key_is_refused_until_the_admin_approves_the_new_thumbprint() {
        assertThatThrownBy(() -> ManifestTrust.checkOnboard(KEY_A, "old", null)).isInstanceOf(InvalidRequestException.class).hasMessageContaining("changed");
        assertThatThrownBy(() -> ManifestTrust.checkOnboard(KEY_A, "old", "old")).isInstanceOf(InvalidRequestException.class);
        assertThatCode(() -> ManifestTrust.checkOnboard(KEY_A, "old", "keyA")).doesNotThrowAnyException();
    }

    @Test
    void an_unsigned_manifest_that_plan_allowed_needs_no_key_approval() {
        assertThatCode(() -> ManifestTrust.checkOnboard(UNSIGNED, null, null)).doesNotThrowAnyException();
    }
}
