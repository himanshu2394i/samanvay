package com.samanvay.consent.api;

import java.util.List;
import java.util.UUID;

public record ConsentRequested(
        UUID requestId, UUID citizenId, String requesterId, String purposeCode, List<String> categories) {}
