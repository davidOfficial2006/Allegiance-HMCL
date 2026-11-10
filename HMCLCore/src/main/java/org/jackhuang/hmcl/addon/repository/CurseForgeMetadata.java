// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.addon.repository;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.jackhuang.hmcl.util.function.ExceptionalFunction;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jackhuang.hmcl.util.io.ResponseCodeException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;

/// One metadata policy shared by search, project details, file completion, versions and fingerprints.
/// File downloads remain in HMCL's existing downloader; this class never invents a JAR URL.
@NotNullByDefault
public final class CurseForgeMetadata {
    /// Official API origin; only this origin may receive a CurseForge API key.
    public static final String OFFICIAL = "https://api.curseforge.com";
    /// Metadata mirror already used by HMCL's download-provider configuration.
    public static final String MCIM = "https://mod.mcimirror.top/curseforge";
    /// Bounds memory used by one metadata response.
    private static final int MAX_RESPONSE = 16 * 1024 * 1024;
    /// Large search/project responses are not retained; bounds the 512-entry cache to small records.
    private static final int MAX_CACHE_CHARS = 64 * 1024;
    /// Retains metadata briefly during import, retries and repeated UI operations.
    private static final long CACHE_MILLIS = 15 * 60 * 1000;
    /// Prevents hundreds of concurrent completion tasks repeatedly hitting a failed source.
    private static final long FAILURE_MILLIS = 60 * 1000;
    /// Explicit source preference: auto, official or mcim.
    private final String source;
    /// Optional official API key; never serialized into cache records or exception messages.
    private final String apiKey;
    /// Injectable request transport used by isolated tests.
    private final ExceptionalFunction<Request, String, IOException> transport;
    /// Clock used for expiry and deterministic tests.
    private final LongSupplier clock;
    /// Bounded in-memory raw JSON cache; no account or administrator credentials are stored.
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>();
    /// Source-level failure timestamps shared by HMCL's bounded concurrent requests.
    private final Map<String, Long> failed = new ConcurrentHashMap<>();

    /// Immutable outbound metadata request; tests can assert exact key isolation and payload parity.
    @NotNullByDefault
    record Request(String url, String method, String body, @Unmodifiable Map<String, String> headers) { }

    /// Immutable cached response with its fetch time.
    @NotNullByDefault
    private record Cached(String json, long fetchedAt) { }

    /// Creates the launcher policy, optionally controlled by the hmcl.curseforge.source JVM property.
    public CurseForgeMetadata(String apiKey) {
        this(System.getProperty("hmcl.curseforge.source", "auto"), apiKey, CurseForgeMetadata::fetch, System::currentTimeMillis);
    }

    /// Captures deterministic source, transport and clock choices for package-local tests.
    CurseForgeMetadata(String source, String apiKey, ExceptionalFunction<Request, String, IOException> transport, LongSupplier clock) {
        if (!Set.of("auto", "official", "mcim").contains(source))
            throw new IllegalArgumentException("hmcl.curseforge.source must be auto, official or mcim");
        this.source = source;
        this.apiKey = apiKey;
        this.transport = transport;
        this.clock = clock;
    }

    /// An API key is required only in explicitly official-only mode.
    public boolean isAvailable() { return !source.equals("official") || !apiKey.isBlank(); }

    /// Reads typed GET metadata with the same source/fallback policy as completion and search.
    public <T> T get(String path, TypeToken<T> type) throws IOException { return request("GET", path, "", type); }

    /// Reads typed POST metadata, including local-file fingerprint lookup.
    public <T> T post(String path, Object payload, TypeToken<T> type) throws IOException {
        return request("POST", path, JsonUtils.GSON.toJson(payload), type);
    }

    /// Shares cached metadata without holding a monitor during network I/O.
    /// The repository's existing semaphore bounds network concurrency.
    private <T> T request(String method, String path, String body, TypeToken<T> type) throws IOException {
        if (!path.startsWith("/v1/") || path.contains("#") || path.contains("\\") || path.contains(".."))
            throw new IOException("Invalid CurseForge metadata path");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Metadata request cancelled");
        String key = method + " " + path + " " + body;
        @Nullable Cached existing;
        synchronized (cache) { existing = cache.get(key); }
        long now = clock.getAsLong();
        if (existing != null && now - existing.fetchedAt() < CACHE_MILLIS)
            return decode(existing.json(), type);
        @Unmodifiable List<String> origins = source.equals("auto")
                ? (apiKey.isBlank() ? List.of(MCIM) : List.of(OFFICIAL, MCIM))
                : List.of(source.equals("official") ? OFFICIAL : MCIM);
        IOException failure = new IOException("CurseForge 元数据读取失败，请检查来源设置与网络；不会跳过整合包必要文件下载");
        for (String origin : origins) {
            if (origin.equals(OFFICIAL) && apiKey.isBlank()) {
                failure.addSuppressed(new IOException("Official API key is not configured"));
                continue;
            }
            @Nullable Long lastFailure = failed.get(origin);
            if (lastFailure != null && now - lastFailure < FAILURE_MILLIS) {
                failure.addSuppressed(new IOException("Source temporarily cooling down: " + URI.create(origin).getHost()));
                continue;
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            headers.put("User-Agent", "Allegiance-HMCL/0.1");
            if (origin.equals(OFFICIAL)) headers.put("X-API-KEY", apiKey);
            try {
                String raw = transport.apply(new Request(origin + path, method, body, Map.copyOf(headers)));
                T result = decode(raw, type);
                failed.remove(origin);
                synchronized (cache) {
                    if (raw.length() <= MAX_CACHE_CHARS) cache.put(key, new Cached(raw, clock.getAsLong()));
                    while (cache.size() > 512) cache.remove(cache.keySet().iterator().next());
                }
                return result;
            } catch (IOException e) {
                // A missing file is not a source outage; preserve its status for HMCL's manual-download handling.
                if (e instanceof FileNotFoundException || e instanceof ResponseCodeException code && code.getResponseCode() == 404)
                    throw e;
                if (e instanceof InterruptedIOException && Thread.currentThread().isInterrupted()) throw e;
                failed.put(origin, clock.getAsLong());
                failure.addSuppressed(e);
            }
        }
        if (existing != null) return decode(existing.json(), type);
        throw failure;
    }

    /// Rejects malformed API envelopes before caching or trying to interpret a platform record.
    private static <T> T decode(String raw, TypeToken<T> type) throws IOException {
        try {
            JsonElement envelope = JsonParser.parseString(raw);
            if (!envelope.isJsonObject() || !envelope.getAsJsonObject().has("data")
                    || envelope.getAsJsonObject().get("data").isJsonNull()) throw new IOException("Invalid metadata response envelope");
            return JsonUtils.fromNonNullJson(raw, type);
        } catch (RuntimeException e) { throw new IOException("Invalid metadata JSON", e); }
    }

    /// Uses HMCL's proxy/timeouts but refuses redirects so credentials cannot escape to another origin.
    static String fetch(Request request) throws IOException {
        HttpURLConnection connection = NetworkUtils.createHttpConnection(request.url());
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(request.method());
        request.headers().forEach(connection::setRequestProperty);
        try {
            if (request.method().equals("POST")) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream output = connection.getOutputStream()) { output.write(request.body().getBytes(StandardCharsets.UTF_8)); }
            }
            int status = connection.getResponseCode();
            if (status != 200) throw new ResponseCodeException(request.url(), status);
            try (InputStream raw = connection.getInputStream();
                 InputStream input = "gzip".equalsIgnoreCase(connection.getContentEncoding()) ? new GZIPInputStream(raw) : raw) {
                byte[] bytes = input.readNBytes(MAX_RESPONSE + 1);
                if (bytes.length > MAX_RESPONSE) throw new IOException("Metadata response is too large");
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
}
