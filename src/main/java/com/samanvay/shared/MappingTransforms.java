package com.samanvay.shared;

import java.util.Set;

/** Closed transform set — catalog validates, connector executes, same names. */
public final class MappingTransforms {

    public static final Set<String> NAMES = Set.of(
            "trim", "upper", "lower", "date_parse", "coalesce", "split_name", "lookup", "mask");

    private MappingTransforms() {}
}
