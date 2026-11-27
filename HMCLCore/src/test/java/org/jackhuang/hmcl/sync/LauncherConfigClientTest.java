// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/// Security contract tests for signed configuration, passive resources and malformed inputs.
@NotNullByDefault
class LauncherConfigClientTest {
    /// Per-test isolated delivery and cache directories.
    @TempDir private @Nullable Path temporary;

    /// Creates the fixed trust-endpoint response; it contains only a public key.
    private byte[] trust(KeyPair key) {
        JsonObject response = new JsonObject();
        response.addProperty("schema", 1);
        response.addProperty("public_key", Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));
        return response.toString().getBytes(StandardCharsets.UTF_8);
    }

    /// First connection pins the key only after the published configuration passes signature validation.
    @Test void automaticFirstConnectionAndReopen() throws Exception {
        Path root = java.util.Objects.requireNonNull(temporary);
        Path keyFile = root.resolve("launcher-config-public-key.txt"), cache = root.resolve("cache");
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] envelope = sign(document(), key);
        LauncherConfigClient client = LauncherConfigClient.bootstrap(cache, keyFile, (path, maximum) -> path.endsWith("launcher-trust") ? trust(key) : envelope);
        assertTrue(Files.isRegularFile(keyFile));
        assertEquals(2, client.cached().get("revision").getAsInt());
        LauncherConfigClient reopened = LauncherConfigClient.bootstrap(cache, keyFile, (path, maximum) -> {
            assertEquals("/api/public/launcher-config", path, "existing bindings never ask the server for a replacement key");
            return envelope;
        });
        assertEquals(2, reopened.cached().get("revision").getAsInt());
    }

    /// An unavailable publication or a signature from a different key cannot leave a partially trusted install.
    @Test void failedFirstConnectionLeavesNoKeyOrCache() throws Exception {
        Path root = java.util.Objects.requireNonNull(temporary);
        Path keyFile = root.resolve("key.txt"), cache = root.resolve("cache");
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] wrong = sign(document(), KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        assertThrows(IOException.class, () -> LauncherConfigClient.bootstrap(cache, keyFile, (path, maximum) -> path.endsWith("launcher-trust") ? trust(key) : wrong));
        assertFalse(Files.exists(keyFile));
        assertFalse(Files.exists(cache.resolve("config.json")));
        assertThrows(IOException.class, () -> LauncherConfigClient.bootstrap(cache, keyFile, (path, maximum) -> { throw new IOException("offline"); }));
        assertFalse(Files.exists(keyFile));
    }

    /// A future server key change is rejected while the user's existing binding and verified cache remain intact.
    @Test void existingKeyIsNeverSilentlyReplaced() throws Exception {
        Path root = java.util.Objects.requireNonNull(temporary);
        Path keyFile = root.resolve("key.txt"), cache = root.resolve("cache");
        KeyPair original = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] originalEnvelope = sign(document(), original);
        LauncherConfigClient.bootstrap(cache, keyFile, (path, maximum) -> path.endsWith("launcher-trust") ? trust(original) : originalEnvelope);
        String pinned = Files.readString(keyFile);
        byte[] replacement = sign(document(), KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        assertThrows(IOException.class, () -> LauncherConfigClient.bootstrap(cache, keyFile, (path, maximum) -> replacement));
        assertEquals(pinned, Files.readString(keyFile));
        assertArrayEquals(originalEnvelope, Files.readAllBytes(cache.resolve("config.json")));
    }
    @Test void selectsChineseAndEnglishFromTheSameSignedRelease() throws Exception {
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        JsonObject doc = document();
        JsonObject config = doc.getAsJsonObject("config");
        config.addProperty("title_html", "<p>中文标题</p>");
        config.addProperty("title_html_en", "<p>English title</p>");
        String publicKey = Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
        JsonObject verified = LauncherConfigClient.verify(sign(doc, key), publicKey, 0).getAsJsonObject("config");
        for (String language : new String[]{"zh-CN", "zh-TW", "en-US", "ja-JP"}) {
            boolean chinese = org.jackhuang.hmcl.util.i18n.LocaleUtils.isChinese(java.util.Locale.forLanguageTag(language));
            assertEquals(language.startsWith("zh") ? "<p>中文标题</p>" : "<p>English title</p>",
                    LauncherConfigClient.localizedHtml(verified, "title_html", chinese));
        }
        verified.remove("title_html_en");
        assertEquals("<p>中文标题</p>", LauncherConfigClient.localizedHtml(verified, "title_html", false));
    }

    /// Creates a fresh allowlisted configuration, without a command or arbitrary URL field.
    private JsonObject document() {
        return JsonParser.parseString("{\"schema\":1,\"revision\":2,\"config\":{\"title\":\"\",\"subtitle\":\"\",\"title_color\":\"\",\"title_size\":0,\"announcement_enabled\":false,\"announcement_title\":\"公告\",\"announcement_body\":\"第一行\\n第二行\",\"server_address\":\"play.example.com:25565\",\"memory_mb\":0,\"font\":null,\"background\":null}}").getAsJsonObject();
    }

    /// Signs the exact payload bytes, matching the Python service's base64 envelope.
    private byte[] sign(JsonObject document, KeyPair key) throws Exception {
        byte[] payload = document.toString().getBytes(StandardCharsets.UTF_8);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key.getPrivate());
        signature.update(payload);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("payload", Base64.getEncoder().encodeToString(payload));
        envelope.addProperty("signature", Base64.getEncoder().encodeToString(signature.sign()));
        return envelope.toString().getBytes(StandardCharsets.UTF_8);
    }

    /// Valid signatures work; replay and keys from another administrator do not.
    @Test void signatureKeyAndRevision() throws Exception {
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
        byte[] envelope = sign(document(), key);
        assertEquals(2, LauncherConfigClient.verify(envelope, publicKey, 2).get("revision").getAsInt());
        assertThrows(IOException.class, () -> LauncherConfigClient.verify(envelope, publicKey, 3));
        String other = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
        assertThrows(IOException.class, () -> LauncherConfigClient.verify(envelope, other, 0));
        JsonObject changed = JsonParser.parseString(new String(envelope, StandardCharsets.UTF_8)).getAsJsonObject();
        changed.addProperty("payload", Base64.getEncoder().encodeToString(document().toString().replace("公告", "篡改").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> LauncherConfigClient.verify(changed.toString().getBytes(StandardCharsets.UTF_8), publicKey, 0));
    }

    /// Even correctly signed commands, markup and invalid presentation values are rejected.
    @Test void allowlistAndValueValidation() throws Exception {
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
        for (String change : new String[]{"{\"command\":\"whoami\"}", "{\"title\":\"<script>bad</script>\"}", "{\"title_color\":\"red; url(file:///x)\"}", "{\"title_size\":25}", "{\"memory_mb\":true}", "{\"memory_mb\":512.5}", "{\"server_address\":\"https://evil.example\"}", "{\"server_address\":\"localhost:65536\"}", "{\"font\":{\"url\":\"file:///x\"}}"}) {
            JsonObject document = document();
            JsonParser.parseString(change).getAsJsonObject().entrySet().forEach(entry -> document.getAsJsonObject("config").add(entry.getKey(), entry.getValue()));
            byte[] envelope = sign(document, key);
            assertThrows(IOException.class, () -> LauncherConfigClient.verify(envelope, publicKey, 0), change);
        }
    }

    /// Resource hash and decoder input guards run before a local file is used.
    @Test void resourceHashAndFontBounds() throws Exception {
        byte[] font = new byte[28];
        font[1] = 1; font[5] = 1; font[23] = 28;
        JsonObject asset = new JsonObject();
        asset.addProperty("size", font.length);
        asset.addProperty("extension", "ttf");
        asset.addProperty("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(font)));
        LauncherConfigClient.checkAsset(font, asset, "font");
        font[27] = 1;
        assertThrows(IOException.class, () -> LauncherConfigClient.checkAsset(font, asset, "font"));
        asset.addProperty("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(font)));
        assertThrows(IOException.class, () -> LauncherConfigClient.checkAsset(font, asset, "font"));
    }

    /// Bounded deterministic malformed-input sampling; not an exhaustive fuzzing campaign.
    @Test void malformedEnvelopeProbe() {
        Random random = new Random(42);
        for (int i = 0; i < 300; i++) {
            byte[] bytes = new byte[random.nextInt(512)];
            random.nextBytes(bytes);
            assertThrows(IOException.class, () -> LauncherConfigClient.verify(bytes, "invalid-key", 0));
        }
        assertThrows(IOException.class, () -> LauncherConfigClient.verify(new byte[65537], "invalid", 0));
    }

    /// Python-generated fixture confirms cross-language Ed25519 and UTF-8 wire compatibility.
    @Test void pythonSignedFixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/launcher-config-python.json")) {
            assertNotNull(input);
            JsonObject fixture = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject document = LauncherConfigClient.verify(fixture.getAsJsonObject("envelope").toString().getBytes(StandardCharsets.UTF_8), fixture.get("public_key").getAsString(), 0);
            assertEquals("来自 Python 的公告", document.getAsJsonObject("config").get("announcement_body").getAsString());
        }
    }
}
