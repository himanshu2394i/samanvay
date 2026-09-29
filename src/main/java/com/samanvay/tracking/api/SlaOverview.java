package com.samanvay.tracking.api;

import java.time.Instant;
import java.util.List;

/**
 * Read-only SLA view over open applications (status not APPROVED, REJECTED or CLOSED, with an SLA due
 * time): {@code sla_due_at} compared with {@code asOf}. For the ops dashboards; carries no citizen data.
 */
public interface SlaOverview {

    /** How many of the most overdue / soonest-due open applications {@link Snapshot#watchlist()} lists. */
    int WATCHLIST_SIZE = 10;

    /** Open applications due within this window count as "due soon". */
    java.time.Duration DUE_SOON = java.time.Duration.ofHours(24);

    Snapshot snapshot(Instant asOf);

    /**
     * @param open open applications with an SLA due time
     * @param breached those already past due
     * @param dueSoon those not yet due but due within {@link #DUE_SOON}
     * @param byJourney the same counts per journey code, ordered by code
     * @param watchlist the {@link #WATCHLIST_SIZE} open applications with the earliest due time (overdue first)
     */
    record Snapshot(
            Instant asOf, long open, long breached, long dueSoon, List<JourneySla> byJourney, List<Case> watchlist) {}

    record JourneySla(String journeyCode, long open, long breached, long dueSoon) {}

    /** @param secondsToDue negative when overdue */
    record Case(String referenceNo, String journeyCode, String status, Instant slaDueAt, long secondsToDue) {}
}
