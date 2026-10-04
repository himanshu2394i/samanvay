package com.samanvay.catalog.api;

import java.util.List;

/**
 * A department's self-published capability manifest (fetched from
 * {@code {baseUrl}/.well-known/samanvay/manifest}). Non-sensitive metadata only: what documents
 * the department holds and how to fetch them, and the journeys it offers - never any citizen
 * values, secrets or SQL. The middle layer uses this to onboard a department from just its base URL.
 *
 * <p>v2 (docs/FINAL-CHANGES.md sections 10-13) adds, per document, {@code lookup} (a resolve step when the document
 * key is not the person ID), {@code auth} (scheme and the parameters required, never values) and {@code access}
 * (protocol-specific location details), plus a top-level {@code identity} block for the department login. Every
 * new block is optional, so a v1 manifest still parses.
 */
public record DepartmentManifest(
        int manifestVersion,
        Dept department,
        List<Document> documents,
        List<Journey> journeys,
        Identity identity,
        Sample sample) {

    /** A FAKE person the department can answer for, so an admin can run a trial fetch at onboarding. Never a real person. */
    public record Sample(String personId) {}


    public record Dept(String code, String name, String description) {}

    public record Document(
            String category,
            String title,
            String protocol,
            String method,
            String path,
            List<Input> inputs,
            List<Field> fields,
            Lookup lookup,
            Auth auth,
            Access access) {}

    public record Input(String name, String in, boolean required, String description) {}

    public record Field(String name, String type, boolean sensitive) {}

    /** Present on a document when its key is not the person ID: call {@code resolve} first (person ID in, document keys out). */
    public record Lookup(Resolve resolve) {}

    /**
     * How to ask the department which documents a person holds: {@code method} and {@code path} (with {@code {personId}}),
     * the fixed {@code query} parameters, and where in the JSON answer to find the list ({@code listField}), each item's key
     * ({@code keyField}) and the flag marking the newest ({@code latestField}).
     */
    public record Resolve(
            String method,
            String path,
            java.util.Map<String, String> query,
            String listField,
            String keyField,
            String latestField) {}

    /**
     * How to authenticate to the department: the scheme (API_KEY, OAUTH2_CLIENT, WS_SECURITY_USERNAME, PASSWORD,
     * DB_USER, ...) and every parameter it needs. Values are never here; an admin enters them out of band.
     */
    public record Auth(
            String scheme,
            String tokenUrl,
            List<String> scopes,
            String passwordType,
            List<AuthParam> parameters,
            String docs) {}

    /** {@code in} says where it goes: header, query, token-request, soap-header, sftp, db... */
    public record AuthParam(String name, String in, boolean secret) {}

    /** Protocol-specific, non-secret details; only the block that matches the document's protocol is present. */
    public record Access(Soap soap, Sftp sftp, Jdbc jdbc) {}

    public record Soap(String endpoint, String soapAction, String requestTemplate, String contentType) {}

    public record Sftp(
            String host,
            int port,
            String directory,
            String fileNamePattern,
            String format,
            String keyColumn,
            List<String> columns,
            String hostKeyFingerprint) {}

    /** A read-only VIEW and its key column; the department never sends SQL. */
    public record Jdbc(String host, int port, String database, String readOnlyView, String keyColumn, boolean tlsRequired) {}

    /** How a citizen proves they are a person at this department (docs/contracts/login-assertion.md). */
    public record Identity(String personIdType, String loginUrl, String jwksUrl, String assertionIssuer) {}

    public record Journey(
            String code,
            String name,
            String description,
            String referencePrefix,
            int slaHours,
            String consentPurpose,
            String requester,
            List<RequiredCategory> requiredCategories,
            String portalUrl) {}

    /** A document category a journey needs, and the department that provides it. */
    public record RequiredCategory(String category, String department) {}
}
