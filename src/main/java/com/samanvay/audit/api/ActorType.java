package com.samanvay.audit.api;

public enum ActorType {
    CITIZEN,
    OFFICER,
    SYSTEM,
    ADMIN,
    /** A department integration's client-credentials token; actorId is the client id. */
    DEPARTMENT,
    /** No valid token was presented (refused API calls only). */
    ANONYMOUS
}
