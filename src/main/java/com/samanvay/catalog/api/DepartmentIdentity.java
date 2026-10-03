package com.samanvay.catalog.api;

/**
 * How a citizen proves who they are at a department, from its manifest {@code identity} block (see
 * docs/contracts/login-assertion.md): the type of person ID its login returns, where the login is, where its public
 * keys are, and the issuer name its assertions carry. Public metadata; no secrets.
 */
public record DepartmentIdentity(String personIdType, String loginUrl, String jwksUrl, String assertionIssuer) {}
