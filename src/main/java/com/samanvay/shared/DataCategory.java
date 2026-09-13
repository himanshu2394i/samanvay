package com.samanvay.shared;

public record DataCategory(String code) {

    public static final DataCategory INCOME_CERTIFICATE = new DataCategory("INCOME_CERTIFICATE");
    public static final DataCategory CASTE_CERTIFICATE = new DataCategory("CASTE_CERTIFICATE");
    public static final DataCategory MARKS = new DataCategory("MARKS");
    public static final DataCategory BANK_ACCOUNT = new DataCategory("BANK_ACCOUNT");

    public static DataCategory of(String code) {
        return new DataCategory(code);
    }
}
