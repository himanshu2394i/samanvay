package com.samanvay.catalog.api;

/** One field of a central schema: its name, JSON type (string, integer, number, boolean) and whether it must be present. */
public record SchemaField(String name, String type, boolean required) {}
