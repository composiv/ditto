/*
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.policies.service.persistence.actors.announcements;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.eclipse.ditto.internal.utils.akka.logging.DittoDiagnosticLoggingAdapter;
import org.eclipse.ditto.internal.utils.akka.logging.DittoLoggerFactory;
import org.eclipse.ditto.internal.utils.pubsub.DistributedPub;
import org.eclipse.ditto.policies.model.Policy;
import org.eclipse.ditto.policies.model.PolicyEntry;
import org.eclipse.ditto.policies.model.PolicyId;
import org.eclipse.ditto.policies.model.PolicyLifecycle;
import org.eclipse.ditto.policies.model.Subject;
import org.eclipse.ditto.policies.model.Subjects;
import org.eclipse.ditto.policies.model.signals.announcements.PolicyAnnouncement;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.Terminated;
import akka.japi.pf.ReceiveBuilder;

/**
 * Manager of actors responsible for policy announcements.
 */
public final class PolicyAnnouncementManager extends AbstractActor {

    private final DittoDiagnosticLoggingAdapter log = DittoLoggerFactory.getDiagnosticLoggingAdapter(this);

    private final PolicyId policyId;
    private final Duration gracePeriod;
    private final DistributedPub<PolicyAnnouncement<?>> policyAnnouncementPub;
    private final Duration maxTimeout;
    private final Map<Subject, ActorRef> subjectExpiryActors;
    private final Map<ActorRef, Subject> activeSubjects;
    private final ActorRef commandForwarder;

    private PolicyAnnouncementManager(final PolicyId policyId, final Duration gracePeriod,
            final DistributedPub<PolicyAnnouncement<?>> policyAnnouncementPub,
            final Duration maxTimeout,
            final ActorRef commandForwarder) {
        this.policyId = policyId;
        this.gracePeriod = gracePeriod;
        this.policyAnnouncementPub = policyAnnouncementPub;
        this.maxTimeout = maxTimeout;
        this.commandForwarder = commandForwarder;
        this.subjectExpiryActors = new HashMap<>();
        this.activeSubjects = new HashMap<>();
    }

    /**
     * Create the Props object for this actor.
     *
     * @param policyId The policy ID.
     * @param gracePeriod How long overdue acknowledgements are tolerated.
     * @param policyAnnouncementPub Ditto pubsub API to publish policy announcements.
     * @param maxTimeout The maximum timeout for acknowledgements.
     * @param forwarder Actor to forward policy commands to.
     * @return The Props object.
     */
    public static Props props(final PolicyId policyId, final Duration gracePeriod,
            final DistributedPub<PolicyAnnouncement<?>> policyAnnouncementPub, final Duration maxTimeout,
            final ActorRef forwarder) {
        return Props.create(PolicyAnnouncementManager.class, policyId, gracePeriod, policyAnnouncementPub, maxTimeout,
                forwarder);
    }

    @Override
    public Receive createReceive() {
        return ReceiveBuilder.create()
                .match(Policy.class, this::onPolicyModified)
                .match(Terminated.class, this::onChildTerminated)
                .build();
    }

    private void onPolicyModified(final Policy policy) {
        final var subjects = getSubjectsWithExpiryOrAnnouncements(policy);
        final var newSubjects = setDifference(subjects, subjectExpiryActors.keySet());
        final var deletedSubjects = setDifference(subjectExpiryActors.keySet(), subjects);
        log.debug("OnPolicyModified policy=<{}> newSubjects=<{}> deletedSubjects=<{}>", policy, newSubjects,
                deletedSubjects);
        for (final var newSubject : newSubjects) {
            startChild(newSubject);
        }
        for (final var deletedSubject : deletedSubjects) {
            sendSubjectDeleted(deletedSubject);
        }
    }

    private void startChild(final Subject subject) {
        final var props =
                SubjectExpiryActor.props(policyId, subject, gracePeriod, policyAnnouncementPub, maxTimeout,
                        commandForwarder);

        final var child = getContext().actorOf(props);
        getContext().watch(child);
        subjectExpiryActors.put(subject, child);
        activeSubjects.put(child, subject);
    }

    private void onChildTerminated(final Terminated terminated) {
        final var terminatedActor = terminated.actor();
        final var removedSubject = activeSubjects.remove(terminatedActor);
        if (removedSubject != null) {
            final var child = subjectExpiryActors.remove(removedSubject);
            log.debug("OnChildTerminated: Removed terminated child <{}>", child);
        } else {
            log.debug("OnChildTerminated: Child not found: <{}>", terminatedActor);
        }
    }

    private void sendSubjectDeleted(final Subject subject) {
        final var child = subjectExpiryActors.get(subject);
        if (child != null) {
            child.tell(SubjectExpiryActor.Message.SUBJECT_DELETED, ActorRef.noSender());
        } else {
            log.error("Attempting to notify nonexistent child for deleted subject <{}>", subject);
        }
    }

    private Set<Subject> getSubjectsWithExpiryOrAnnouncements(final Policy policy) {
        if (policy.getLifecycle().filter(lifeCycle -> lifeCycle == PolicyLifecycle.ACTIVE).isPresent()) {
            return StreamSupport.stream(policy.spliterator(), false)
                    .map(PolicyEntry::getSubjects)
                    .flatMap(Subjects::stream)
                    .filter(subject -> subject.getExpiry().isPresent() || subject.getAnnouncement().isPresent())
                    .collect(Collectors.toSet());
        } else {
            return Set.of();
        }
    }

    private static List<Subject> setDifference(final Set<Subject> minuend, final Set<Subject> subtrahend) {
        return minuend.stream()
                .filter(subject -> !subtrahend.contains(subject))
                .collect(Collectors.toList());
    }
}
