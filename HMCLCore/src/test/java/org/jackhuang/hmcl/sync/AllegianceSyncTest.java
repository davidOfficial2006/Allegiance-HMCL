// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.sync;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

/// Contract and transaction tests use loopback HTTP via the package-private test connector; production requires HTTPS.
@NotNullByDefault
class AllegianceSyncTest {
    /// A renamed HMCL import keeps its pack identity through the original CurseForge manifest.
    @Test void importedManifestIdentifiesRenamedPackAndRejectsConflicts() throws Exception {
        try (Fixture f = new Fixture("DeceasedCraft_CB")) {
            Path imported = f.root.resolve("manifest.json");
            Files.writeString(imported, "{\"manifestType\":\"minecraftModpack\",\"manifestVersion\":1,\"name\":\"DeceasedCraft_CB\",\"version\":\"5.11.9\"}");
            assertEquals("5.11.9", AllegianceSync.identify(f.root));
            assertTrue(f.sync(w -> true).synchronizeIfRecognized(w -> fail(w)));
            Files.createDirectory(f.root.resolve("DeceasedCraft_CB_5.11.19-natives"));
            assertThrows(AllegianceSync.UnrecognizedPackException.class, () -> AllegianceSync.identify(f.root));
            Files.delete(f.root.resolve("DeceasedCraft_CB_5.11.19-natives"));
            Files.writeString(imported, "{\"manifestType\":\"minecraftModpack\",\"manifestVersion\":1,\"name\":\"OtherPack\",\"version\":\"5.11.9\"}");
            assertThrows(AllegianceSync.UnrecognizedPackException.class, () -> AllegianceSync.identify(f.root));
            Files.writeString(imported, "{\"manifestType\":\"minecraftModpack\",\"manifestVersion\":1,\"name\":\"DeceasedCraft_CB\",\"version\":\"5.11.9\"}");
            Files.delete(f.root.resolve("mods/DCTweaks_5.11.9.jar"));
            assertThrows(AllegianceSync.UnrecognizedPackException.class, () -> AllegianceSync.identify(f.root));
        }
    }
    /// Temporary instance directories supplied by JUnit.
    @TempDir static @Nullable Path temp;
    /// Holds a disposable real pack and a bounded loopback publication service.
    @NotNullByDefault
    private static final class Fixture implements AutoCloseable {
        /// Instance run directory under JUnit's temporary root.
        final Path root;
        /// Loopback HTTP test server; never connects to the deployed update site.
        final HttpServer server;
        /// HTTPS origin used by the synchronizer.
        final URI origin;
        /// Controlled manifest returned by the patch endpoint.
        final JsonObject manifest = new JsonObject();
        /// Published fixture blobs, keyed by exact URL path.
        final Map<String, byte[]> blobs = new HashMap<>();
        /// Number of actual blob requests, excluding manifest checks.
        int downloads;
        /// Manifest requests prove that an unidentified pack never contacts the publication service.
        int manifestRequests;

        /// Creates a minimal recognizable pack; the DCTweaks stub contains no executable classes.
        Fixture() throws Exception {
            this("DeceasedCraft_CB_5.11.9");
        }

        /// Creates a fixture whose imported folder may not yet contain a version clue.
        Fixture(String instanceName) throws Exception {
            root = Files.createTempDirectory(Objects.requireNonNull(temp), "instance-").resolve(instanceName);
            Files.createDirectories(root.resolve("mods"));
            Files.write(root.resolve("mods/DCTweaks_5.11.9.jar"), jar("dctweaks", "old"));
            manifest.addProperty("schema_version", 1);
            manifest.addProperty("id", "release-test");
            manifest.addProperty("module", "patch");
            manifest.addProperty("base_version", "5.11.9");
            manifest.add("files", new JsonArray());
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                @Nullable byte[] body;
                if (path.equals("/api/public/manifest")) {
                    manifestRequests++;
                    body = exchange.getRequestURI().getQuery().contains("module=base") ? null : manifest.toString().getBytes(StandardCharsets.UTF_8);
                }
                else { body = blobs.get(path); downloads++; }
                exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
                if (body != null) exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            origin = URI.create("https://localhost:" + server.getAddress().getPort());
        }

        /// Publishes a file with the same shape used by the Python backend's files array.
        JsonObject publish(String path, byte[] bytes) throws Exception {
            String url = "/blob/" + UUID.randomUUID();
            blobs.put(url, bytes);
            JsonObject item = new JsonObject();
            item.addProperty("path", path);
            item.addProperty("mode", "replace");
            item.addProperty("operation", "modify");
            item.addProperty("url", url);
            item.addProperty("size", bytes.length);
            item.addProperty("sha256", hash(bytes));
            manifest.getAsJsonArray("files").add(item);
            return item;
        }

        /// Substitutes only the test transport; manifest origin and paths still use the HTTPS contract.
        AllegianceSync sync(Predicate<String> confirm) throws IOException {
            return new AllegianceSync(root, origin, message -> { }, confirm, () -> false, uri -> {
                URI local = URI.create("http://localhost:" + server.getAddress().getPort() + uri.getRawPath()
                        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
                HttpURLConnection connection = (HttpURLConnection) local.toURL().openConnection(Proxy.NO_PROXY);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                return connection;
            });
        }

        /// Lists immutable backup records without guessing timestamps.
        List<Path> journals() throws IOException {
            try (var paths = Files.walk(root.resolve(".allegiance-sync/backups"))) {
                return paths.filter(p -> p.getFileName().toString().equals("journal.json")).toList();
            }
        }

        /// Stops the loopback test server.
        @Override public void close() { server.stop(0); }
    }

    /// Builds a tiny metadata-only JAR for a top-level Forge Mod identity.
    private static byte[] jar(String id, String content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("META-INF/mods.toml"));
            zip.write(("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + id + "\"\nversion=\"1.0\"\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("fixture.txt"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /// Computes the fixture's publication hash.
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    /// Same-version different bytes replace the exact managed identity; unrelated and protected Mods survive.
    @Test void replacementBackupIdempotenceAndSingleJarRestore() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] old = jar("allegiance_kubejs", "old script");
            byte[] newer = jar("allegiance_kubejs", "new script, same version");
            Files.write(f.root.resolve("mods/renamed-old.jar"), old);
            byte[] extra = jar("client_extra", "untouched");
            Files.write(f.root.resolve("mods/client-extra.jar"), extra);
            byte[] tweaks = Files.readAllBytes(f.root.resolve("mods/DCTweaks_5.11.9.jar"));
            f.publish("mods/new.jar", newer).addProperty("mod_id", "allegiance_kubejs");
            f.sync(w -> true).synchronize();
            assertArrayEquals(newer, Files.readAllBytes(f.root.resolve("mods/new.jar")));
            assertFalse(Files.exists(f.root.resolve("mods/renamed-old.jar")));
            assertArrayEquals(extra, Files.readAllBytes(f.root.resolve("mods/client-extra.jar")));
            assertArrayEquals(tweaks, Files.readAllBytes(f.root.resolve("mods/DCTweaks_5.11.9.jar")));
            f.sync(w -> true).synchronize();
            assertEquals(1, f.downloads);
            Path journal = f.journals().get(0);
            assertEquals("mods/new.jar", f.sync(w -> true).backups().get(0).path());
            f.sync(w -> true).restore(journal.getParent().getFileName().toString(), "mods/new.jar");
            assertArrayEquals(old, Files.readAllBytes(f.root.resolve("mods/renamed-old.jar")));
            assertFalse(Files.exists(f.root.resolve("mods/new.jar")));
            assertTrue(Files.exists(journal.getParent().resolve("files/mods/renamed-old.jar")));
        }
    }

    /// Mirrors can recover a broken route; corrupt content never touches the old JAR.
    @Test void alternateRoutesAndCorruptDownload() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] old = jar("demo", "old");
            Files.write(f.root.resolve("mods/old.jar"), old);
            JsonObject item = f.publish("mods/new.jar", jar("demo", "new"));
            item.addProperty("mod_id", "demo");
            String working = item.get("url").getAsString();
            item.addProperty("url", "/missing");
            JsonArray mirrors = new JsonArray(); mirrors.add(working); item.add("mirrors", mirrors);
            f.sync(w -> true).synchronize();
            assertEquals(2, f.downloads);
            item.addProperty("sha256", "0".repeat(64));
            byte[] installed = Files.readAllBytes(f.root.resolve("mods/new.jar"));
            assertThrows(IOException.class, () -> f.sync(w -> true).synchronize());
            assertArrayEquals(installed, Files.readAllBytes(f.root.resolve("mods/new.jar")));
        }
    }

    /// Warning-only conflicts remain untouched; rejecting the dialog cancels all queued patch writes.
    @Test void conflictsWarnAndCancelWithoutDeleting() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] conflict = jar("conflict_mod", "conflict");
            Files.write(f.root.resolve("mods/conflict.jar"), conflict);
            JsonObject check = new JsonObject();
            check.addProperty("path", "mods/conflict.jar"); check.addProperty("mod_id", "conflict_mod");
            check.addProperty("mode", "check"); check.addProperty("required", false);
            f.manifest.getAsJsonArray("files").add(check);
            f.publish("config/menu.txt", "menu".getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> f.sync(w -> false).synchronize());
            assertFalse(Files.exists(f.root.resolve("config/menu.txt")));
            f.sync(w -> { assertTrue(w.contains("conflict.jar")); return true; }).synchronize();
            assertArrayEquals(conflict, Files.readAllBytes(f.root.resolve("mods/conflict.jar")));
        }
    }

    /// Checks prevent launching the wrong game or mutating traversal paths; resource packs are skipped.
    @Test void wrongPackTraversalAndResourcepackExclusion() throws Exception {
        try (Fixture f = new Fixture()) {
            Path wrong = Files.createDirectory(Objects.requireNonNull(temp).resolve("other-" + UUID.randomUUID()));
            Files.createDirectory(wrong.resolve("mods"));
            assertThrows(IOException.class, () -> AllegianceSync.identify(wrong));
            f.publish("resourcepacks/skipped.zip", new byte[]{1});
            f.sync(w -> true).synchronize();
            assertEquals(0, f.downloads);
            f.publish("config/../../escape.txt", new byte[]{1});
            assertThrows(IOException.class, () -> f.sync(w -> true).synchronize());
            assertFalse(Files.exists(f.root.getParent().resolve("escape.txt")));
        }
    }

    /// The managed_mods extension works without guessing a CurseForge project's Mod ID.
    @Test void managedModsUseDownloadedIdentityAndSha1() throws Exception {
        try (Fixture f = new Fixture()) {
            Files.write(f.root.resolve("mods/old.jar"), jar("demo", "old"));
            byte[] content = jar("demo", "new");
            JsonObject item = f.publish("mods/new.jar", content);
            f.manifest.add("files", new JsonArray());
            item.remove("sha256");
            JsonObject hashes = new JsonObject(); hashes.addProperty("sha1", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content)));
            item.add("hashes", hashes); item.addProperty("filename", "new.jar");
            JsonObject managed = new JsonObject(); managed.add("123", item); f.manifest.add("managed_mods", managed);
            f.sync(w -> true).synchronize();
            assertFalse(Files.exists(f.root.resolve("mods/old.jar")));
            assertArrayEquals(content, Files.readAllBytes(f.root.resolve("mods/new.jar")));
        }
    }

    /// A filesystem failure after a JAR replacement automatically restores the old JAR.
    @Test void partialCommitRestoresOldJar() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] old = jar("demo", "old");
            Files.write(f.root.resolve("mods/old.jar"), old);
            f.publish("mods/new.jar", jar("demo", "new")).addProperty("mod_id", "demo");
            Files.createDirectory(f.root.resolve("config"));
            Files.writeString(f.root.resolve("config/blocked"), "not a folder");
            f.publish("config/blocked/child.txt", new byte[]{1});
            assertThrows(IOException.class, () -> f.sync(w -> true).synchronize());
            assertArrayEquals(old, Files.readAllBytes(f.root.resolve("mods/old.jar")));
            assertFalse(Files.exists(f.root.resolve("mods/new.jar")));
        }
    }

    /// A protected download identity or unrelated target filename cannot be overwritten.
    @Test void protectedIdentityAndNameCollision() throws Exception {
        try (Fixture f = new Fixture()) {
            f.publish("mods/disguised.jar", jar("dctweaks", "new beta"));
            assertThrows(IOException.class, () -> f.sync(w -> true).synchronize());
            assertFalse(Files.exists(f.root.resolve("mods/disguised.jar")));
            f.manifest.add("files", new JsonArray());
            byte[] unrelated = jar("other", "other");
            Files.write(f.root.resolve("mods/new.jar"), unrelated);
            f.publish("mods/new.jar", jar("demo", "new"));
            assertThrows(IOException.class, () -> f.sync(w -> true).synchronize());
            assertArrayEquals(unrelated, Files.readAllBytes(f.root.resolve("mods/new.jar")));
        }
    }

    /// Pending journals recover even when an old and staged file have the same hash.
    @Test void recoverPendingAndDoNotOverwriteUserEdits() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] content = jar("demo", "same");
            Files.write(f.root.resolve("mods/old.jar"), content);
            f.publish("mods/new.jar", content).addProperty("mod_id", "demo");
            f.sync(w -> true).synchronize();
            Path path = f.journals().get(0);
            JsonObject journal = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            journal.addProperty("status", "pending");
            Files.writeString(path, journal.toString());
            f.manifest.add("files", new JsonArray());
            f.sync(w -> true).synchronize();
            assertArrayEquals(content, Files.readAllBytes(f.root.resolve("mods/old.jar")));
            assertFalse(Files.exists(f.root.resolve("mods/new.jar")));
        }
        try (Fixture f = new Fixture()) {
            Files.write(f.root.resolve("mods/old.jar"), jar("demo", "old"));
            f.publish("mods/new.jar", jar("demo", "new"));
            f.sync(w -> true).synchronize();
            byte[] edit = jar("demo", "user edit");
            Files.write(f.root.resolve("mods/new.jar"), edit);
            String transaction = f.journals().get(0).getParent().getFileName().toString();
            assertThrows(IOException.class, () -> f.sync(w -> true).restore(transaction, "mods/new.jar"));
            assertArrayEquals(edit, Files.readAllBytes(f.root.resolve("mods/new.jar")));
        }
    }

    /// The user's actual DCTweaks declares deceasedcraft, not dctweaks; natives alone are insufficient.
    @Test void actualPackIdentityAndAmbiguousVersions() throws Exception {
        try (Fixture f = new Fixture()) {
            Files.write(f.root.resolve("mods/DCTweaks_5.11.9.jar"), jar("deceasedcraft", "standard metadata"));
            assertEquals("5.11.9", AllegianceSync.identify(f.root));
            Files.createDirectory(f.root.resolve("DeceasedCraft_CB_5.11.19-natives"));
            assertThrows(IOException.class, () -> AllegianceSync.identify(f.root));
        }
        Path wrong = Files.createTempDirectory(Objects.requireNonNull(temp), "natives-only-");
        Files.createDirectory(wrong.resolve("mods"));
        Files.createDirectory(wrong.resolve("DeceasedCraft_CB_5.11.9-natives"));
        assertThrows(IOException.class, () -> AllegianceSync.identify(wrong));
    }

    /// Cancellation is honored before network mutations, and production origins cannot be HTTP.
    @Test void cancellationAndHttpsOrigin() throws Exception {
        try (Fixture f = new Fixture()) {
            assertThrows(IOException.class, () -> new AllegianceSync(f.root, URI.create("http://localhost"), m -> { }, w -> true));
            AllegianceSync cancelled = new AllegianceSync(f.root, f.origin, m -> { }, w -> true, () -> true);
            assertThrows(InterruptedIOException.class, cancelled::synchronize);
            assertFalse(Files.exists(f.root.resolve(".allegiance-sync/backups")));
        }
    }

    /// ModernFix changes only the approved semantic properties and skips resourcepack downloads.
    @Test void modernfixFieldsAndRequiredCheck() throws Exception {
        try (Fixture f = new Fixture()) {
            Files.createDirectory(f.root.resolve("config"));
            Files.writeString(f.root.resolve("config/modernfix-mixins.properties"), "other.setting=true\nmixin.perf.dynamic_languages=true\n");
            JsonObject patch = new JsonObject(); patch.addProperty("path", "config/modernfix-mixins.properties");
            patch.addProperty("mode", "properties");
            JsonObject fields = new JsonObject(); fields.addProperty("mixin.perf.dynamic_languages", "false"); fields.addProperty("mixin.perf.dynamic_resources", "false");
            patch.add("set", fields); f.manifest.getAsJsonArray("files").add(patch);
            f.sync(w -> true).synchronize();
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(f.root.resolve("config/modernfix-mixins.properties"))) { properties.load(reader); }
            assertEquals("false", properties.getProperty("mixin.perf.dynamic_resources"));
            assertEquals("true", properties.getProperty("other.setting"));
            JsonObject required = new JsonObject(); required.addProperty("path", "mods/missing.jar");
            required.addProperty("mod_id", "missing"); required.addProperty("mode", "check"); required.addProperty("required", true);
            required.addProperty("sha256", "0".repeat(64)); f.manifest.getAsJsonArray("files").add(required);
            IOException error = assertThrows(IOException.class, () -> f.sync(w -> true).synchronize());
            assertTrue(error.getMessage().contains("必需 Mod"));
        }
    }

    /// Recovery must not delete an untouched original when its bytes equal the planned installation.
    @Test void pendingBeforeFirstMovePreservesSameHashOriginal() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] original = jar("demo", "same bytes");
            Files.write(f.root.resolve("mods/same.jar"), original);
            Path backup = f.root.resolve(".allegiance-sync/backups/" + UUID.randomUUID());
            Files.createDirectories(backup);
            JsonObject journal = new JsonObject(); journal.addProperty("status", "pending");
            JsonObject entry = new JsonObject(); entry.addProperty("path", "mods/same.jar"); entry.addProperty("installed_sha256", hash(original));
            JsonObject old = new JsonObject(); old.addProperty("path", "mods/same.jar"); old.addProperty("sha256", hash(original));
            JsonArray previous = new JsonArray(); previous.add(old); entry.add("previous", previous);
            JsonArray entries = new JsonArray(); entries.add(entry); journal.add("entries", entries);
            Files.writeString(backup.resolve("journal.json"), journal.toString());
            f.sync(w -> true).synchronize();
            assertArrayEquals(original, Files.readAllBytes(f.root.resolve("mods/same.jar")));
        }
    }

    /// A fresh import without mods may launch; completion can populate it before a later synchronization.
    @Test void incompleteImportWarnsWithoutWritingOrRequestingUpdates() throws Exception {
        try (Fixture f = new Fixture()) {
            Files.delete(f.root.resolve("mods/DCTweaks_5.11.9.jar"));
            Files.delete(f.root.resolve("mods"));
            List<String> notices = new ArrayList<>();
            assertFalse(f.sync(w -> true).synchronizeIfRecognized(notices::add));
            assertEquals(1, notices.size());
            assertTrue(notices.get(0).contains("继续启动游戏"));
            assertEquals(0, f.manifestRequests);
            assertFalse(Files.exists(f.root.resolve(".allegiance-sync")));
            Files.createDirectory(f.root.resolve("mods"));
            Files.write(f.root.resolve("mods/DCTweaks_5.11.9.jar"), jar("deceasedcraft", "completed"));
            f.publish("config/after-completion.txt", new byte[]{1});
            assertTrue(f.sync(w -> true).synchronizeIfRecognized(notices::add));
            assertArrayEquals(new byte[]{1}, Files.readAllBytes(f.root.resolve("config/after-completion.txt")));
            assertEquals(1, notices.size());
        }
    }

    /// Missing or ambiguous version clues warn; once completion creates natives, the same import can sync.
    @Test void versionClueAppearingAfterCompletionEnablesSync() throws Exception {
        try (Fixture f = new Fixture("ImportedPack")) {
            List<String> notices = new ArrayList<>();
            assertFalse(f.sync(w -> true).synchronizeIfRecognized(notices::add));
            assertEquals(0, f.manifestRequests);
            Files.createDirectory(f.root.resolve("DeceasedCraft_CB_5.11.9-natives"));
            assertTrue(f.sync(w -> true).synchronizeIfRecognized(notices::add));
            Files.createDirectory(f.root.resolve("DeceasedCraft_CB_5.11.19-natives"));
            assertFalse(f.sync(w -> true).synchronizeIfRecognized(notices::add));
            assertEquals(2, notices.size());
        }
    }

    /// The launch exception policy must not turn a recognized-pack corrupt download into a successful skip.
    @Test void knownPackIntegrityErrorsStillFail() throws Exception {
        try (Fixture f = new Fixture()) {
            f.publish("config/bad.txt", new byte[]{1}).addProperty("sha256", "0".repeat(64));
            assertThrows(IOException.class, () -> f.sync(w -> true).synchronizeIfRecognized(m -> fail("Integrity failures are not identification notices")));
            assertFalse(Files.exists(f.root.resolve("config/bad.txt")));
        }
    }
}
