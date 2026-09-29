package com.samanvay.payments.api;

import java.util.UUID;

/**
 * One instalment of a disbursement. {@code id} is the payment id that payment-scoped consent
 * checks ({@code AccessRequest#paymentId}) refer to.
 */
public record Instalment(UUID id, int sequence, String status) {}
