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

import eu.openanalytics.containerproxy.model.runtime.Proxy;
import eu.openanalytics.containerproxy.model.runtime.runtimevalues.HttpHeadersKey;
import eu.openanalytics.containerproxy.model.spec.ProxySpec;
import eu.openanalytics.containerproxy.service.ProxyService;
import eu.openanalytics.containerproxy.util.ProxyMappingManager;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * What the platform does not hand to a version Skald built (T9 (c), found by the live probe):
 * the viewer's credentials and any identity header the client wrote itself.
 *
 * <p>A built version runs a publisher's bundle, which is untrusted code. ContainerProxy
 * forwards every request header to the app, so without this the app received the viewer's
 * {@code Authorization} header -- with HTTP Basic, the viewer's password -- and the platform's
 * session cookie, with which code in the app could act as the viewer on the platform itself
 * (an admin viewing an app could have their session used against {@code /admin}). And an
 * {@code X-SP-*} header the client sent reached the app beside the engine's own, so an app
 * trusting the {@code X-SP-} prefix could be lied to.
 *
 * <p>Applied where the engine proxies -- an Undertow handler in front of its proxy handler,
 * on every exchange it dispatched -- so every route to a built version is covered:
 * {@code /c/<path>/}, upstream's {@code /app/} and {@code /app_direct/}, and the WebSocket
 * upgrade. Only for built versions: an admin's YAML spec and a direct-image version keep
 * upstream's behaviour exactly ("unchanged runtime semantics"). No upstream class changes:
 * the seams are Undertow's handler chain and ContainerProxy's public dispatcher attachment.
 *
 * <p>Removed: {@code Authorization} and {@code Proxy-Authorization}; the platform's session
 * cookies from {@code Cookie} (the app's own cookies stay); every {@code X-SP-*} header that is
 * not one the engine set for this proxy (those it set replaced the client's already).
 */
public final class UntrustedRequestHeaders implements HttpHandler {

    /** ContainerProxy 1.2.4's ProxyMappingManager.PROXY_INTERNAL_ENDPOINT (private there). */
    static final String PROXY_ENDPOINT = "/proxy_endpoint/";

    private final HttpHandler next;
    private final Function<String, Proxy> proxies;
    private final Function<String, ProxySpec> specs;
    private final Set<String> sessionCookies;
    /** Whether a proxy's spec is a built version; a proxy's spec never changes. Bounded. */
    private final Map<String, Boolean> built = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 10_000;
        }
    });

    UntrustedRequestHeaders(HttpHandler next, Function<String, Proxy> proxies, Function<String, ProxySpec> specs,
                            Set<String> sessionCookies) {
        this.next = next;
        this.proxies = proxies;
        this.specs = specs;
        this.sessionCookies = sessionCookies;
    }

    static UntrustedRequestHeaders wrap(HttpHandler next, ObjectProvider<ProxyService> proxyService,
                                        Function<String, ProxySpec> specs, Set<String> sessionCookies) {
        return new UntrustedRequestHeaders(next, id -> {
            ProxyService service = proxyService.getIfAvailable();
            return service == null ? null : service.getProxy(id);
        }, specs, sessionCookies);
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.getAttachment(ProxyMappingManager.ATTACHMENT_KEY_DISPATCHER) != null) {
            String proxyId = proxyIdOf(exchange.getRelativePath());
            if (proxyId != null) {
                Proxy proxy = proxies.apply(proxyId);
                if (proxy != null && isBuilt(proxy)) {
                    sanitise(exchange.getRequestHeaders(), engineHeaders(proxy), sessionCookies);
                }
            }
        }
        next.handleRequest(exchange);
    }

    /** The proxy id of an internal path {@code /proxy_endpoint/<id>/...}, or null. */
    static String proxyIdOf(String path) {
        if (path == null || !path.startsWith(PROXY_ENDPOINT)) {
            return null;
        }
        int end = path.indexOf('/', PROXY_ENDPOINT.length());
        String id = end < 0 ? path.substring(PROXY_ENDPOINT.length()) : path.substring(PROXY_ENDPOINT.length(), end);
        return id.isEmpty() ? null : id;
    }

    private boolean isBuilt(Proxy proxy) {
        return built.computeIfAbsent(proxy.getId(), id -> {
            ProxySpec spec = specs.apply(proxy.getSpecId());
            // A built version's container carries the build's label (ContentSpecRepository);
            // nothing else does. Unresolvable: treated as built, the safe side.
            if (spec == null || spec.getContainerSpecs().isEmpty()) {
                return true;
            }
            var labels = spec.getContainerSpecs().get(0).getLabels();
            Map<String, String> map = labels == null ? null : labels.getOriginalValue();
            return map != null && map.containsKey("eu.skald.build");
        });
    }

    /** Names of the headers the engine set for this proxy (lower-case). */
    private static Set<String> engineHeaders(Proxy proxy) {
        var headers = proxy.getRuntimeObjectOrNull(HttpHeadersKey.inst);
        if (headers == null) {
            return Set.of();
        }
        Set<String> names = new java.util.HashSet<>();
        headers.getUndertowHeaderMap().getHeaderNames().forEach(n -> names.add(n.toString().toLowerCase(Locale.ROOT)));
        return names;
    }

    /** The removals, on one request's headers. */
    static void sanitise(HeaderMap headers, Set<String> engineSet, Set<String> sessionCookies) {
        headers.remove(Headers.AUTHORIZATION);
        headers.remove(Headers.PROXY_AUTHORIZATION);
        List<HttpString> forged = new ArrayList<>();
        for (HttpString name : headers.getHeaderNames()) {
            String lower = name.toString().toLowerCase(Locale.ROOT);
            if (lower.startsWith("x-sp-") && !engineSet.contains(lower)) {
                forged.add(name);
            }
        }
        forged.forEach(headers::remove);
        HeaderValues cookies = headers.get(Headers.COOKIE);
        if (cookies != null) {
            List<String> kept = new ArrayList<>();
            for (String line : cookies) {
                List<String> parts = new ArrayList<>();
                for (String pair : line.split(";")) {
                    String trimmed = pair.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    int eq = trimmed.indexOf('=');
                    String cookieName = (eq < 0 ? trimmed : trimmed.substring(0, eq)).trim();
                    if (!sessionCookies.contains(cookieName.toUpperCase(Locale.ROOT))) {
                        parts.add(trimmed);
                    }
                }
                if (!parts.isEmpty()) {
                    kept.add(String.join("; ", parts));
                }
            }
            headers.remove(Headers.COOKIE);
            kept.forEach(v -> headers.add(Headers.COOKIE, v));
        }
    }
}
