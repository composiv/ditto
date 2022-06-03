/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.things.service.persistence.actors.strategies.commands;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.base.model.entity.metadata.Metadata;
import org.eclipse.ditto.base.model.entity.metadata.MetadataBuilder;
import org.eclipse.ditto.base.model.headers.DittoHeaderDefinition;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.base.model.signals.WithOptionalEntity;
import org.eclipse.ditto.base.model.signals.commands.Command;
import org.eclipse.ditto.internal.utils.headers.conditional.ConditionalHeadersValidator;
import org.eclipse.ditto.internal.utils.persistentactors.MetadataFromSignal;
import org.eclipse.ditto.internal.utils.persistentactors.etags.AbstractConditionHeaderCheckingCommandStrategy;
import org.eclipse.ditto.internal.utils.persistentactors.results.Result;
import org.eclipse.ditto.internal.utils.persistentactors.results.ResultFactory;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonPointer;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.things.model.Thing;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.signals.commands.ThingCommand;
import org.eclipse.ditto.things.model.signals.commands.modify.ThingModifyCommand;
import org.eclipse.ditto.things.model.signals.commands.query.ThingQueryCommand;
import org.eclipse.ditto.things.model.signals.events.ThingEvent;

/**
 * Abstract base class for {@link org.eclipse.ditto.things.model.signals.commands.ThingCommand} strategies.
 *
 * @param <C> the type of the handled command - of type {@code Command} as also
 * {@link org.eclipse.ditto.things.api.commands.sudo.SudoCommand} are handled which are no ThingCommands.
 */
@Immutable
abstract class AbstractThingCommandStrategy<C extends Command<C>>
        extends AbstractConditionHeaderCheckingCommandStrategy<C, Thing, ThingId, ThingEvent<?>> {

    private static final ConditionalHeadersValidator VALIDATOR =
            ThingsConditionalHeadersValidatorProvider.getInstance();

    protected AbstractThingCommandStrategy(final Class<C> theMatchingClass) {
        super(theMatchingClass);
    }

    @Override
    protected ConditionalHeadersValidator getValidator() {
        return VALIDATOR;
    }

    /**
     * Execute a command strategy after it is determined applicable.
     *
     * @param context context of the persistent actor.
     * @param entity entity of the persistent actor.
     * @param nextRevision the next revision to allocate to events.
     * @param command the incoming command.
     * @return result of the command strategy.
     */
    @Override
    public Result<ThingEvent<?>> apply(final Context<ThingId> context, @Nullable final Thing entity,
            final long nextRevision, final C command) {

        final var dittoHeaders = command.getDittoHeaders();
        final var dittoHeadersBuilder = dittoHeaders.toBuilder();
        final var loggerWithCorrelationId = context.getLog().withCorrelationId(command);
        final var thingConditionFailed = dittoHeaders.getCondition()
                .flatMap(condition -> ThingConditionValidator.validate(command, condition, entity));
        final Boolean liveChannelConditionPassed = dittoHeaders.getLiveChannelCondition()
                .map(condition -> ThingConditionValidator.validate(command, condition, entity).isEmpty())
                .orElse(false);

        final Result<ThingEvent<?>> result;
        if (command instanceof ThingQueryCommand<?> &&
                !dittoHeaders.getMetadataFieldsToGet().isEmpty()) {
            final Optional<Metadata> optionalMetadata = calculateMetadataForGetRequests(entity, command);
            dittoHeadersBuilder.putHeader(DittoHeaderDefinition.DITTO_METADATA.getKey(),
                    optionalMetadata.isPresent() ? optionalMetadata.get().toString() :
                            JsonObject.empty().toString());
        }

        if (thingConditionFailed.isPresent()) {
            final var conditionFailedException = thingConditionFailed.get();
            loggerWithCorrelationId.debug("Validating condition failed with exception <{}>.",
                    conditionFailedException.getMessage());
            result = ResultFactory.newErrorResult(conditionFailedException, command);
        } else if (dittoHeaders.getLiveChannelCondition().isPresent()) {
            dittoHeadersBuilder.putHeader(DittoHeaderDefinition.LIVE_CHANNEL_CONDITION_MATCHED.getKey(),
                    liveChannelConditionPassed.toString());
            result = super.apply(context, entity, nextRevision, command.setDittoHeaders(dittoHeadersBuilder.build()));
        } else {
            result = super.apply(context, entity, nextRevision, command.setDittoHeaders(dittoHeadersBuilder.build()));
        }

        return result;
    }

    @Override
    protected Optional<Metadata> calculateRelativeMetadata(@Nullable final Thing entity, final C command) {
        final DittoHeaders dittoHeaders = command.getDittoHeaders();
        final Metadata existingRelativeMetadata = getExistingMetadata(entity, command);

        if (command instanceof WithOptionalEntity withOptionalEntity &&
                !dittoHeaders.getMetadataHeadersToPut().isEmpty()) {
            final MetadataFromSignal relativeMetadata =
                    MetadataFromSignal.of(command, withOptionalEntity, existingRelativeMetadata);
            return Optional.ofNullable(relativeMetadata.get());
        } else if (command instanceof ThingModifyCommand<?> && !dittoHeaders.getMetadataFieldsToDelete().isEmpty()) {
            return calculateMetadataForDeleteRequests(entity, command);
        }

        return Optional.empty();
    }

    private Optional<Metadata> calculateMetadataForGetRequests(@Nullable Thing entity, final C command) {
        final Metadata existingMetadata = getExistingMetadata(entity, command);
        final Set<JsonPointer> metadataFields = command.getDittoHeaders().getMetadataFieldsToGet();
        final Set<JsonPointer> metadataFieldsWithResolvedWildcard;
        if (containsExactlySingleWildcard(metadataFields) && existingMetadata != null) {
            return Optional.of(existingMetadata);
        }

        if (checkIfContainsWildcards(metadataFields) && entity != null) {
            metadataFields.stream()
                    .filter(this::checkIfContainsWildcard)
                    .forEach(jsonPointer -> MetadataWildcardValidator.validateMetadataWildcard(command.getType(),
                            jsonPointer.toString(), DittoHeaderDefinition.GET_METADATA.getKey()));
            metadataFieldsWithResolvedWildcard =
                    expandWildcardsInMetadataExpression(metadataFields, entity, command,
                            DittoHeaderDefinition.GET_METADATA.getKey());
        } else {
            metadataFieldsWithResolvedWildcard = metadataFields;
        }

        final MetadataBuilder metadataBuilder = Metadata.newBuilder();
        if (existingMetadata != null && !metadataFieldsWithResolvedWildcard.isEmpty()) {
            metadataFieldsWithResolvedWildcard.forEach(metadataFieldPointer -> {
                final Optional<JsonValue> metadataValue = existingMetadata.getValue(metadataFieldPointer);
                metadataValue.ifPresent(
                        jsonValue -> metadataBuilder.set(metadataFieldPointer.toString(), jsonValue));
            });
            return Optional.of(metadataBuilder.build());
        }

        return Optional.empty();
    }

    private Optional<Metadata> calculateMetadataForDeleteRequests(@Nullable Thing entity, final C command) {
        final Metadata existingMetadata = getExistingMetadata(entity, command);
        final Set<JsonPointer> metadataFieldsToDelete = command.getDittoHeaders().getMetadataFieldsToDelete();
        if (containsExactlySingleWildcard(metadataFieldsToDelete) && existingMetadata != null) {
            // delete all metadata
            return Optional.of(Metadata.newMetadata(JsonObject.empty()));
        }

        final Set<JsonPointer> metadataFieldsWithResolvedWildcard;
        if (checkIfContainsWildcards(metadataFieldsToDelete) && entity != null) {
            metadataFieldsToDelete.stream()
                    .filter(this::checkIfContainsWildcard)
                    .forEach(jsonPointer -> MetadataWildcardValidator.validateMetadataWildcard(command.getType(),
                            jsonPointer.toString(), DittoHeaderDefinition.DELETE_METADATA.getKey()));
            metadataFieldsWithResolvedWildcard =
                    expandWildcardsInMetadataExpression(metadataFieldsToDelete, entity, command,
                            DittoHeaderDefinition.DELETE_METADATA.getKey());
        } else {
            metadataFieldsWithResolvedWildcard = metadataFieldsToDelete;
        }


        if (existingMetadata != null && !metadataFieldsWithResolvedWildcard.isEmpty()) {
            final MetadataBuilder metadataBuilder = existingMetadata.toBuilder();
            metadataFieldsWithResolvedWildcard.forEach(metadataBuilder::remove);

            return Optional.of(metadataBuilder.build());
        }

        return Optional.empty();
    }

    @Override
    public boolean isDefined(final C command) {
        return command instanceof ThingCommand;
    }

    @Nullable
    private Metadata getExistingMetadata(@Nullable final Thing entity, final C command) {
        return Optional.ofNullable(entity)
                .flatMap(Thing::getMetadata)
                .flatMap(m -> m.getValue(command.getResourcePath()))
                .filter(JsonValue::isObject)
                .map(JsonValue::asObject)
                .map(Metadata::newMetadata)
                .orElse(null);
    }

    private boolean checkIfContainsWildcards(final Set<JsonPointer> jsonPointers) {
        return jsonPointers.stream().anyMatch(this::checkIfContainsWildcard);
    }

    private boolean checkIfContainsWildcard(final JsonPointer jsonPointer) {
        return jsonPointer.toString().contains("/*/");
    }

    private boolean containsExactlySingleWildcard(final Set<JsonPointer> jsonPointers) {
        return jsonPointers.size() == 1 && jsonPointers.contains(JsonPointer.of("*"));
    }

    private Set<JsonPointer> expandWildcardsInMetadataExpression(final Set<JsonPointer> metadataPointerWithWildcard,
            final Thing entity, final C command, final String headerKey) {

        final Set<JsonPointer> resolvedMetadataPointers = new HashSet<>();

        metadataPointerWithWildcard.stream()
                .filter(jsonPointer -> !checkIfContainsWildcard(jsonPointer))
                .forEach(resolvedMetadataPointers::add);

        metadataPointerWithWildcard.stream()
                .filter(this::checkIfContainsWildcard)
                .map(jsonPointer -> MetadataFieldsWildcardResolver.resolve(command, entity, jsonPointer, headerKey))
                .forEach(resolvedMetadataPointers::addAll);

        return resolvedMetadataPointers;
    }

}
