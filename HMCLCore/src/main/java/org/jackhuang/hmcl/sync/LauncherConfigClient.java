// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

/// Downloads signed allowlisted configuration only from the fixed HTTPS administration origin.
@NotNullByDefault
public final class LauncherConfigClient {
    /// Trusted origin; assets cannot supply arbitrary URLs or redirects.
    private static final String ORIGIN = "https://sync.decreasedcraftallegiance.com";
    /// Upper bound on the signed JSON envelope, independent of HTTP headers.
    private static final int MAX_CONFIG = 128 * 1024;
    /// Verified local cache directory, separate from game and font files.
    private final Path cache;
    /// Public key pinned during HTTPS bootstrap, trusted delivery or explicit administrator binding.
    private final String publicKey;

    /// Creates a client with a pinned key; normal refreshes never replace this key from a response.
    public LauncherConfigClient(Path cache, String publicKey) {
        this.cache = cache;
        this.publicKey = publicKey.strip();
    }

    /// Fetches only fixed-origin paths; the package-private injection point is used by network contract tests.
    @FunctionalInterface
    interface Fetcher {
        /// Reads a bounded response or throws without returning unverified partial data.
        byte[] read(String path, int maximum) throws IOException;
    }

    /// Automatically binds a first install using normal HTTPS certificate validation at the fixed origin.
    /// A signature-valid, schema-valid publication must exist before any key or configuration is accepted.
    public static LauncherConfigClient bootstrap(Path cache, Path keyFile) throws IOException {
        return bootstrap(cache, keyFile, LauncherConfigClient::download);
    }

    /// Exercises first binding and existing-key protection with deterministic response fixtures.
    static LauncherConfigClient bootstrap(Path cache, Path keyFile, Fetcher fetch) throws IOException {
        if (Files.exists(keyFile)) {
            String existing = new String(readBounded(keyFile, 4096), StandardCharsets.UTF_8).strip();
            LauncherConfigClient client = new LauncherConfigClient(cache, existing);
            long revision = 0;
            try { revision = client.cached().get("revision").getAsLong(); } catch (IOException ignored) { }
            byte[] envelope = fetch.read("/api/public/launcher-config", MAX_CONFIG);
            verify(envelope, existing, revision);
            replace(cache.resolve("config.json"), envelope);
            return client;
        }
        String key;
        try {
            byte[] response = fetch.read("/api/public/launcher-trust", 4096);
            if (response.length > 4096) throw new IOException("首次连接资料过大");
            JsonObject trust = JsonParser.parseString(new String(response, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!trust.keySet().equals(Set.of("schema", "public_key")) || !trust.get("schema").toString().equals("1")
                    || !trust.getAsJsonPrimitive("public_key").isString()) throw new IOException("首次连接资料无效");
            key = trust.get("public_key").getAsString().strip();
            if (key.length() > 256) throw new IOException("首次连接公钥无效");
            KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key)));
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IOException("首次连接公钥无效", e);
        }
        byte[] envelope = fetch.read("/api/public/launcher-config", MAX_CONFIG);
        verify(envelope, key, 0);
        Files.createDirectories(keyFile.toAbsolutePath().getParent());
        // CREATE_NEW prevents a concurrent first connection from replacing an already accepted key.
        try { Files.writeString(keyFile, key + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); }
        catch (FileAlreadyExistsException e) {
            String existing = new String(readBounded(keyFile, 4096), StandardCharsets.UTF_8).strip();
            if (!existing.equals(key)) throw new IOException("公钥在首次连接期间发生变化，保留现有绑定", e);
        }
        replace(cache.resolve("config.json"), envelope);
        return new LauncherConfigClient(cache, key);
    }

    /// Verifies signature before parsing payload and rejects an older revision than the verified cache.
    public static JsonObject verify(byte[] envelope, String publicKey, long minimumRevision) throws IOException {
        try {
            if (envelope.length > MAX_CONFIG) throw new IOException("配置文件过大");
            JsonObject outer = JsonParser.parseString(new String(envelope, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!outer.keySet().equals(Set.of("payload", "signature"))) throw new IOException("签名封装字段无效");
            byte[] payload = Base64.getDecoder().decode(outer.get("payload").getAsString());
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))));
            verifier.update(payload);
            if (!verifier.verify(Base64.getDecoder().decode(outer.get("signature").getAsString()))) throw new IOException("配置签名不匹配");
            JsonObject document = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String numeric : new String[]{"schema", "revision"}) {
                if (!document.has(numeric) || !document.get(numeric).isJsonPrimitive() || !document.getAsJsonPrimitive(numeric).isNumber()
                        || !document.get(numeric).getAsString().matches("[0-9]{1,15}")) throw new IOException("配置版本类型无效");
            }
            if (!document.keySet().equals(Set.of("schema", "revision", "config")) || document.get("schema").getAsInt() != 1
                    || document.get("revision").getAsLong() < Math.max(1, minimumRevision)) throw new IOException("配置版本无效或已回退");
            validate(document.getAsJsonObject("config"));
            return document;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IOException("配置签名或数据格式无效", e);
        }
    }

    /// Enforces the same fixed schema and resource limits even when a signing key is misused.
    private static void validate(JsonObject config) throws IOException {
        Set<String> required = Set.of("title", "subtitle", "title_color", "title_size", "announcement_enabled", "announcement_title", "announcement_body", "server_address", "memory_mb", "font", "background");
        Set<String> optional = Set.of("title_html", "announcement_title_html", "announcement_html",
                "title_html_en", "announcement_title_html_en", "announcement_html_en",
                "launch_text", "launch_color", "launch_text_color", "launch_radius", "network_background");
        if (!config.keySet().containsAll(required) || config.keySet().stream().anyMatch(key -> !required.contains(key) && !optional.contains(key)))
            throw new IOException("不支持的配置字段");
        for (String field : Set.of("title_html", "announcement_title_html", "announcement_html", "title_html_en", "announcement_title_html_en", "announcement_html_en", "launch_text", "launch_color", "launch_text_color")) {
            if (config.has(field) && (!config.get(field).isJsonPrimitive() || !config.getAsJsonPrimitive(field).isString()))
                throw new IOException("外观字段类型无效");
        }
        for (String field : Set.of("launch_color", "launch_text_color")) {
            if (config.has(field) && !config.get(field).getAsString().matches("(?:#[0-9a-fA-F]{6})?"))
                throw new IOException("按钮颜色无效");
        }
        if (config.has("launch_radius") && !config.get("launch_radius").isJsonNull()
                && (!config.get("launch_radius").isJsonPrimitive() || !config.getAsJsonPrimitive("launch_radius").isNumber()
                || !config.get("launch_radius").getAsString().matches("[0-9]{1,2}") || config.get("launch_radius").getAsInt() > 32))
            throw new IOException("按钮圆角无效");
        for (String field : new String[]{"title", "subtitle", "title_color", "announcement_title", "announcement_body", "server_address"}) {
            String value = config.get(field).getAsString();
            if (!config.get(field).isJsonPrimitive() || !config.getAsJsonPrimitive(field).isString()) throw new IOException("文本字段类型无效");
            int limit = field.equals("announcement_body") ? 4000 : field.equals("server_address") ? 253 : 100;
            if (value.length() > limit || value.contains("<") || value.contains(">") || value.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\t')) throw new IOException("配置必须为有限长度的纯文本");
        }
        String color = config.get("title_color").getAsString();
        if (!color.isEmpty() && !color.matches("#[0-9a-fA-F]{6}")) throw new IOException("标题颜色无效");
        int size = config.get("title_size").getAsInt(), memory = config.get("memory_mb").getAsInt();
        for (String numeric : new String[]{"title_size", "memory_mb"}) {
            if (!config.get(numeric).isJsonPrimitive() || !config.getAsJsonPrimitive(numeric).isNumber() || !config.get(numeric).getAsString().matches("[0-9]+")) throw new IOException("数字字段类型无效");
        }
        if ((size != 0 && (size < 10 || size > 24)) || (memory != 0 && (memory < 512 || memory > 65536))) throw new IOException("推荐参数超出范围");
        if (!config.get("announcement_enabled").isJsonPrimitive() || !config.getAsJsonPrimitive("announcement_enabled").isBoolean()) throw new IOException("公告开关无效");
        String address = config.get("server_address").getAsString();
        if (!address.isEmpty() && !address.matches("[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?(?::[0-9]{1,5})?")) throw new IOException("服务器地址无效");
        if (address.contains(":")) {
            int port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
            if (port < 1 || port > 65535) throw new IOException("服务器端口无效");
        }
        for (String kind : Set.of("font", "background", "network_background")) {
            if (!config.has(kind) || config.get(kind).isJsonNull()) continue;
            JsonObject asset = config.getAsJsonObject(kind);
            if (!asset.keySet().equals(Set.of("sha256", "size", "extension")) || !asset.get("sha256").getAsString().matches("[a-f0-9]{64}")) throw new IOException("素材引用无效");
            Set<String> extensions = kind.equals("font") ? Set.of("ttf", "otf") : Set.of("png", "jpg", "jpeg", "webp");
            long length = asset.get("size").getAsLong();
            if (!asset.get("size").isJsonPrimitive() || !asset.getAsJsonPrimitive("size").isNumber() || !asset.get("size").getAsString().matches("[0-9]{1,10}")) throw new IOException("素材大小类型无效");
            if (!extensions.contains(asset.get("extension").getAsString()) || length <= 0 || length > limit(kind)) throw new IOException("素材格式或大小无效");
        }
    }

    /// Selects the localized rich text. Older releases used a single field.
    public static String localizedHtml(JsonObject config, String field, boolean chinese) {
        String english = field + "_en";
        String key = !chinese && config.has(english) ? english : field;
        return config.has(key) ? config.get(key).getAsString() : "";
    }

    /// Loads a previously verified envelope; it is verified again at every startup.
    public JsonObject cached() throws IOException {
        return verify(readBounded(cache.resolve("config.json"), MAX_CONFIG), publicKey, 0);
    }

    /// Fetches a newer signed configuration, retaining the previous cache if anything fails.
    public JsonObject refresh() throws IOException {
        Files.createDirectories(cache);
        long revision = 0;
        try { revision = cached().get("revision").getAsLong(); } catch (IOException ignored) { }
        byte[] bytes = download("/api/public/launcher-config", MAX_CONFIG);
        JsonObject document = verify(bytes, publicKey, revision);
        replace(cache.resolve("config.json"), bytes);
        return document;
    }

    /// Registers only the previously chosen signed font at startup; never downloads or changes preferences.
    public static @Nullable Path selectedFont(Path cache, String key) throws IOException {
        JsonObject document = verify(readBounded(cache.resolve("selected-font-config.json"), MAX_CONFIG), key, 0);
        if (document.getAsJsonObject("config").get("font").isJsonNull()) return null;
        JsonObject asset = document.getAsJsonObject("config").getAsJsonObject("font");
        Path path = cache.resolve(asset.get("sha256").getAsString() + "." + asset.get("extension").getAsString());
        checkAsset(readBounded(path, limit("font")), asset, "font");
        return path;
    }

    /// Verifies cached or downloaded resource bytes before returning an opaque content-addressed path.
    public Path asset(JsonObject asset, String kind) throws IOException {
        readAsset(asset, kind, true, true);
        return cache.resolve(asset.get("sha256").getAsString() + "." + asset.get("extension").getAsString());
    }

    /// Reads verified wallpaper bytes. Cache write failures do not prevent display.
    public byte[] wallpaper(JsonObject asset, boolean allowDownload) throws IOException {
        return readAsset(asset, "background", allowDownload, false);
    }

    /// Verifies bytes from cache or from the fixed asset endpoint before returning them.
    private byte[] readAsset(JsonObject asset, String kind, boolean allowDownload, boolean requireCache) throws IOException {
        JsonObject config = JsonParser.parseString("{\"title\":\"\",\"subtitle\":\"\",\"title_color\":\"\",\"title_size\":0,\"announcement_enabled\":false,\"announcement_title\":\"\",\"announcement_body\":\"\",\"server_address\":\"\",\"memory_mb\":0,\"font\":null,\"background\":null}").getAsJsonObject();
        if (!kind.equals("font") && !kind.equals("background")) throw new IOException("未知素材类型");
        config.add(kind, asset);
        validate(config);
        String hash = asset.get("sha256").getAsString();
        Path target = cache.resolve(hash + "." + asset.get("extension").getAsString());
        byte[] bytes;
        try {
            bytes = readBounded(target, limit(kind));
            checkAsset(bytes, asset, kind);
        } catch (IOException e) {
            if (!allowDownload) throw e;
            bytes = download("/api/public/launcher-assets/" + hash, limit(kind));
            checkAsset(bytes, asset, kind);
            if (requireCache) {
                replace(target, bytes);
            } else {
                try {
                    Files.createDirectories(cache);
                    Files.write(target, bytes);
                } catch (IOException ignored) {
                    // Readers verify the digest, so an incomplete wallpaper cache is safe to replace.
                }
            }
        }
        return bytes;
    }

    /// Checks declared size, SHA-256, and image/font magic before a native decoder receives the file.
    static void checkAsset(byte[] bytes, JsonObject asset, String kind) throws IOException {
        try {
            if (bytes.length != asset.get("size").getAsLong() || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(asset.get("sha256").getAsString())) throw new IOException("素材完整性校验失败");
            if (bytes.length < 12) throw new IOException("素材文件损坏");
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            if (kind.equals("font")) {
                int magic = buffer.getInt();
                if (magic != 0x00010000 && magic != 0x4f54544f && magic != 0x74727565) throw new IOException("字体格式无效");
                int count = Short.toUnsignedInt(buffer.getShort());
                if (count == 0 || count > 256 || bytes.length < 12 + count * 16) throw new IOException("字体表损坏");
                for (int i = 0; i < count; i++) {
                    long offset = Integer.toUnsignedLong(buffer.getInt(12 + i * 16 + 8));
                    long length = Integer.toUnsignedLong(buffer.getInt(12 + i * 16 + 12));
                    if (offset + length > bytes.length) throw new IOException("字体表越界");
                }
            } else {
                String ext = asset.get("extension").getAsString();
                boolean valid = switch (ext) {
                    case "png" -> buffer.getLong(0) == 0x89504e470d0a1a0aL;
                    case "jpg", "jpeg" -> (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
                    case "webp" -> buffer.getInt(0) == 0x52494646 && buffer.getInt(8) == 0x57454250;
                    default -> false;
                };
                if (!valid) throw new IOException("图片格式无效");
                if (ext.equals("png")) {
                    if (bytes.length < 24 || buffer.getInt(12) != 0x49484452) throw new IOException("PNG 文件头无效");
                    long width = Integer.toUnsignedLong(buffer.getInt(16)), height = Integer.toUnsignedLong(buffer.getInt(20));
                    if (width == 0 || height == 0 || width > 32000000 || height > 32000000 || width * height > 32000000) throw new IOException("图片分辨率过大");
                }
            }
        } catch (GeneralSecurityException | RuntimeException e) { throw new IOException("素材校验失败", e); }
    }

    /// Fixed per-kind decoder input limit matching the administrator upload contract.
    private static int limit(String kind) { return (kind.equals("font") ? 20 : 10) * 1024 * 1024; }

    /// Reads bounded local files without trusting their size header.
    private static byte[] readBounded(Path path, int maximum) throws IOException {
        try (var stream = Files.newInputStream(path)) {
            byte[] result = stream.readNBytes(maximum + 1);
            if (result.length > maximum) throw new IOException("文件过大");
            return result;
        }
    }

    /// Writes through a temporary sibling so network and disk failures cannot truncate the active cache.
    private static void replace(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "verified-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    /// Requires normal TLS validation, rejects redirects, and limits both read time and response size.
    private static byte[] download(String path, int maximum) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(ORIGIN + path + (path.equals("/api/public/launcher-config") ? "?appearance=3" : "")).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("User-Agent", "Allegiance-HMCL/LauncherConfig");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("启动器配置服务暂不可用（HTTP " + connection.getResponseCode() + "）");
            try (var stream = connection.getInputStream()) {
                byte[] bytes = stream.readNBytes(maximum + 1);
                if (bytes.length > maximum) throw new IOException("响应超过大小限制");
                return bytes;
            }
        } finally { connection.disconnect(); }
    }
}
