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
package org.eclipse.ditto.policies.service.common.config;

import static org.mutabilitydetector.unittesting.MutabilityAssert.assertInstancesOf;
import static org.mutabilitydetector.unittesting.MutabilityMatchers.areImmutable;

import java.time.Duration;

import org.assertj.core.api.JUnitSoftAssertions;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import nl.jqno.equalsverifier.EqualsVerifier;

/**
 * Unit test for {@link DefaultPolicyAnnouncementConfig}.
 */
public final class DefaultPolicyAnnouncementConfigTest {

    private static Config commandTestConfig;

    @Rule
    public final JUnitSoftAssertions softly = new JUnitSoftAssertions();

    @BeforeClass
    public static void initTestFixture() {
        commandTestConfig = ConfigFactory.load("policy-announcement-config-test");
    }

    @Test
    public void assertImmutability() {
        assertInstancesOf(DefaultPolicyAnnouncementConfig.class, areImmutable());
    }

    @Test
    public void testHashCodeAndEquals() {
        EqualsVerifier.forClass(DefaultPolicyAnnouncementConfig.class)
                .usingGetClass()
                .verify();
    }

    @Test
    public void underTestReturnsDefaultValuesIfBaseConfigWasEmpty() {
        final PolicyAnnouncementConfig underTest =
                PolicyAnnouncementConfig.of(ConfigFactory.parseString("policy.supervisor {}"));

        softly.assertThat(underTest.getGracePeriod())
                .as(PolicyAnnouncementConfig.ConfigValue.GRACE_PERIOD.getConfigPath())
                .isEqualTo(Duration.ofHours(4L));

        softly.assertThat(underTest.getMaxTimeout())
                .as(PolicyAnnouncementConfig.ConfigValue.MAX_TIMEOUT.getConfigPath())
                .isEqualTo(Duration.ofMinutes(1L));
    }

    @Test
    public void underTestReturnsValuesOfBaseConfig() {
        final PolicyAnnouncementConfig underTest = PolicyAnnouncementConfig.of(commandTestConfig);

        softly.assertThat(underTest.getGracePeriod())
                .as(PolicyAnnouncementConfig.ConfigValue.GRACE_PERIOD.getConfigPath())
                .isEqualTo(Duration.ofSeconds(1234));

        softly.assertThat(underTest.getMaxTimeout())
                .as(PolicyAnnouncementConfig.ConfigValue.MAX_TIMEOUT.getConfigPath())
                .isEqualTo(Duration.ofSeconds(5678));
    }

}
