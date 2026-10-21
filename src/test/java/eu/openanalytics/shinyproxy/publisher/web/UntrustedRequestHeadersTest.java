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
import eu.openanalytics.containerproxy.model.spec.ContainerSpec;
import eu.openanalytics.containerproxy.model.spec.ProxySpec;
import eu.openanalytics.containerproxy.spec.expression.SpelField;
import eu.openanalytics.containerproxy.util.ProxyMappingManager;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What a built version is not handed (T9 (c)): the viewer's credentials, the platform's
 * session cookie and any X-SP-* the client wrote -- and that nothing else is touched, for
 * a built version or for anything that is not one.
 */
public class UntrustedRequestHeadersTest {

    private static final Set<String> SESSION = Set.of("SESSION", "JSESSIONID");

    private static ProxySpec spec(String id, Map<String, String> labels) {
        ContainerSpec.ContainerSpecBuilder c = ContainerSpec.builder().image(new SpelField.String("img"));
        if (labels != null) {
            c.labels(new SpelField.StringMap(labels));
        }
        return ProxySpec.builder().id(id).containerSpecs(List.of(c.build())).build();
    }

    private static Proxy proxy(String id, String specId) {
        Proxy p = Mockito.mock(Proxy.class);
        Mockito.when(p.getId()).thenReturn(id);
        Mockito.when(p.getSpecId()).thenReturn(specId);
        Mockito.when(p.getRuntimeObjectOrNull(HttpHeadersKey.inst)).thenReturn(
            new eu.openanalytics.containerproxy.model.runtime.runtimevalues.HttpHeaders(
                Map.of("X-SP-UserId", "alice", "X-SP-UserGroups", "ADMINS")));
        return p;
    }

    /** Runs one request through the handler and returns the headers the next handler saw. */
    private static HeaderMap through(String path, boolean dispatched, Proxy proxy, ProxySpec spec,
                                     Map<String, List<String>> headers) throws Exception {
        HttpServerExchange exchange = new HttpServerExchange(null);
        exchange.setRelativePath(path);
        headers.forEach((k, vs) -> vs.forEach(v -> exchange.getRequestHeaders().add(new HttpString(k), v)));
        if (dispatched) {
            exchange.putAttachment(ProxyMappingManager.ATTACHMENT_KEY_DISPATCHER, Mockito.mock(ProxyMappingManager.class));
        }
        List<HeaderMap> seen = new ArrayList<>();
        new UntrustedRequestHeaders(e -> seen.add(e.getRequestHeaders()), id -> proxy != null && id.equals(proxy.getId())
            ? proxy : null, id -> spec, SESSION).handleRequest(exchange);
        return seen.get(0);
    }

    private static Map<String, List<String>> forged() {
        return Map.of(
            "Authorization", List.of("Basic YWxpY2U6c2VjcmV0"),
            "Proxy-Authorization", List.of("Basic eA=="),
            "Cookie", List.of("SESSION=abc; app=1", "JSESSIONID=def; theme=dark"),
            "X-SP-UserId", List.of("alice"),
            "X-SP-UserGroups", List.of("ADMINS"),
            "X-SP-Something", List.of("forged"),
            "x-sp-other", List.of("forged"),
            "Accept", List.of("text/html"));
    }

    @Test
    public void aBuiltVersionIsNotHandedTheViewersCredentialsOrAForgedIdentityHeader() throws Exception {
        HeaderMap h = through("/proxy_endpoint/p1/x", true, proxy("p1", "s1"),
            spec("s1", Map.of("eu.skald.build", "b1")), forged());
        assertNull(h.get("Authorization"));
        assertNull(h.get("Proxy-Authorization"));
        assertNull(h.get("X-SP-Something"));
        assertNull(h.get("x-sp-other"));
        assertEquals("alice", h.getFirst("X-SP-UserId"), "the engine's own identity header stays");
        assertEquals("ADMINS", h.getFirst("X-SP-UserGroups"));
        assertEquals(List.of("app=1", "theme=dark"), new ArrayList<>(h.get("Cookie")), "only the app's own cookies");
        assertEquals("text/html", h.getFirst("Accept"));
    }

    @Test
    public void anythingThatIsNotABuiltVersionIsLeftAsUpstreamHandlesIt() throws Exception {
        // An admin's spec (YAML or a direct image): no build label.
        HeaderMap yaml = through("/proxy_endpoint/p1/x", true, proxy("p1", "s1"), spec("s1", null), forged());
        assertEquals("Basic YWxpY2U6c2VjcmV0", yaml.getFirst("Authorization"));
        assertEquals("forged", yaml.getFirst("X-SP-Something"));
        HeaderMap labelled = through("/proxy_endpoint/p1/x", true, proxy("p1", "s1"),
            spec("s1", Map.of("eu.skald.content", "c")), forged());
        assertEquals("forged", labelled.getFirst("X-SP-Something"), "only the build label marks a built version");
        // Not an exchange the engine dispatched, or not its endpoint: nothing touched.
        HeaderMap notDispatched = through("/proxy_endpoint/p1/x", false, proxy("p1", "s1"),
            spec("s1", Map.of("eu.skald.build", "b1")), forged());
        assertEquals("Basic YWxpY2U6c2VjcmV0", notDispatched.getFirst("Authorization"));
        HeaderMap elsewhere = through("/app/p1", true, proxy("p1", "s1"),
            spec("s1", Map.of("eu.skald.build", "b1")), forged());
        assertEquals("Basic YWxpY2U6c2VjcmV0", elsewhere.getFirst("Authorization"));
    }

    @Test
    public void aSpecThatCannotBeResolvedIsTreatedAsBuilt() throws Exception {
        HeaderMap h = through("/proxy_endpoint/p1/x", true, proxy("p1", "gone"), null, forged());
        assertNull(h.get("Authorization"), "unresolvable: the safe side");
    }

    @Test
    public void cookiesAreJudgedByNameOnly() {
        HeaderMap h = new HeaderMap();
        h.add(new HttpString("Cookie"), "SESSION=1;JSESSIONID=2 ; ;session_extra=3; Xsession=4; session=5; Session=6");
        h.add(new HttpString("Cookie"), "SESSION=only");
        UntrustedRequestHeaders.sanitise(h, Set.of(), SESSION);
        // By exact name (ec37449-F2): an app's own "session" (Flask's, Starlette's default) is the
        // app's, and so is a name that merely contains the platform's.
        assertEquals(List.of("session_extra=3; Xsession=4; session=5; Session=6"), new ArrayList<>(h.get("Cookie")));
        HeaderMap all = new HeaderMap();
        all.add(new HttpString("Cookie"), "SESSION=1; JSESSIONID=2");
        UntrustedRequestHeaders.sanitise(all, Set.of(), SESSION);
        assertNull(all.get("Cookie"), "nothing left, no empty header");
    }

    /** The engine's own constant, read where it is declared: an upgrade that moves it fails here. */
    @Test
    public void theEndpointIsTheEnginesOwn() throws Exception {
        var field = ProxyMappingManager.class.getDeclaredField("PROXY_INTERNAL_ENDPOINT");
        field.setAccessible(true);
        assertEquals(field.get(null) + "/", UntrustedRequestHeaders.PROXY_ENDPOINT);
    }

    @Test
    public void theProxyIdIsTheEndpointsFirstSegment() {
        assertEquals("p1", UntrustedRequestHeaders.proxyIdOf("/proxy_endpoint/p1/a/b"));
        assertEquals("p1", UntrustedRequestHeaders.proxyIdOf("/proxy_endpoint/p1"));
        assertNull(UntrustedRequestHeaders.proxyIdOf("/proxy_endpoint/"));
        assertNull(UntrustedRequestHeaders.proxyIdOf("/proxy_endpointx/p1"));
        assertNull(UntrustedRequestHeaders.proxyIdOf("/app/p1"));
        assertNull(UntrustedRequestHeaders.proxyIdOf(null));
    }
}
