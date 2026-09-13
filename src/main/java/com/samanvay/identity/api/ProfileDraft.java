package com.samanvay.identity.api;

import java.time.LocalDate;

public record ProfileDraft(
        String nameLatin,
        String nameDevanagari,
        String givenName,
        String familyName,
        String fatherName,
        LocalDate dob,
        String dobPrecision,
        String gender,
        String contactMasked) {}
