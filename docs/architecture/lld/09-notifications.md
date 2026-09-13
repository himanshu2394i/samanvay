# LLD: `notifications`

| | |
|---|---|
| **HLD charter** | [hld/09-notifications.md](../hld/09-notifications.md) |
| **Migration range** | V160–V179 |
| **Package** | `com.samanvay.notifications` |
| **Depends on** | `audit.api` — and the event types of every module it listens to |

---

## 1. Four open questions closed

**Consent-critical notifications are not unsubscribable.** `ConsentRequested` is the
message that makes consent meaningful — a citizen who opted out of it would never know to
approve or deny a request, silently defeating the whole mechanism. `ConsentRequested`,
`ConsentGranted`, and `ConsentRevoked` always deliver **in-app** at minimum, regardless of
subscription state; a citizen may still additionally subscribe SMS/email on top.

**Templates are classpath resources, not database rows.** A runtime-editable template
rendered with citizen data is a real injection surface — the same reasoning that keeps the
mapping DSL fixed ([HLD §5.2](../HLD.md#52-mapping-dsl--deliberately-not-a-programming-language)).
Templates are logic-less `{{placeholder}}` substitution only, versioned with the code in
git, changed by a PR like any other code change — never by an admin screen at runtime.

**Rendered bodies are retained 90 days, then purged.** They contain citizen data
(HLD §5.9's stored/never-stored table doesn't cover delivery history explicitly, but the
same discipline applies: retain only as long as operationally useful).

**Reviewer alerts are batched hourly, not sent one per candidate.** `identity`'s
resolution scan (§6 of its own LLD) can raise many candidates in one run; one notification
per candidate would train reviewers to ignore the channel. A digest is sent instead.

## 2. Migration: `V160__notifications_init.sql`

```sql
CREATE TABLE notification_subscription (
    id            UUID PRIMARY KEY,
    recipient_id  VARCHAR(100) NOT NULL,
    event_type    VARCHAR(60)  NOT NULL,
    channel       VARCHAR(20)  NOT NULL CHECK (channel IN ('IN_APP','SMS','EMAIL')),
    locale        VARCHAR(10)  NOT NULL DEFAULT 'en',
    enabled       BOOLEAN NOT NULL DEFAULT true,
    UNIQUE (recipient_id, event_type, channel)
);

CREATE TABLE notification_delivery (
    id             UUID PRIMARY KEY,
    recipient_id   VARCHAR(100) NOT NULL,
    event_type     VARCHAR(60)  NOT NULL,
    channel        VARCHAR(20)  NOT NULL,
    -- Derived from the source event's own identity (e.g. consentId +
    -- eventType). This, not a separate "have I seen this event id"
    -- table, is what makes redelivery produce one message.
    dedupe_key     VARCHAR(200) NOT NULL,
    template_ref   VARCHAR(100) NOT NULL,
    rendered_body  TEXT,                 -- NULL after the 90-day purge, §6
    status         VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','SENT','FAILED')),
    attempts       INT NOT NULL DEFAULT 0,
    last_error     VARCHAR(500),
    sent_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (recipient_id, channel, dedupe_key)
);
```

## 3. Template rendering — logic-less, on purpose

```java
package com.samanvay.notifications.internal.channel;

@Component
class TemplateRenderer {

    // {{placeholder}} substitution only - no conditionals, no loops, no
    // method calls. Same philosophy as connector's mapping DSL: a
    // template that cannot execute logic cannot be used to inject any.
    String render(String templateRef, Map<String, String> variables) {
        String template = classpathTemplates.load(templateRef);   // src/main/resources/templates/notifications/{locale}/{ref}.txt
        for (var e : variables.entrySet())
            template = template.replace("{{" + e.getKey() + "}}", htmlEscape(e.getValue()));
        if (template.contains("{{"))
            throw new UnresolvedTemplatePlaceholderException(templateRef);   // fail loud, never send half a message
        return template;
    }
}
```

## 4. Dispatch — mandatory channel, dedup, never on the critical path

```java
package com.samanvay.notifications.internal.service;

@Component
class NotificationDispatcher {

    private static final Set<String> MANDATORY = Set.of("ConsentRequested", "ConsentGranted", "ConsentRevoked");

    @ApplicationModuleListener
    void on(ConsentRequested event) {
        dispatch("ConsentRequested", event.citizenId(), event.requestId().toString(), templateVars(event));
    }
    // ... one @ApplicationModuleListener per event type this module consumes (§7)

    private void dispatch(String eventType, String recipientId, String sourceEventId, Map<String, String> vars) {
        var channels = new HashSet<Channel>();
        if (MANDATORY.contains(eventType)) channels.add(Channel.IN_APP);
        channels.addAll(subscriptions.enabledChannelsFor(recipientId, eventType));

        for (Channel channel : channels) {
            String dedupeKey = eventType + ":" + sourceEventId;
            if (deliveries.existsByDedupeKey(recipientId, channel, dedupeKey)) continue;   // idempotent on redelivery

            var locale = subscriptions.localeFor(recipientId);
            var templateRef = templateRef(eventType, channel, locale);
            var body = renderWithFallback(templateRef, vars, locale);   // falls back to English - see §5
            var delivery = deliveries.insert(recipientId, eventType, channel, dedupeKey, templateRef);

            try {
                channelImpls.get(channel).send(new RenderedMessage(recipientId, body));
                deliveries.markSent(delivery.id());
            } catch (Exception e) {
                // Never rethrown. A failed SMS must never fail the
                // business operation that triggered it - see
                // hld/09-notifications.md §6 and LLD.md §4.3. Recorded
                // for the operational dashboard, not propagated.
                deliveries.markFailed(delivery.id(), e.getMessage());
                events.publishEvent(new DeliveryFailed(delivery.id(), channel, e.getMessage()));
            }
        }
    }
}
```

## 5. Locale fallback

```java
private String renderWithFallback(String templateRef, Map<String, String> vars, String locale) {
    try {
        return templateRenderer.render(templateRef, vars);
    } catch (TemplateNotFoundException e) {
        return templateRenderer.render(templateRefForLocale(templateRef, "en"), vars);   // never drop the message
    }
}
```

## 6. Reviewer digest and retention — two scheduled jobs

```java
package com.samanvay.notifications.internal.service;

@Component
class ScheduledJobs {

    @Scheduled(cron = "0 0 * * * *")   // hourly
    void sendReviewerDigest() {
        var pending = pendingCandidateAlerts.since(lastDigestRun());
        pending.groupByReviewer().forEach((reviewerId, items) ->
            dispatcher.dispatchDirect("CandidateDigest", reviewerId, Map.of("count", String.valueOf(items.size()))));
    }

    @Scheduled(cron = "0 0 3 * * *")   // daily
    void purgeOldBodies() {
        deliveries.clearRenderedBodyOlderThan(Instant.now().minus(Duration.ofDays(90)));
    }
}
```

## 7. Error handling

| Exception (`notifications.api`) | Raised when |
|---|---|
| `UnresolvedTemplatePlaceholderException` | A template still contains `{{...}}` after substitution — a missing variable, caught at render time rather than sending a broken message |
| `TemplateNotFoundException` | Internal only — caught by §5's fallback, never surfaces past this module |

## 8. Events

### Published
`DeliveryFailed` — `{deliveryId, channel, error}`

### Consumed

| From | Events |
|---|---|
| `consent` | `ConsentRequested`, `ConsentGranted`, `ConsentRevoked` |
| `orchestration` | `JourneyStarted`, `StepPendingSource`, `ApplicationStateChanged`, `SlaBreached`, `ManualUploadRequested` |
| `tracking` | `ApplicationReferenceIssued` |
| `identity` | `LinkAsserted`, `CandidateRaised` (batched, §6) |
| `registry` | `PointerUpserted` |
| `connector` | `BatchRowRejected` |

## 9. Tests

| Test | Proves |
|---|---|
| `MandatoryChannelTest` | A citizen with zero subscriptions still receives `ConsentRequested` in-app |
| `DedupOnRedeliveryTest` | The same source event delivered twice produces one `notification_delivery` row, not two |
| `NeverFailsCallerTest` | A channel implementation throwing an exception does not propagate past `dispatch()` — the triggering business transaction commits regardless |
| `LocaleFallbackTest` | A missing Marathi template falls back to English rather than dropping the message |
| `TemplateInjectionTest` | A variable value containing `{{` or template-like syntax is rendered as literal text, not re-interpreted |
| `ReviewerDigestBatchingTest` | Ten candidates raised within one hour produce one digest notification per reviewer, not ten |
| `RetentionPurgeTest` | A delivery older than 90 days has `rendered_body = NULL` after the purge job runs; the delivery row itself (status, timestamps) remains |
