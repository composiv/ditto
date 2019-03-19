/*
 * Copyright (c) 2017-2018 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.gateway.endpoints.config;

import java.io.Serializable;
import java.util.Objects;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.services.utils.cache.config.CacheConfig;
import org.eclipse.ditto.services.utils.cache.config.DefaultCacheConfig;
import org.eclipse.ditto.services.utils.config.DefaultScopedConfig;

import com.typesafe.config.Config;

/**
 * This class is the default implementation of the Gateway's caches config.
 */
@Immutable
public final class DefaultCachesConfig implements CachesConfig, Serializable {

    private static final String CONFIG_PATH = "cache";

    private static final long serialVersionUID = -4371780699062739928L;

    private final CacheConfig publicKeysConfig;

    private DefaultCachesConfig(final CacheConfig thePublicKeysConfig) {
        publicKeysConfig = thePublicKeysConfig;
    }

    /**
     * Returns an instance of {@code DefaultCachesConfig} based on the settings of the specified Config.
     *
     * @param config is supposed to provide the settings of the caches config at {@value #CONFIG_PATH}.
     * @return the instance.
     * @throws org.eclipse.ditto.services.utils.config.DittoConfigError if {@code config} is invalid.
     */
    public static DefaultCachesConfig of(final Config config) {
        final DefaultScopedConfig cacheScopedConfig = DefaultScopedConfig.newInstance(config, CONFIG_PATH);
        return new DefaultCachesConfig(DefaultCacheConfig.of(cacheScopedConfig, "publickeys"));
    }

    @Override
    public CacheConfig getPublicKeysConfig() {
        return publicKeysConfig;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DefaultCachesConfig that = (DefaultCachesConfig) o;
        return Objects.equals(publicKeysConfig, that.publicKeysConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(publicKeysConfig);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " [" +
                "publicKeysConfig=" + publicKeysConfig +
                "]";
    }

}
