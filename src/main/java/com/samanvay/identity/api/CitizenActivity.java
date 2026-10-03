package com.samanvay.identity.api;

import java.util.UUID;

/**
 * Lets other modules say whether a citizen has already done anything that would make folding their record into another one
 * unsafe (a consent, a journey). Identity only merges a citizen when no implementation reports activity.
 */
public interface CitizenActivity {

    boolean hasActivity(UUID citizenId);
}
