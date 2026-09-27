package com.samanvay.audit.api;

public enum ActorType {
    CITIZEN,
    OFFICER,
    /** A staff user acting with the REVIEWER role (e.g. confirming an identity candidate). */
    REVIEWER,
    SYSTEM,
    ADMIN,
    /** A department integration's client-credentials token; actorId is the client id. */
    DEPARTMENT,
    /** No valid token was presented (refused API calls only). */
    ANONYMOUS,
    /** A valid token from a trusted realm that carries no Samanvay role (refused API calls only). */
    AUTHENTICATED
}
