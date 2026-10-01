package com.samanvay.identity.api;

import java.util.UUID;

/** One officer-search hit: enough to pick the right person, nothing more (birth year, not date of birth). */
public record CitizenMatch(UUID citizenId, String nameLatin, String nameDevanagari, Integer birthYear) {}
