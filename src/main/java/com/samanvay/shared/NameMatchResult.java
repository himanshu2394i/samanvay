package com.samanvay.shared;

/**
 * The verdict of comparing a name we hold against a name a source returned.
 *
 * <p>Mirrors the bank-check contract's {@code nameMatch} values. It is a machine
 * hint, never a final decision: {@link #PARTIAL}, {@link #NO_MATCH} and
 * {@link #NOT_CHECKED} all route to an officer, they never reject on their own.
 */
public enum NameMatchResult {

    /** Every name part agrees, with no reliance on an initial. */
    MATCH,

    /** Enough agrees to be plausibly the same person, but not a full match. */
    PARTIAL,

    /** At most one name part agrees. */
    NO_MATCH,

    /** The names could not be compared (empty, or written in different scripts). */
    NOT_CHECKED
}
