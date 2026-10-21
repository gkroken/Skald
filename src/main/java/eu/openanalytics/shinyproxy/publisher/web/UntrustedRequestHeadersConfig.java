/*
 * Skald
 *
 * Copyright (C) 2026 Gard Kroken
 *
 * Built on ShinyProxy, Copyright (C) 2016-2026 Open Analytics NV.
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.shinyproxy.publisher.web;

import eu.openanalytics.containerproxy.service.ProxyService;
import eu.openanalytics.containerproxy.spec.IProxySpecProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Puts {@link UntrustedRequestHeaders} in front of ContainerProxy's proxy handler. A
 * customizer runs after the factory bean that registered the engine's own wrapper, so this one
 * is added after it and Undertow makes it the outer one: it sees each proxied exchange before
 * the engine's handler sends it on.
 */
@Configuration
@ConditionalOnProperty(name = "spring.datasource.url")
public class UntrustedRequestHeadersConfig {

    @Bean
    public WebServerFactoryCustomizer<UndertowServletWebServerFactory> untrustedRequestHeaders(
            ObjectProvider<ProxyService> proxyService, ObjectProvider<IProxySpecProvider> specProvider,
            @Value("${server.servlet.session.cookie.name:}") String sessionCookie) {
        // Spring Session's default, the servlet container's, and the configured name.
        Set<String> sessionCookies = new TreeSet<>(Set.of("SESSION", "JSESSIONID"));
        if (!sessionCookie.isBlank()) {
            sessionCookies.add(sessionCookie.trim().toUpperCase(Locale.ROOT));
        }
        Set<String> names = Set.copyOf(sessionCookies);
        return factory -> factory.addDeploymentInfoCustomizers(info -> info.addInnerHandlerChainWrapper(
                next -> UntrustedRequestHeaders.wrap(next, proxyService, specId -> {
                    IProxySpecProvider specs = specProvider.getIfAvailable();
                    return specs == null ? null : specs.getSpec(specId);
                }, names)));
    }
}
