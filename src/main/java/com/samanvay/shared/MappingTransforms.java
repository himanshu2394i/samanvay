package com.samanvay.shared;

import java.util.Set;

/**
 * Closed transform set: catalog validates, connector executes, same names. There is deliberately no "lookup": it would need a table
 * to look values up in, and nothing provides one.
 */
public final class MappingTransforms {

    public static final Set<String> NAMES = Set.of(
            "trim", "upper", "lower", "date_parse", "coalesce", "split_name", "mask");

    private MappingTransforms() {}
}
