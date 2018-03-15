/*
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial contribution
 */
package org.eclipse.ditto.services.connectivity.messaging.rabbitmq;

import static org.eclipse.ditto.model.base.common.ConditionChecker.checkNotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.eclipse.ditto.model.connectivity.ConnectivityModelFactory;
import org.eclipse.ditto.model.connectivity.ExternalMessage;
import org.eclipse.ditto.model.connectivity.ExternalMessageBuilder;
import org.eclipse.ditto.model.base.headers.DittoHeaderDefinition;
import org.eclipse.ditto.services.connectivity.mapping.MessageMappers;
import org.eclipse.ditto.services.utils.akka.LogUtil;

import com.rabbitmq.client.BasicProperties;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.DiagnosticLoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;

/**
 * Actor which receives message from an RabbitMQ source and forwards them to a {@code MessageMappingProcessorActor}.
 */
public final class RabbitMQConsumerActor extends AbstractActor {

    private static final String MESSAGE_ID_HEADER = "messageId";
    private static final String EXCHANGE_HEADER = "exchange";

    private static final String TEXT_PLAIN = "text/plain";
    private static final String APPLICATION_JSON = "application/json";

    private final DiagnosticLoggingAdapter log = LogUtil.obtain(this);

    private final ActorRef messageMappingProcessor;

    private RabbitMQConsumerActor(final ActorRef messageMappingProcessor) {
        this.messageMappingProcessor = checkNotNull(messageMappingProcessor, "messageMappingProcessor");
    }

    /**
     * Creates Akka configuration object {@link Props} for this {@code RabbitMQConsumerActor}.
     *
     * @param messageMappingProcessor the message mapping processor where received messages are forwarded to
     * @return the Akka configuration Props object.
     */
    static Props props(final ActorRef messageMappingProcessor) {
        return Props.create(
                RabbitMQConsumerActor.class, new Creator<RabbitMQConsumerActor>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public RabbitMQConsumerActor create() {
                        return new RabbitMQConsumerActor(messageMappingProcessor);
                    }
                });
    }

    @Override
    public Receive createReceive() {
        return ReceiveBuilder.create()
                .match(Delivery.class, this::handleDelivery)
                .matchAny(m -> {
                    log.debug("Unknown message: {}", m);
                    unhandled(m);
                }).build();
    }

    private void handleDelivery(final Delivery delivery) {
        final BasicProperties properties = delivery.getProperties();
        final Envelope envelope = delivery.getEnvelope();
        final byte[] body = delivery.getBody();
        try {
            final String correlationId = properties.getCorrelationId();
            LogUtil.enhanceLogWithCorrelationId(log, correlationId);
            final Map<String, String> headers = extractHeadersFromMessage(properties, envelope);
            final ExternalMessageBuilder externalMessageBuilder =
                    ConnectivityModelFactory.newExternalMessageBuilder(headers);
            final String contentType = properties.getContentType();
            if (shouldBeInterpretedAsText(contentType)) {
                final String text = new String(body, MessageMappers.determineCharset(contentType));
                externalMessageBuilder.withText(text);
            } else {
                externalMessageBuilder.withBytes(body);
            }
            final ExternalMessage externalMessage = externalMessageBuilder.build();
            log.debug("Received message from RabbitMQ ({}//{}): {}", envelope, properties);
            messageMappingProcessor.forward(externalMessage, getContext());
        } catch (final Exception e) {
            log.warning("Processing delivery {} failed: {}", envelope.getDeliveryTag(), e.getMessage(), e);
        }
    }

    private static boolean shouldBeInterpretedAsText(@Nullable final String contentType) {
        return contentType != null && (contentType.startsWith(TEXT_PLAIN) || contentType.startsWith(APPLICATION_JSON));
    }

    private Map<String, String> extractHeadersFromMessage(final BasicProperties properties, final Envelope envelope) {
        final Map<String, String> headersFromProperties =
                Optional.ofNullable(properties.getHeaders())
                        .map(Map::entrySet)
                        .map(this::setToStringStringMap).orElseGet(HashMap::new);

        // set headers specific to rmq messages
        // TODO DG is this still required?
        if (envelope.getExchange() != null) {
            headersFromProperties.put(EXCHANGE_HEADER, envelope.getExchange());
        }
        if (properties.getReplyTo() != null) {
            headersFromProperties.put(ExternalMessage.REPLY_TO_HEADER, properties.getReplyTo());
        }
        if (properties.getCorrelationId() != null) {
            headersFromProperties.put(DittoHeaderDefinition.CORRELATION_ID.getKey(), properties.getCorrelationId());
        }
        if (properties.getContentType() != null) {
            headersFromProperties.put(ExternalMessage.CONTENT_TYPE_HEADER, properties.getContentType());
        }
        headersFromProperties.put(MESSAGE_ID_HEADER, Long.toString(envelope.getDeliveryTag()));
        return headersFromProperties;
    }

    private Map<String, String> setToStringStringMap(final Set<Map.Entry<String, Object>> entries) {
        return entries.stream()
                .filter(entry -> Objects.nonNull(entry.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> String.valueOf(entry.getValue())));
    }

}
