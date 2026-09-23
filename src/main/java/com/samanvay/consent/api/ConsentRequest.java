package com.samanvay.consent.api;

import java.util.List;
import java.util.UUID;

public record ConsentRequest(
        UUID id, UUID citizenId, String requesterId, String purposeCode, String purposeText, List<String> categories, String status) {}
