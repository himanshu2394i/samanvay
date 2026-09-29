package com.samanvay.payments.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Issues disbursements. Money movement is out of scope (HLD 1.5: DBT is mocked): a disbursement
 * is the record that an approved application is owed payment, split into instalments that each
 * have an id. Downstream checks (a consent with frequency {@code ONCE_PER_PAYMENT}) name an
 * instalment by that id.
 */
public interface DisbursementService {

    /**
     * Issues the disbursement for an approved application. Idempotent: an application has at
     * most one, so a repeat call (a redelivered approval event, a retry) returns the existing
     * disbursement and writes nothing, no second audit entry included.
     */
    Disbursement disburse(UUID applicationId, UUID citizenId, String journeyCode);

    Optional<Disbursement> forApplication(UUID applicationId);

    /** The instalment behind a payment id, with the disbursement it belongs to, if any. */
    Optional<Instalment> instalment(UUID instalmentId);
}
