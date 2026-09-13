package com.samanvay.notifications.internal.service;

import com.samanvay.connector.api.BatchRowRejected;
import com.samanvay.consent.api.ConsentGranted;
import com.samanvay.consent.api.ConsentRequested;
import com.samanvay.consent.api.ConsentRevoked;
import com.samanvay.identity.api.CandidateRaised;
import com.samanvay.identity.api.LinkAsserted;
import com.samanvay.notifications.api.Channel;
import com.samanvay.notifications.api.DeliveryFailed;
import com.samanvay.notifications.api.NotificationChannel;
import com.samanvay.notifications.api.RenderedMessage;
import com.samanvay.notifications.api.TemplateNotFoundException;
import com.samanvay.notifications.internal.channel.TemplateRenderer;
import com.samanvay.notifications.internal.domain.DeliveryEntity;
import com.samanvay.notifications.internal.domain.SubscriptionEntity;
import com.samanvay.notifications.internal.repository.DeliveryRepository;
import com.samanvay.notifications.internal.repository.SubscriptionRepository;
import com.samanvay.orchestration.api.ApplicationStateChanged;
import com.samanvay.orchestration.api.JourneyStarted;
import com.samanvay.orchestration.api.ManualUploadRequested;
import com.samanvay.orchestration.api.SlaBreached;
import com.samanvay.orchestration.api.StepPendingSource;
import com.samanvay.registry.api.PointerUpserted;
import com.samanvay.tracking.api.ApplicationReferenceIssued;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class NotificationDispatcher {

    private static final Set<String> MANDATORY = Set.of("ConsentRequested", "ConsentGranted", "ConsentRevoked");

    private final SubscriptionRepository subscriptions;
    private final DeliveryRepository deliveries;
    private final TemplateRenderer renderer;
    private final Map<Channel, NotificationChannel> channels = new HashMap<>();
    private final ApplicationEventPublisher events;
    private final PendingCandidateAlerts candidateAlerts;

    NotificationDispatcher(
            SubscriptionRepository subscriptions,
            DeliveryRepository deliveries,
            TemplateRenderer renderer,
            List<NotificationChannel> channelList,
            ApplicationEventPublisher events,
            PendingCandidateAlerts candidateAlerts) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.renderer = renderer;
        channelList.forEach(c -> this.channels.put(c.channel(), c));
        this.events = events;
        this.candidateAlerts = candidateAlerts;
    }

    @ApplicationModuleListener
    void on(ConsentRequested event) {
        dispatch(
                "ConsentRequested",
                event.citizenId().toString(),
                event.requestId().toString(),
                Map.of(
                        "requester",
                        event.requesterId(),
                        "category",
                        String.join(",", event.categories()),
                        "purpose",
                        event.purposeCode(),
                        "revokeUrl",
                        "/consent"));
    }

    @ApplicationModuleListener
    void on(ConsentGranted event) {
        dispatch(
                "ConsentGranted",
                event.citizenId().toString(),
                event.consentId().toString(),
                Map.of("requester", event.requesterId(), "purpose", "granted", "summary", "granted"));
    }

    @ApplicationModuleListener
    void on(ConsentRevoked event) {
        dispatch(
                "ConsentRevoked",
                event.citizenId().toString(),
                event.consentId() + ":" + event.version(),
                Map.of("requester", "system", "summary", "revoked"));
    }

    @ApplicationModuleListener
    void on(JourneyStarted event) {
        dispatch(
                "JourneyStarted",
                event.citizenId().toString(),
                event.instanceId().toString(),
                Map.of("journeyCode", event.journeyCode(), "summary", event.journeyCode()));
    }

    @ApplicationModuleListener
    void on(StepPendingSource event) {
        dispatch(
                "StepPendingSource",
                event.instanceId().toString(),
                event.instanceId() + ":" + event.stepCode(),
                Map.of("summary", event.stepCode()));
    }

    @ApplicationModuleListener
    void on(ApplicationStateChanged event) {
        dispatch(
                "ApplicationStateChanged",
                event.instanceId().toString(),
                event.instanceId() + ":" + event.newStatus(),
                Map.of("summary", event.newStatus()));
    }

    @ApplicationModuleListener
    void on(SlaBreached event) {
        dispatch("SlaBreached", event.instanceId().toString(), event.instanceId().toString(), Map.of("summary", "sla"));
    }

    @ApplicationModuleListener
    void on(ManualUploadRequested event) {
        dispatch(
                "ManualUploadRequested",
                event.instanceId().toString(),
                event.instanceId() + ":" + event.stepCode(),
                Map.of("summary", event.stepCode()));
    }

    @ApplicationModuleListener
    void on(ApplicationReferenceIssued event) {
        dispatch(
                "ApplicationReferenceIssued",
                event.citizenId().toString(),
                event.referenceNo(),
                Map.of("referenceNo", event.referenceNo(), "summary", event.referenceNo()));
    }

    @ApplicationModuleListener
    void on(LinkAsserted event) {
        dispatch(
                "LinkAsserted",
                event.citizenId().toString(),
                event.citizenId() + ":" + event.departmentCode(),
                Map.of("summary", event.departmentCode()));
    }

    @ApplicationModuleListener
    void on(CandidateRaised event) {
        candidateAlerts.add(event);
    }

    @ApplicationModuleListener
    void on(PointerUpserted event) {
        dispatch(
                "PointerUpserted",
                event.subjectId().toString(),
                event.pointerId().toString(),
                Map.of("summary", event.dataCategory()));
    }

    @ApplicationModuleListener
    void on(BatchRowRejected event) {
        dispatch(
                "BatchRowRejected",
                "ops",
                event.dataSourceCode() + ":" + event.filename() + ":" + event.rowNumber(),
                Map.of("summary", event.reason()));
    }

    void dispatchDirect(String eventType, String recipientId, String sourceEventId, Map<String, String> vars) {
        dispatch(eventType, recipientId, sourceEventId, vars);
    }

    void dispatch(String eventType, String recipientId, String sourceEventId, Map<String, String> vars) {
        EnumSet<Channel> chosen = EnumSet.noneOf(Channel.class);
        if (MANDATORY.contains(eventType)) {
            chosen.add(Channel.IN_APP);
        }
        for (SubscriptionEntity sub : subscriptions.findByRecipientIdAndEventTypeAndEnabledTrue(recipientId, eventType)) {
            chosen.add(Channel.valueOf(sub.getChannel()));
        }
        String locale = subscriptions.findByRecipientId(recipientId).stream()
                .map(SubscriptionEntity::getLocale)
                .findFirst()
                .orElse("en");
        for (Channel channel : chosen) {
            try {
                deliver(eventType, recipientId, sourceEventId, vars, channel, locale);
            } catch (RuntimeException ex) {
                // never rethrown — a failed SMS must not fail the business TX
            }
        }
    }

    private void deliver(
            String eventType,
            String recipientId,
            String sourceEventId,
            Map<String, String> vars,
            Channel channel,
            String locale) {
        String dedupeKey = eventType + ":" + sourceEventId;
        if (deliveries.existsByRecipientIdAndChannelAndDedupeKey(recipientId, channel.name(), dedupeKey)) {
            return;
        }
        String templateRef = locale + "/" + eventType;
        String body = renderWithFallback(templateRef, vars, locale, eventType);
        DeliveryEntity row = new DeliveryEntity();
        row.setId(UUID.randomUUID());
        row.setRecipientId(recipientId);
        row.setEventType(eventType);
        row.setChannel(channel.name());
        row.setDedupeKey(dedupeKey);
        row.setTemplateRef(templateRef);
        row.setRenderedBody(body);
        row.setStatus("PENDING");
        row.setAttempts(1);
        row.setCreatedAt(Instant.now());
        deliveries.save(row);
        NotificationChannel impl = channels.get(channel);
        try {
            if (impl == null) {
                throw new IllegalStateException("no channel " + channel);
            }
            var outcome = impl.send(new RenderedMessage(recipientId, body));
            if (!outcome.sent()) {
                throw new IllegalStateException(outcome.error());
            }
            row.setStatus("SENT");
            row.setSentAt(Instant.now());
            deliveries.save(row);
        } catch (Exception e) {
            row.setStatus("FAILED");
            row.setLastError(e.getMessage());
            deliveries.save(row);
            events.publishEvent(new DeliveryFailed(row.getId(), channel, e.getMessage()));
        }
    }

    private String renderWithFallback(String templateRef, Map<String, String> vars, String locale, String eventType) {
        try {
            return renderer.render(templateRef, vars);
        } catch (TemplateNotFoundException e) {
            try {
                return renderer.render("en/" + eventType, vars);
            } catch (TemplateNotFoundException missing) {
                return renderer.render("en/Generic", vars);
            }
        }
    }
}
