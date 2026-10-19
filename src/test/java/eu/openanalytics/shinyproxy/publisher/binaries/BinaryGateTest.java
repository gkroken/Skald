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
package eu.openanalytics.shinyproxy.publisher.binaries;

import com.sun.net.httpserver.HttpServer;
import eu.openanalytics.shinyproxy.publisher.build.BuildsConfig;
import eu.openanalytics.shinyproxy.publisher.recipe.BaseCatalog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * forge's platform registrations as Skald reads them, and the gate that turns shared binaries
 * on for a base only when forge registers its key for that very base (T7b step 2d, part 1),
 * against a stub forge.
 */
public class BinaryGateTest {

    private static final String KEY = "noble-x86_64/4.6";
    private static final String TOKEN = "forge_skald_test_token";
    private static final String IMAGE_ID = "sha256:" + "a".repeat(64);
    private static HttpServer server;
    private static URI forge;
    private static final Map<String, String> registrations = new ConcurrentHashMap<>();
    private static final Map<String, Integer> statusOf = new ConcurrentHashMap<>();
    private static final AtomicInteger reads = new AtomicInteger();
    private static volatile String lastAuthorization;

    @BeforeAll
    public static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/cran/platforms/", exchange -> {
            reads.incrementAndGet();
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            String key = exchange.getRequestURI().getPath().substring("/api/v1/cran/platforms/".length());
            String body = registrations.get(key);
            int status = statusOf.getOrDefault(key, body == null ? 404 : 200);
            byte[] bytes = (status == 200 ? body : "no").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        forge = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterAll
    public static void stop() {
        server.stop(0);
    }

    @BeforeEach
    public void clean() {
        registrations.clear();
        statusOf.clear();
        reads.set(0);
    }

    private static String registration(String key, String digest, String platform) {
        return "{\"key\":\"" + key + "\",\"rPlatform\":\"" + platform + "\",\"builderImage\":\"skald/base/r\","
            + "\"builderDigest\":\"" + digest + "\",\"maxGlibc\":\"2.39\",\"libraries\":[],\"managedBy\":\"api\"}";
    }

    private static ForgeSettings settings() {
        return new ForgeSettings(forge, "cran-linux", TOKEN, Duration.ofSeconds(60), Duration.ofSeconds(5));
    }

    private static BaseCatalog.Entry rBase() {
        return new BaseCatalog.Entry("r-shiny-4.6.1-amd64-r3", "shiny", "r", "4.6.1", "amd64", 3, "10001", "10001",
            Map.of(), "noble", "x86_64-pc-linux-gnu", Map.of("Matrix", "1.7-5"));
    }

    // ------------------------------------------------------------------ reading registrations

    @Test
    public void aRegistrationIsReadWithSkaldsTokenAndCachedForItsTtl() throws Exception {
        registrations.put(KEY, registration(KEY, IMAGE_ID, "x86_64-pc-linux-gnu"));
        AtomicLong now = new AtomicLong();
        ForgePlatforms p = new ForgePlatforms(settings(), now::get);
        ForgePlatforms.Registration r = p.registration(KEY).orElseThrow();
        assertEquals(IMAGE_ID, r.builderDigest());
        assertEquals("x86_64-pc-linux-gnu", r.rPlatform());
        assertEquals("Bearer " + TOKEN, lastAuthorization);
        now.addAndGet(Duration.ofSeconds(59).toNanos());
        p.registration(KEY);
        assertEquals(1, reads.get(), "within the TTL: one read");
        registrations.put(KEY, registration(KEY, "sha256:" + "b".repeat(64), "x86_64-pc-linux-gnu"));
        now.addAndGet(Duration.ofSeconds(2).toNanos());
        assertEquals("sha256:" + "b".repeat(64), p.registration(KEY).orElseThrow().builderDigest(),
            "past the TTL a re-registration is seen (c365d95 N1)");
        assertEquals(2, reads.get());
    }

    @Test
    public void anUnknownKeyIsNoRegistration() throws Exception {
        assertEquals(Optional.empty(), new ForgePlatforms(settings()).registration(KEY));
    }

    @Test
    public void aRefusedTokenOrAnErrorIsAFailureAndNeverCached() throws Exception {
        AtomicLong now = new AtomicLong();
        ForgePlatforms p = new ForgePlatforms(settings(), now::get);
        statusOf.put(KEY, 401);
        IOException e = assertThrows(IOException.class, () -> p.registration(KEY));
        assertTrue(e.getMessage().contains("401"), e.getMessage());
        assertFalse(e.getMessage().contains(TOKEN), "the token is never in a message");
        statusOf.put(KEY, 500);
        assertThrows(IOException.class, () -> p.registration(KEY));
        statusOf.clear();
        registrations.put(KEY, registration(KEY, IMAGE_ID, "x86_64-pc-linux-gnu"));
        assertTrue(p.registration(KEY).isPresent(), "a failure was not cached");
    }

    @Test
    public void anAnswerThatIsNotThisKeysRegistrationIsAFailure() {
        ForgePlatforms p = new ForgePlatforms(settings());
        registrations.put(KEY, registration("trixie-x86_64/4.6", IMAGE_ID, "x86_64-pc-linux-gnu"));
        assertThrows(IOException.class, () -> p.registration(KEY));
        registrations.put(KEY, registration(KEY, "a".repeat(64), "x86_64-pc-linux-gnu"));
        assertThrows(IOException.class, () -> new ForgePlatforms(settings()).registration(KEY));
        registrations.put(KEY, "not json");
        assertThrows(IOException.class, () -> new ForgePlatforms(settings()).registration(KEY));
    }

    // ------------------------------------------------------------------ the gate

    private static BinaryGate.Decision decide(Optional<String> imageId) {
        return new BinaryGate(new ForgePlatforms(settings())).decide(rBase(), imageId);
    }

    @Test
    public void binariesAreOnOnlyWhenForgeRegistersThisVeryBase() {
        registrations.put(KEY, registration(KEY, IMAGE_ID, "x86_64-pc-linux-gnu"));
        BinaryGate.Decision on = decide(Optional.of(IMAGE_ID));
        assertTrue(on.binaries(), on.reason());
        BinaryGate.Decision other = decide(Optional.of("sha256:" + "c".repeat(64)));
        assertFalse(other.binaries());
        assertTrue(other.reason().contains("not base"), other.reason());
    }

    @Test
    public void anotherRPlatformIsOff() {
        registrations.put(KEY, registration(KEY, IMAGE_ID, "x86_64-unknown-linux-gnu"));
        BinaryGate.Decision d = decide(Optional.of(IMAGE_ID));
        assertFalse(d.binaries());
        assertTrue(d.reason().contains("R platform"), d.reason());
    }

    @Test
    public void anyDoubtIsOff() {
        assertFalse(decide(Optional.of(IMAGE_ID)).binaries(), "no registration");
        registrations.put(KEY, registration(KEY, IMAGE_ID, "x86_64-pc-linux-gnu"));
        assertFalse(decide(Optional.empty()).binaries(), "no image id on record");
        statusOf.put(KEY, 503);
        BinaryGate.Decision d = decide(Optional.of(IMAGE_ID));
        assertFalse(d.binaries(), "an unreadable registration");
        assertTrue(d.reason().contains("could not be read"), d.reason());
        statusOf.clear();
        BaseCatalog.Entry python = new BaseCatalog.Entry("py", "shiny", "python", "3.13.16", "amd64", 1, "10001",
            "10001", Map.of(), "trixie");
        assertFalse(new BinaryGate(new ForgePlatforms(settings())).decide(python, Optional.of(IMAGE_ID)).binaries());
    }

    // ------------------------------------------------------------------ settings

    @Test
    public void forgeSettingsNeedAPlainUrlAndATokenAndNeverShowIt() {
        assertEquals(null, ForgeSettings.DEFAULTS.url(), "off by default");
        ForgeSettings s = ForgeSettings.fromOverrides(Map.of("forge-url", "https://forge.example/",
            "forge-token", TOKEN, "forge-binary-repository", "cran-linux"));
        assertFalse(s.toString().contains(TOKEN), s.toString());
        assertTrue(s.toString().contains("****"));
        assertThrows(IllegalArgumentException.class, () -> ForgeSettings.fromOverrides(Map.of("forge-url",
            "https://forge.example/")), "a URL without a token");
        for (String bad : new String[] {"https://forge.example", "ftp://forge.example/", "https://u:p@forge.example/",
                "https://forge.example/?a=1"}) {
            assertThrows(IllegalArgumentException.class, () -> ForgeSettings.fromOverrides(Map.of("forge-url", bad,
                "forge-token", TOKEN)), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> ForgeSettings.fromOverrides(Map.of("forge-url",
            "https://forge.example/", "forge-token", "a b")));
        assertThrows(IllegalArgumentException.class, () -> ForgeSettings.fromOverrides(Map.of(
            "forge-binary-repository", "../x")));
        assertThrows(IllegalArgumentException.class, () -> ForgeSettings.fromOverrides(Map.of("forge-tokn", "x")));
    }

    @Test
    public void aRefusedUrlNeverRepeatsWhatItCarries() {
        // be34ce0-F1: a URL is refused exactly when it carries userinfo, a query or a fragment,
        // where secrets go; the refusal (which Spring logs at startup) must not repeat them.
        for (String setting : List.of("forge-url", "source-repository")) {
            for (String bad : List.of("https://skald:s3cretPW@forge.example/", "https://forge.example/?token=s3cretQ",
                    "https://forge.example/a b?t=s3cretS", "https://forge.example/x#s3cretF")) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
                    if (setting.equals("forge-url")) {
                        ForgeSettings.fromOverrides(Map.of("forge-url", bad, "forge-token", TOKEN));
                    } else {
                        SourceSettings.fromOverrides(Map.of("source-repository", bad));
                    }
                }, bad);
                assertFalse(e.getMessage().contains("s3cret"), setting + ": " + e.getMessage());
                assertTrue(e.getMessage().contains(setting), e.getMessage());
            }
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ForgeSettings.fromOverrides(
            Map.of("forge-url", "https://u:p@forge.example:8443/", "forge-token", TOKEN)));
        assertTrue(e.getMessage().contains("credentials (user information)") && e.getMessage()
            .contains("https://forge.example:8443"), "says what is wrong and the safe part: " + e.getMessage());
    }

    @Test
    public void theApplicationSplitsForgeKeysFromTheOthers() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(BuildsConfig.class)
            .withPropertyValues("skald.builds.binaries.max-running=2",
                "skald.builds.binaries.source-repository=https://forge.example/repository/cran-proxy/",
                "skald.builds.binaries.forge-url=https://forge.example/",
                "skald.builds.binaries.forge-token=" + TOKEN)
            .run(ctx -> {
                assertNotNull(ctx.getBean(ForgeSettings.class).url());
                assertEquals(TOKEN, ctx.getBean(ForgeSettings.class).token());
                assertEquals(2, ctx.getBean(BinaryQueueSettings.class).maxRunning());
                assertNotNull(ctx.getBean(SourceSettings.class).repository());
            });
    }

    @Test
    public void aPublishedBasesImageIdIsReadAndChecked() throws Exception {
        byte[] catalog = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("images/catalog.json"));
        String id = "r-shiny-4.6.1-amd64-r3";
        String head = "{\"layout_version\":1,\"catalog_sha256\":\"" + sha256(catalog) + "\",\"registry\":\"reg:5000\","
            + "\"bases\":{\"" + id + "\":\"reg:5000/skald/base/" + id + "@sha256:" + "e".repeat(64) + "\"}";
        BaseCatalog c = BaseCatalog.load(new java.io.ByteArrayInputStream((head + ",\"image_ids\":{\"" + id + "\":\""
            + IMAGE_ID + "\"}}").getBytes(StandardCharsets.UTF_8)));
        assertEquals(Optional.of(IMAGE_ID), c.imageId(id));
        assertEquals(Optional.empty(), BaseCatalog.load(new java.io.ByteArrayInputStream((head + "}")
            .getBytes(StandardCharsets.UTF_8))).imageId(id), "optional");
        for (String bad : List.of(",\"image_ids\":{\"" + id + "\":\"" + "a".repeat(64) + "\"}",
                ",\"image_ids\":{\"python-shiny-3.13.16-amd64-r1\":\"" + IMAGE_ID + "\"}",
                ",\"image_ids\":[]", ",\"image_ids\":{\"" + id + "\":\"sha256:XYZ\"}")) {
            assertThrows(IllegalArgumentException.class, () -> BaseCatalog.load(new java.io.ByteArrayInputStream(
                (head + bad + "}").getBytes(StandardCharsets.UTF_8))), bad);
        }
    }

    private static String sha256(byte[] b) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
    }
}
