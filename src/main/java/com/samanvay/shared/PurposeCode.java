package com.samanvay.shared;

public record PurposeCode(String code) {

    public static final PurposeCode SCHOLARSHIP_ELIGIBILITY = new PurposeCode("SCHOLARSHIP_ELIGIBILITY");
    public static final PurposeCode ADMIN_DEDUP = new PurposeCode("ADMIN_DEDUP");

    public static PurposeCode of(String code) {
        return new PurposeCode(code);
    }
}
