// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.addon.repository;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.jackhuang.hmcl.util.io.ResponseCodeException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/// Tests the one metadata policy used by both addon browsing and CurseForge pack completion.
@NotNullByDefault
class CurseForgeMetadataTest {
    /// A slow file cannot hold up unrelated project/file metadata during pack completion.
    @Test void networkRequestsDoNotHoldTheCacheMonitor() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CurseForgeMetadata policy = new CurseForgeMetadata("mcim", "", request -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test transport not released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            return RESPONSE;
        }, () -> 0);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> policy.get("/v1/mods/123", JSON));
            var second = pool.submit(() -> policy.get("/v1/mods/456", JSON));
            try { assertTrue(entered.await(3, TimeUnit.SECONDS), "independent requests must reach transport concurrently"); }
            finally { release.countDown(); }
            assertEquals(123, first.get(3, TimeUnit.SECONDS).getAsJsonObject("data").get("id").getAsInt());
            assertEquals(123, second.get(3, TimeUnit.SECONDS).getAsJsonObject("data").get("id").getAsInt());
        }
    }
    /// Typed JSON envelope for deterministic policy assertions.
    private static final TypeToken<JsonObject> JSON = TypeToken.get(JsonObject.class);
    /// Small valid platform response.
    private static final String RESPONSE = "{\"data\":{\"id\":123}}";

    /// An unsigned build without a key goes straight to MCIM, with a bounded shared cache.
    @Test void noKeyUsesMirrorAndCache() throws Exception {
        List<CurseForgeMetadata.Request> requests = new ArrayList<>();
        AtomicLong clock = new AtomicLong();
        CurseForgeMetadata policy = new CurseForgeMetadata("auto", "", request -> { requests.add(request); return RESPONSE; }, clock::get);
        assertTrue(policy.isAvailable());
        policy.get("/v1/mods/123", JSON);
        policy.get("/v1/mods/123", JSON);
        assertEquals(1, requests.size());
        assertEquals(CurseForgeMetadata.MCIM + "/v1/mods/123", requests.get(0).url());
        assertFalse(requests.get(0).headers().containsKey("X-API-KEY"));
        clock.set(16 * 60 * 1000);
        policy.get("/v1/mods/123", JSON);
        assertEquals(2, requests.size());
    }

    /// One official 403 switches to MCIM and cools down the failed official origin for other files.
    @Test void officialFailureNeverLeaksKeyAndCooldownExpires() throws Exception {
        List<CurseForgeMetadata.Request> requests = new ArrayList<>();
        AtomicLong clock = new AtomicLong();
        CurseForgeMetadata policy = new CurseForgeMetadata("auto", "test-key", request -> {
            requests.add(request);
            if (request.url().startsWith(CurseForgeMetadata.OFFICIAL)) throw new ResponseCodeException(request.url(), 403);
            return RESPONSE;
        }, clock::get);
        policy.get("/v1/mods/123", JSON);
        assertEquals("test-key", requests.get(0).headers().get("X-API-KEY"));
        assertFalse(requests.get(1).headers().containsKey("X-API-KEY"));
        policy.get("/v1/mods/456", JSON);
        assertEquals(3, requests.size());
        assertTrue(requests.get(2).url().startsWith(CurseForgeMetadata.MCIM));
        clock.set(61000);
        policy.get("/v1/mods/789", JSON);
        assertEquals(5, requests.size());
        assertTrue(requests.get(3).url().startsWith(CurseForgeMetadata.OFFICIAL));
    }

    /// Invalid upstream JSON is not cached as success; a valid mirror can provide the response instead.
    @Test void malformedDataFallsBackAndOutageKeepsStaleCache() throws Exception {
        AtomicLong clock = new AtomicLong();
        CurseForgeMetadata policy = new CurseForgeMetadata("auto", "test-key", request -> {
            if (clock.get() > 0) throw new IOException("test outage");
            return request.url().startsWith(CurseForgeMetadata.OFFICIAL) ? "<html>blocked</html>" : RESPONSE;
        }, clock::get);
        assertEquals(123, policy.get("/v1/mods/123", JSON).getAsJsonObject("data").get("id").getAsInt());
        clock.set(16 * 60 * 1000);
        assertEquals(123, policy.get("/v1/mods/123", JSON).getAsJsonObject("data").get("id").getAsInt());
        assertThrows(IOException.class, () -> policy.get("/v1/mods/456", JSON));
    }

    /// Explicit source choices are honored; 404 remains distinguishable for manual-download prompts.
    @Test void explicitSourcesAndMissingFiles() throws Exception {
        CurseForgeMetadata official = new CurseForgeMetadata("official", "", request -> { fail("must not request without a key"); return RESPONSE; }, () -> 0);
        assertFalse(official.isAvailable());
        assertThrows(IOException.class, () -> official.get("/v1/mods/123", JSON));
        CurseForgeMetadata mirror = new CurseForgeMetadata("mcim", "test-key", request -> {
            assertTrue(request.url().startsWith(CurseForgeMetadata.MCIM));
            assertFalse(request.headers().containsKey("X-API-KEY"));
            throw new ResponseCodeException(request.url(), 404);
        }, () -> 0);
        ResponseCodeException missing = assertThrows(ResponseCodeException.class, () -> mirror.get("/v1/mods/123/files/11", JSON));
        assertEquals(404, missing.getResponseCode());
        assertThrows(IllegalArgumentException.class, () -> new CurseForgeMetadata("typo", "", r -> RESPONSE, () -> 0));
    }

    /// Fingerprint POST payloads are preserved across fallback and use a separate cache key from GET.
    @Test void postPayloadAndPathValidation() throws Exception {
        CurseForgeMetadata policy = new CurseForgeMetadata("mcim", "", request -> {
            assertEquals("POST", request.method());
            assertEquals(com.google.gson.JsonParser.parseString("{\"fingerprints\":[42]}"), com.google.gson.JsonParser.parseString(request.body()));
            return RESPONSE;
        }, () -> 0);
        policy.post("/v1/fingerprints/432", java.util.Map.of("fingerprints", List.of(42)), JSON);
        assertThrows(IOException.class, () -> policy.get("https://evil.example", JSON));
        assertThrows(IOException.class, () -> policy.get("/v1/../private", JSON));
    }

    /// The exact method used by CurseCompletionTask now reaches the shared mirror policy without a key.
    @Test void nativePackFileLookupUsesPolicyAndChecksIdentity() throws Exception {
        List<String> urls = new ArrayList<>();
        String file = "{\"data\":{\"id\":11,\"modId\":123,\"fileName\":\"demo.jar\",\"displayName\":\"Demo 1.0\","
                + "\"fileDate\":\"2026-11-02T00:00:00Z\",\"releaseType\":1,\"hashes\":[],\"dependencies\":[],"
                + "\"gameVersions\":[\"1.20.1\"],\"downloadUrl\":\"https://edge.forgecdn.net/files/0/11/demo.jar\"}}";
        CurseForgeMetadata policy = new CurseForgeMetadata("auto", "", request -> { urls.add(request.url()); return file; }, () -> 0);
        CurseForgeRemoteAddonRepository repository = new CurseForgeRemoteAddonRepository(policy);
        assertEquals("demo.jar", repository.getAddonFile("123", "11").filename());
        assertEquals(CurseForgeMetadata.MCIM + "/v1/mods/123/files/11", urls.get(0));
        assertThrows(IOException.class, () -> repository.getAddonFile("999", "11"));
    }

    /// Search and categories use the same policy instead of independently rewriting official API URLs.
    @Test void browsingUsesTheSamePolicyAsCompletion() throws Exception {
        List<String> urls = new ArrayList<>();
        CurseForgeMetadata policy = new CurseForgeMetadata("auto", "", request -> {
            urls.add(request.url());
            return "{\"data\":[],\"pagination\":{\"totalCount\":0}}";
        }, () -> 0);
        CurseForgeRemoteAddonRepository repository = new CurseForgeRemoteAddonRepository(policy);
        var result = repository.search(new org.jackhuang.hmcl.download.DownloadProvider(),
                org.jackhuang.hmcl.addon.RemoteAddon.Type.MOD, "1.20.1", null, 0, 20, "demo",
                org.jackhuang.hmcl.addon.RemoteAddonRepository.SortType.POPULARITY,
                org.jackhuang.hmcl.addon.RemoteAddonRepository.SortOrder.DESC);
        assertEquals(0, result.totalPages());
        assertEquals(0, repository.getCategories(org.jackhuang.hmcl.addon.RemoteAddon.Type.MOD).count());
        assertEquals(2, urls.size());
        assertTrue(urls.get(0).startsWith(CurseForgeMetadata.MCIM + "/v1/mods/search?"));
        assertTrue(urls.get(1).startsWith(CurseForgeMetadata.MCIM + "/v1/categories?"));
    }

    /// Oversized-but-valid search responses are served without accumulating them in the small-record cache.
    @Test void largeResponsesAreNotRetained() throws Exception {
        AtomicLong requests = new AtomicLong();
        String large = "{\"data\":{\"padding\":\"" + "a".repeat(70 * 1024) + "\"}}";
        CurseForgeMetadata policy = new CurseForgeMetadata("mcim", "", request -> { requests.incrementAndGet(); return large; }, () -> 0);
        policy.get("/v1/mods/123", JSON);
        policy.get("/v1/mods/123", JSON);
        assertEquals(2, requests.get());
    }
}
