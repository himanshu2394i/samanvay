package com.samanvay.catalog.api;

/** A governed purpose code (DEPA consent purpose shape: code, refUri, text, category type). */
public record Purpose(String code, String text, String refUri, String categoryType, boolean active) {}
