# Module: `notifications`

| | |
|---|---|
| **Plane** | Data |
| **Owner** | E |
| **Phase** | 2 |
| **Depends on** | `audit` — and the event types of the modules it listens to |
| **Depended on by** | Nothing |
| **HLD context** | [§3.3](../HLD.md#33-modules) |

---

## 1. Purpose

Tell people what happened, on the channel they chose, in the language they chose, without
any other module knowing how that works.

This module is the visible half of the problem statement's *"event-driven notifications"*.
The durable event backbone itself is **infrastructure** — Spring Modulith's Event
Publication Registry — not this module. `notifications` exists because subscriptions,
channel preferences, templates and delivery attempts are genuine domain state that
something must own.

## 2. Responsibilities

### Owns

- Subscriptions: who is notified about which event types
- Channel preferences and locale per recipient
- Message templates and rendering
- Delivery attempts, retries and failure records

### Explicitly not responsible for

- **Event transport.** Modulith's publication registry delivers events; this module
  consumes them.
- **Deciding what is notifiable.** Publishers publish; subscriptions determine reach.
- **Being on any critical path.** A failed notification must never fail a business
  operation.
- **Storing message content beyond retention.** Rendered messages contain citizen data
  and are purged.

## 3. Public interface — `com.samanvay.notifications.api`

```java
public interface SubscriptionService {
    Subscription subscribe(RecipientRef recipient, EventType type, Channel channel, Locale locale);
    void unsubscribe(UUID subscriptionId);
    List<Subscription> forRecipient(RecipientRef recipient);
}

public interface NotificationHistory {
    Page<DeliveryRecord> forRecipient(RecipientRef recipient, Pageable p);
    Page<DeliveryRecord> failures(Pageable p);          // operational view
}

/** Channel implementations. Stubs in the demo; real gateways in production. */
public interface NotificationChannel {
    Channel channel();                                   // IN_APP | SMS | EMAIL
    DeliveryOutcome send(RenderedMessage message);
}
```

`NotificationChannel` is *not* one of the three architectural ports from
[P6](../HLD.md#p6--depend-on-capabilities-only-where-a-swap-is-real) — it is ordinary
polymorphism over three concrete channels, in the same way `ProtocolAdapter` is. It is
listed here because implementers will add channels, not swap an implementation.

## 4. Data owned

| Table | Notes |
|---|---|
| `notification_subscription` | recipient, event type, channel, locale, enabled |
| `notification_template` | event type, channel, locale, template body, version |
| `notification_delivery` | subscription, recipient, channel, template ref, status, attempts, `last_error`, `sent_at` |

Rendered message bodies are retained only for the delivery-history window, then purged —
they contain citizen data.

## 5. Events

### Published

| Event | Consumed by |
|---|---|
| `DeliveryFailed` | Observability; operational dashboard |

### Consumed

The broadest consumer in the system:

| From | Events |
|---|---|
| `consent` | `ConsentRequested`, `ConsentGranted`, `ConsentRevoked` |
| `orchestration` | `JourneyStarted`, `StepPendingSource`, `ApplicationStateChanged`, `SlaBreached`, `ManualUploadRequested` |
| `tracking` | `ApplicationReferenceIssued` |
| `identity` | `LinkAsserted`, `CandidateRaised` (reviewer alert) |
| `registry` | `PointerUpserted` (e.g. "your income certificate has been renewed") |
| `connector` | `BatchRowRejected` (officer alert) |

## 6. Key decisions

### Event publication is infrastructure; this module is domain

Modulith's registry persists a publication in the same transaction as the business write,
delivers it, marks it complete, and retries incomplete publications on restart. That is a
transactional outbox, and it is why **no Kafka is required** — and why no additional
infrastructure can be down during a demo.

`notifications` sits on top of that as an ordinary consumer.

### Never on a critical path

A failed SMS must never fail a scholarship application. Delivery runs in its own
transaction, downstream of the business commit. Failures are recorded and retried, never
propagated.

This is the reason `notifications` is a consumer-only module with nothing depending on it.

### Locale is first-class

Marathi, Hindi and English. A government notification in a language the recipient does not
read is not a notification. Templates are keyed on `(event type, channel, locale)`.

### `ConsentRequested` is the one that matters

The single most important message in the system is *"Department X wants to see your income
certificate for purpose Y — approve or deny."* It is what makes consent real rather than a
checkbox at registration. It gets the most design attention and the clearest wording.

### Consent notifications are not marketing

Every notification about data access carries: who, what, why, and a link to revoke. That
turns the notification stream into an ambient privacy dashboard, which is far more
convincing than a settings page nobody opens.

## 7. Failure modes

| Failure | Behaviour |
|---|---|
| Channel gateway unavailable | Retry with backoff; record attempts; never affects the business operation |
| Template missing for a locale | Fall back to English, log a defect. Never drop the notification |
| Recipient has no channel configured | Deliver in-app; no error |
| Duplicate event delivery | Idempotent on (event id, subscription) — a citizen must not receive the same alert twice |
| Retry budget exhausted | `DeliveryFailed`; visible on the operational dashboard |

## 8. Acceptance criteria

- [ ] A failed notification never rolls back or fails a business operation
- [ ] Duplicate event delivery produces one message, not two
- [ ] Templates render in Marathi, Hindi and English
- [ ] A missing locale template falls back rather than dropping the message
- [ ] Every data-access notification names requester, category, purpose, and links to revoke
- [ ] Delivery failures are visible operationally without inspecting logs
- [ ] Nothing in the system depends on this module (Modulith verifies)

## 9. Open questions for LLD

- Whether subscriptions are opt-in or opt-out per event type. Consent-related events are
  arguably mandatory and not unsubscribable — that is a policy decision with a legal
  flavour, and it should be made deliberately.
- Template engine choice, and whether templates are catalog rows (editable at runtime) or
  resources (versioned with the code). Runtime-editable templates are an injection surface.
- Retention window for rendered message bodies.
- Whether officer/reviewer alerts belong here or in an operational channel; batching
  reviewer alerts matters once the identity queue has real volume.
