package com.samanvay.identity.api;

import java.time.LocalDate;
import java.util.UUID;

public record Profile(
        UUID citizenId,
        String nameLatin,
        String nameDevanagari,
        String familyName,
        String fatherName,
        LocalDate dob,
        String dobPrecision) {}
