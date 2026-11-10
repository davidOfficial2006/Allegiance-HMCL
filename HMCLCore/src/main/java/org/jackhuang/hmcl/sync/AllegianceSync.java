// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.sync;

import com.google.gson.*;
import org.jackhuang.hmcl.util.function.ExceptionalFunction;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// Synchronizes only administrator-published files inside one verified DeceasedCraft instance.
/// No account tokens or administrator keys are used. JARs are inspected, never executed.
@NotNullByDefault
public final class AllegianceSync {
    /// Production origin; override with a JVM property when building another deployment.
    public static final String DEFAULT_ENDPOINT = "https://sync.decreasedcraftallegiance.com";
    /// Maximum size of one downloaded file.
    private static final long MAX_FILE = 256L * 1024 * 1024;
    /// Maximum size of JSON and JAR metadata.
    private static final int MAX_JSON = 8 * 1024 * 1024;
    /// Base-version clues are anchored to the pack name, not arbitrary numbers in paths.
    private static final Pattern PACK = Pattern.compile("(?i)^DeceasedCraft_CB[_-](\\d+\\.\\d+\\.\\d+)(?:-natives)?$");
    /// Serializes local state and journal files.
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    /// Actual selected HMCL run directory.
    private final Path root;
    /// Private staging and backup directory outside mods.
    private final Path state;
    /// HTTPS origin of the publication service.
    private final URI endpoint;
    /// Reports progress to the launch task.
    private final Consumer<String> progress;
    /// Asks the user before continuing with known warning-only conflicts.
    private final Predicate<String> confirm;

    /// Shares cancellation state with HMCL without interrupting the JavaFX thread.
    private final BooleanSupplier cancelled;

    /// HTTPS connector, replaceable only by package-local contract tests.
    private final ExceptionalFunction<URI, HttpURLConnection, IOException> connector;
    /// Per-scan identity cache; avoids reopening the large DCTweaks archive for every managed Mod.
    private final Map<Path, Set<String>> inventory = new HashMap<>();

    /// Captures a real instance directory and callbacks; does not perform network operations.
    public AllegianceSync(Path directory, URI endpoint, Consumer<String> progress, Predicate<String> confirm) throws IOException {
        this(directory, endpoint, progress, confirm, () -> false);
    }

    /// Captures the HMCL cancellation flag in addition to filesystem and UI callbacks.
    public AllegianceSync(Path directory, URI endpoint, Consumer<String> progress, Predicate<String> confirm, BooleanSupplier cancelled) throws IOException {
        this(directory, endpoint, progress, confirm, cancelled, AllegianceSync::connect);
    }

    /// Allows loopback transport in package-local tests without weakening production HTTPS validation.
    AllegianceSync(Path directory, URI endpoint, Consumer<String> progress, Predicate<String> confirm, BooleanSupplier cancelled,
                  ExceptionalFunction<URI, HttpURLConnection, IOException> connector) throws IOException {
        this.connector = connector;
        this.cancelled = cancelled;
        this.root = directory.toRealPath();
        this.state = root.resolve(".allegiance-sync");
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/")))
            throw new IOException("更新站必须是 HTTPS 根地址");
        this.endpoint = endpoint;
        this.progress = progress;
        this.confirm = confirm;
    }

    /// A downloaded or generated file and the exact existing paths it replaces.
    @NotNullByDefault
    private record Change(String relative, Path staged, String sha256, @Unmodifiable List<String> previous) { }

    /// Indicates an unrecognized or incomplete pack, which may launch without synchronization.
    @NotNullByDefault
    public static final class UnrecognizedPackException extends IOException {
        /// Keeps the identification reason separate from download, integrity, and filesystem failures.
        private UnrecognizedPackException(String reason) { super(reason); }
    }

    /// Skips only an unrecognized pack; recognized-pack download and integrity failures still propagate.
    public boolean synchronizeIfRecognized(Consumer<String> unavailable) throws IOException {
        try {
            synchronize();
            return true;
        } catch (UnrecognizedPackException e) {
            unavailable.accept("当前整合包无法同步，请检查所选游戏目录和整合包版本。\n\n"
                    + e.getMessage() + "\n\n本次跳过客户端同步，继续启动游戏。");
            return false;
        }
    }

    /// Identifies the pack, fetches both published modules, checks Mods first, then commits patches.
    public void synchronize() throws IOException {
        checkInterrupted();
        progress.accept("识别整合包目录");
        String base = identify(root);
        safe(".allegiance-sync/lock");
        Files.createDirectories(state);
        try (FileChannel channel = FileChannel.open(safe(".allegiance-sync/lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            final @Nullable FileLock acquired;
            try {
                acquired = channel.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException e) {
                throw new IOException("此客户端正在同步，请等待另一个启动任务结束", e);
            }
            if (acquired == null) throw new IOException("此客户端正在同步，请等待另一个启动任务结束");
            try (FileLock ignored = acquired) {
                recover();
                inventory.clear();
                checkInterrupted();
                progress.accept("读取服务器已发布的版本（客户端 " + base + "）");
                List<JsonObject> manifests = new ArrayList<>();
                for (String module : List.of("base", "patch")) {
                    @Nullable JsonObject manifest = manifest(base, module);
                    if (manifest != null) manifests.add(manifest);
                }
                if (manifests.isEmpty()) throw new IOException("服务器没有发布适配客户端 " + base + " 的清单，请联系管理者");
                LinkedHashMap<String, JsonObject> mods = new LinkedHashMap<>();
                LinkedHashMap<String, JsonObject> patches = new LinkedHashMap<>();
                for (JsonObject manifest : manifests) {
                    for (JsonElement element : manifest.getAsJsonArray("files")) {
                        JsonObject item = element.getAsJsonObject();
                        String path = text(item, "path");
                        safe(path);
                        if (path.startsWith("resourcepacks/")) continue;
                        if (path.startsWith("mods/")) {
                            String id = optional(item, "mod_id");
                            mods.put(id.isEmpty() || id.equals("未知") ? "path:" + path : "mod:" + id, item);
                        }
                        else patches.put(path, item);
                    }
                    if (manifest.has("managed_mods")) {
                        for (var entry : manifest.getAsJsonObject("managed_mods").entrySet()) {
                            JsonObject item = entry.getValue().getAsJsonObject().deepCopy();
                            item.addProperty("path", "mods/" + text(item, "filename"));
                            String id = optional(item, "mod_id");
                            mods.put(id.isEmpty() ? "cf:" + entry.getKey() : "mod:" + id, item);
                        }
                    }
                }
                Path staging = safe(".allegiance-sync/staging-" + UUID.randomUUID());
                Files.createDirectory(staging);
                List<Change> changes = new ArrayList<>();
                List<String> warnings = new ArrayList<>();
                try {
                    progress.accept("校验服务器要求的 Mod");
                    for (JsonObject mod : mods.values()) plan(mod, true, staging, changes, warnings);
                    progress.accept("校验菜单、皮肤及客户端补丁");
                    for (JsonObject patch : patches.values()) plan(patch, false, staging, changes, warnings);
                    checkInterrupted();
                    if (!warnings.isEmpty() && !confirm.test(String.join("\n", warnings) + "\n\n以上冲突文件不会删除。仍然继续启动？"))
                        throw new IOException("已取消启动；冲突文件未修改");
                    ensureUnique(changes);
                    commit(changes);
                    JsonObject receipt = new JsonObject();
                    receipt.addProperty("base_version", base);
                    receipt.add("releases", JSON.toJsonTree(manifests.stream().map(m -> text(m, "id")).toList()));
                    receipt.addProperty("checked_at", System.currentTimeMillis());
                    write(safe(".allegiance-sync/last-sync.json"), receipt);
                    progress.accept("客户端同步检查完成");
                } finally {
                    try (var files = Files.list(staging)) {
                        for (Path file : files.toList()) Files.deleteIfExists(file);
                    }
                    Files.deleteIfExists(staging);
                }
            }
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException e) {
            throw new IOException("服务器更新清单格式不正确，未允许游戏启动", e);
        }
    }

    /// Requires a real Mods directory, a DCTweaks JAR identity, and an unambiguous pack-version clue.
    public static String identify(Path directory) throws IOException {
        Path mods = directory.resolve("mods");
        if (!Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(mods))
            throw new UnrecognizedPackException("所选游戏目录尚无真实的 mods 文件夹");
        Set<String> versions = new HashSet<>();
        var folder = PACK.matcher(directory.getFileName().toString());
        if (folder.matches()) versions.add(folder.group(1));
        try (var children = Files.list(directory)) {
            for (Path child : children.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String name = child.getFileName().toString();
                var matcher = PACK.matcher(name);
                if (name.endsWith("-natives") && matcher.matches()) versions.add(matcher.group(1));
            }
        }
        boolean tweaks = false;
        try (var files = Files.list(mods)) {
            for (Path file : files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                if (Files.isSymbolicLink(file)) throw new IOException("mods 中存在符号链接，请先改为真实文件：" + file.getFileName());
                if (file.getFileName().toString().toLowerCase(Locale.ROOT).contains("dctweaks") && jarIds(file).stream().anyMatch(AllegianceSync::protectedMod))
                    tweaks = true;
            }
        }
        if (!tweaks || versions.size() != 1)
            throw new UnrecognizedPackException("无法确认 DeceasedCraft 客户端及基础版本，需要 DCTweaks 和唯一的 DeceasedCraft_CB_版本号目录线索");
        return versions.iterator().next();
    }

    /// Returns an optional published module; all errors other than a missing module block launch.
    private @Nullable JsonObject manifest(String base, String module) throws IOException {
        URI uri = endpoint.resolve("/api/public/manifest?base_version=" + URLEncoder.encode(base, StandardCharsets.UTF_8) + "&module=" + module);
        try {
            JsonObject result = JsonParser.parseString(new String(read(uri, MAX_JSON), StandardCharsets.UTF_8)).getAsJsonObject();
            if (result.get("schema_version").getAsInt() != 1 || !base.equals(text(result, "base_version"))
                    || !module.equals(text(result, "module")) || !result.get("files").isJsonArray()
                    || result.getAsJsonArray("files").size() > 20000)
                throw new IOException("更新清单的版本、模块或文件列表无效");
            return result;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    /// Builds a precise change list; download and integrity checks finish before any game file moves.
    private void plan(JsonObject item, boolean mod, Path staging, List<Change> changes, List<String> warnings) throws IOException {
        checkInterrupted();
        String relative = text(item, "path");
        Path target = safe(relative);
        String mode = optional(item, "mode");
        if (mod) {
            if (!relative.matches("mods/[^/]+\\.jar")) throw new IOException("Mod 路径无效：" + relative);
            if (protectedMod(relative) || protectedMod(optional(item, "mod_id"))) return;
        } else if (!(relative.startsWith("config/") || relative.startsWith("CustomSkinLoader/") || relative.startsWith("kubejs/"))) {
            throw new IOException("清单路径不在客户端补丁范围：" + relative);
        }
        if ("check".equals(mode)) {
            List<Path> present = mod ? matching(optional(item, "mod_id"), target) : List.of(target);
            boolean required = item.has("required") && item.get("required").getAsBoolean();
            if (!required) {
                if (present.stream().anyMatch(Files::exists)) warnings.add("可能冲突：" + relative);
            } else {
                boolean ok = false;
                for (Path path : present) if (Files.isRegularFile(path) && matches(path, item)) ok = true;
                if (!ok || present.size() != 1) throw new IOException("必需 Mod 缺失或版本不符：" + relative + "。管理者当前仅发布校验要求，尚未提供可修复下载源");
            }
            return;
        }
        if ("delete".equals(optional(item, "operation"))) {
            if (Files.exists(target)) warnings.add("服务器建议移除，需手动处理：" + relative);
            return;
        }
        Path staged = staging.resolve(UUID.randomUUID().toString());
        if ("properties".equals(mode)) {
            if (!relative.equals("config/modernfix-mixins.properties")) throw new IOException("字段补丁只允许 ModernFix 配置");
            Properties properties = new Properties();
            if (Files.exists(target)) {
                try (Reader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) { properties.load(reader); }
            }
            boolean changed = false;
            JsonObject fields = item.getAsJsonObject("set");
            for (var entry : fields.entrySet()) {
                if (!Set.of("mixin.perf.dynamic_languages", "mixin.perf.dynamic_resources").contains(entry.getKey())
                        || !entry.getValue().getAsString().equals("false")) throw new IOException("ModernFix 字段补丁超出范围");
                changed |= !"false".equals(properties.setProperty(entry.getKey(), "false"));
            }
            if (!changed) return;
            try (Writer writer = Files.newBufferedWriter(staged, StandardCharsets.UTF_8)) { properties.store(writer, "Allegiance ModernFix patch"); }
        } else {
            if (!(mode.isEmpty() || "replace".equals(mode))) throw new IOException("不支持的同步方式：" + mode);
            if (Files.isRegularFile(target) && matches(target, item)) {
                if (mod) {
                    Set<String> ids = jarIds(target);
                    for (String id : ids) if (protectedMod(id)) return;
                    List<Path> duplicates = matchingIds(ids, target);
                    if (duplicates.size() > 1) {
                        Files.copy(target, staged);
                        addChange(relative, staged, duplicates, changes);
                    }
                }
                return;
            }
            progress.accept("下载并校验 " + relative);
            download(item, staged);
            if (mod) {
                Set<String> ids = jarIds(staged);
                if (ids.isEmpty()) throw new IOException("下载内容没有有效 Mod 声明：" + relative);
                if (ids.stream().anyMatch(AllegianceSync::protectedMod)) throw new IOException("DCTweaks 不允许同步");
                String expected = optional(item, "mod_id");
                if (!expected.isEmpty() && !expected.equals("未知") && !ids.contains(expected)) throw new IOException("下载 JAR 的 Mod ID 与清单不符：" + relative);
                List<Path> previous = matchingIds(ids, target);
                addChange(relative, staged, previous, changes);
                return;
            }
        }
        addChange(relative, staged, Files.exists(target) ? List.of(target) : List.of(), changes);
    }

    /// Converts existing paths to validated relative paths and records the staged content hash.
    private void addChange(String relative, Path staged, List<Path> previous, List<Change> changes) throws IOException {
        changes.add(new Change(relative, staged, digest(staged, "SHA-256"),
                previous.stream().map(root::relativize).map(p -> p.toString().replace('\\', '/')).toList()));
    }

    /// Finds only active top-level JARs declaring the exact managed Mod identity.
    private List<Path> matching(String id, Path target) throws IOException {
        return matchingIds(id.isEmpty() || id.equals("未知") ? Set.of() : Set.of(id), target);
    }

    /// Refuses path collisions with unrelated or protected JARs rather than overwriting them.
    private List<Path> matchingIds(Set<String> ids, Path target) throws IOException {
        List<Path> matches = new ArrayList<>();
        try (var files = Files.list(safe("mods"))) {
            for (Path path : files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                safe(root.relativize(path).toString().replace('\\', '/'));
                @Nullable Set<String> cached = inventory.get(path);
                Set<String> local = cached != null ? cached : jarIds(path);
                inventory.put(path, local);
                boolean same = !Collections.disjoint(ids, local);
                if (path.equals(target) && !same && !ids.isEmpty()) throw new IOException("目标文件名被其他 Mod 占用：" + target.getFileName());
                if (same || path.equals(target)) {
                    if (protectedMod(path.getFileName().toString()) || local.stream().anyMatch(AllegianceSync::protectedMod))
                        throw new IOException("禁止替换 DCTweaks：" + path.getFileName());
                    matches.add(path);
                }
            }
        }
        return matches;
    }

    /// Validates declared content, including same-version JARs with different bytes.
    private boolean matches(Path path, JsonObject item) throws IOException {
        String sha256 = optional(item, "sha256");
        if (sha256.matches("(?i)[a-f0-9]{64}")) return sha256.equalsIgnoreCase(digest(path, "SHA-256"));
        @Nullable JsonObject hashes = item.has("hashes") ? item.getAsJsonObject("hashes") : null;
        String sha1 = hashes == null ? "" : optional(hashes, "sha1");
        if (sha1.matches("(?i)[a-f0-9]{40}")) return sha1.equalsIgnoreCase(digest(path, "SHA-1"));
        throw new IOException("发布文件缺少有效内容哈希，拒绝按文件名猜版本");
    }

    /// Tries administrator-provided routes, then the other official Forge CDN hostname.
    private void download(JsonObject item, Path target) throws IOException {
        long size = item.get("size").getAsLong();
        if (size <= 0 || size > MAX_FILE) throw new IOException("发布文件大小无效");
        List<URI> routes = new ArrayList<>();
        URI first = endpoint.resolve(text(item, "url"));
        routes.add(first);
        if (item.has("mirrors")) for (JsonElement mirror : item.getAsJsonArray("mirrors")) {
            if (routes.size() >= 5) break;
            routes.add(endpoint.resolve(mirror.getAsString()));
        }
        if ("edge.forgecdn.net".equals(first.getHost()) || "mediafilez.forgecdn.net".equals(first.getHost())) {
            String alternate = first.getHost().equals("edge.forgecdn.net") ? "mediafilez.forgecdn.net" : "edge.forgecdn.net";
            routes.add(URI.create(first.toString().replace(first.getHost(), alternate)));
        }
        @Nullable IOException failure = null;
        for (URI route : routes) {
            try {
                if (!"https".equalsIgnoreCase(route.getScheme()) || route.getHost() == null || route.getUserInfo() != null)
                    throw new IOException("下载只允许 HTTPS 地址");
                copy(route, target, size);
                if (Files.size(target) != size || !matches(target, item)) throw new IOException("文件大小或内容哈希不符");
                return;
            } catch (IOException e) {
                checkInterrupted();
                failure = e;
                Files.deleteIfExists(target);
            }
        }
        throw new IOException("所有下载路线均失败：" + text(item, "path"), failure);
    }

    /// Rejects overlapping module entries before creating a transaction.
    private static void ensureUnique(List<Change> changes) throws IOException {
        Set<String> occupied = new HashSet<>();
        for (Change change : changes) {
            Set<String> paths = new HashSet<>(change.previous());
            paths.add(change.relative());
            for (String path : paths) if (!occupied.add(path.toLowerCase(Locale.ROOT)))
                throw new IOException("发布模块存在重复管理的文件：" + path + "，请管理者统一目标版本");
        }
    }

    /// Journals every old file before moving it. Failed or interrupted transactions restore on next launch.
    private void commit(List<Change> changes) throws IOException {
        if (changes.isEmpty()) return;
        checkInterrupted();
        progress.accept("备份旧文件并安装已校验更新");
        String transaction = UUID.randomUUID().toString();
        Path backup = safe(".allegiance-sync/backups/" + transaction);
        Files.createDirectories(backup);
        JsonObject journal = new JsonObject();
        journal.addProperty("status", "pending");
        JsonArray entries = new JsonArray();
        for (Change change : changes) {
            JsonObject entry = new JsonObject();
            entry.addProperty("path", change.relative());
            entry.addProperty("installed_sha256", change.sha256());
            JsonArray previous = new JsonArray();
            for (String old : change.previous()) {
                JsonObject file = new JsonObject();
                file.addProperty("path", old);
                file.addProperty("sha256", digest(safe(old), "SHA-256"));
                previous.add(file);
            }
            entry.add("previous", previous);
            entries.add(entry);
        }
        journal.add("entries", entries);
        write(backup.resolve("journal.json"), journal);
        try {
            for (Change change : changes) {
                checkInterrupted();
                for (String previous : change.previous()) {
                    Path dest = safe(".allegiance-sync/backups/" + transaction + "/files/" + previous);
                    Files.createDirectories(dest.getParent());
                    Files.move(safe(previous), dest);
                }
                Path dest = safe(change.relative());
                Files.createDirectories(dest.getParent());
                Files.move(change.staged(), dest);
            }
            journal.addProperty("status", "complete");
            write(backup.resolve("journal.json"), journal);
        } catch (IOException e) {
            try { restoreTransaction(backup, journal, ""); }
            catch (IOException restoreError) { e.addSuppressed(restoreError); }
            throw new IOException("更新未完成，已尝试恢复；重新启动会再次检查恢复记录", e);
        }
    }

    /// Recovers unfinished transactions before reading a new release.
    private void recover() throws IOException {
        Path backups = safe(".allegiance-sync/backups");
        if (!Files.isDirectory(backups)) return;
        try (var directories = Files.list(backups)) {
            for (Path backup : directories.toList()) {
                Path journalPath = safe(root.relativize(backup.resolve("journal.json")).toString().replace('\\', '/'));
                if (!Files.isRegularFile(journalPath)) continue;
                JsonObject journal = parse(journalPath);
                if ("pending".equals(text(journal, "status"))) restoreTransaction(backup, journal, "");
            }
        }
    }

    /// A single managed JAR's immutable recovery choice.
    @NotNullByDefault
    public record Backup(String transaction, String path, String description) { }

    /// Lists completed per-JAR backups without modifying the instance or contacting the update service.
    public @Unmodifiable List<Backup> backups() throws IOException {
        Path folder = safe(".allegiance-sync/backups");
        if (!Files.isDirectory(folder)) return List.of();
        List<Backup> result = new ArrayList<>();
        try (var dirs = Files.list(folder)) {
            for (Path dir : dirs.sorted(Comparator.<Path>comparingLong(p -> p.toFile().lastModified()).reversed()).toList()) {
                String transaction = dir.getFileName().toString();
                if (!transaction.matches("[a-f0-9-]{36}")) continue;
                Path journalPath = safe(".allegiance-sync/backups/" + transaction + "/journal.json");
                if (!Files.isRegularFile(journalPath)) continue;
                JsonObject journal = parse(journalPath);
                if (!text(journal, "status").equals("complete")) continue;
                String date = Files.getLastModifiedTime(journalPath).toInstant().atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
                for (JsonElement element : journal.getAsJsonArray("entries")) {
                    JsonObject entry = element.getAsJsonObject();
                    String path = text(entry, "path");
                    if (!path.startsWith("mods/") || protectedMod(path)) continue;
                    List<String> old = new ArrayList<>();
                    for (JsonElement previous : entry.getAsJsonArray("previous")) old.add(text(previous.getAsJsonObject(), "path"));
                    // Installing a previously absent JAR has no old version to restore.
                    if (!old.isEmpty()) result.add(new Backup(transaction, path,
                            date + " · " + path + " ← " + String.join(", ", old)));
                }
            }
        }
        return List.copyOf(result);
    }

    /// Restores a single changed file (including its old JAR filename) while keeping backup copies.
    /// The next ordinary launch will again enforce the server's active release.
    public void restore(String transaction, String relative) throws IOException {
        if (!transaction.matches("[a-f0-9-]{36}") || !relative.startsWith("mods/")) throw new IOException("回档记录或 Mod 路径无效");
        safe(relative);
        try (FileChannel channel = FileChannel.open(safe(".allegiance-sync/lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            Path backup = safe(".allegiance-sync/backups/" + transaction);
            restoreTransaction(backup, parse(backup.resolve("journal.json")), relative);
        }
    }

    /// Restores only unchanged installed files; user edits after an update are never overwritten silently.
    private void restoreTransaction(Path backup, JsonObject journal, String only) throws IOException {
        JsonArray entries = journal.getAsJsonArray("entries");
        boolean found = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            JsonObject entry = entries.get(i).getAsJsonObject();
            String relative = text(entry, "path");
            if (!only.isEmpty() && !only.equals(relative)) continue;
            found = true;
            Path current = safe(relative);
            JsonArray previous = entry.getAsJsonArray("previous");
            if (Files.exists(current)) {
                String actual = digest(current, "SHA-256");
                boolean original = false;
                for (JsonElement element : previous) {
                    JsonObject file = element.getAsJsonObject();
                    original |= relative.equals(text(file, "path")) && actual.equals(text(file, "sha256"));
                }
                if (actual.equals(text(entry, "installed_sha256")) && !original) Files.delete(current);
                else if (!original) throw new IOException("更新后文件已被其他程序修改，请手动处理：" + relative);
            }
            for (JsonElement element : previous) {
                JsonObject file = element.getAsJsonObject();
                String old = text(file, "path");
                Path saved = safe(root.relativize(backup.resolve("files").resolve(old)).toString().replace('\\', '/'));
                Path dest = safe(old);
                if (Files.exists(saved)) {
                    String expected = text(file, "sha256");
                    if (!digest(saved, "SHA-256").equals(expected)) throw new IOException("备份内容已改变：" + old);
                    if (Files.exists(dest)) {
                        if (!digest(dest, "SHA-256").equals(expected)) throw new IOException("回档目标已被其他文件占用：" + old);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(saved, dest);
                    }
                } else if (!Files.exists(dest) || !digest(dest, "SHA-256").equals(text(file, "sha256"))) {
                    throw new IOException("旧文件及备份均不完整：" + old);
                }
            }
        }
        if (!found) throw new IOException("备份中没有此 Mod 的回档记录");
        if (only.isEmpty()) {
            journal.addProperty("status", "restored");
            write(backup.resolve("journal.json"), journal);
        }
    }

    /// Reads top-level Forge/NeoForge/Fabric Mod IDs, never embedded executable classes.
    private static @Unmodifiable Set<String> jarIds(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Mod 不是普通文件：" + path.getFileName());
        Set<String> result = new HashSet<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            @Nullable ZipEntry fabric = zip.getEntry("fabric.mod.json");
            if (fabric != null) result.add(text(JsonParser.parseString(zipText(zip, fabric)).getAsJsonObject(), "id"));
            for (String name : List.of("META-INF/mods.toml", "META-INF/neoforge.mods.toml")) {
                @Nullable ZipEntry entry = zip.getEntry(name);
                if (entry == null) continue;
                TomlParseResult toml = Toml.parse(zipText(zip, entry));
                @Nullable TomlArray mods = toml.getArray("mods");
                if (mods == null || toml.hasErrors()) continue;
                for (int i = 0; i < mods.size(); i++) {
                    TomlTable table = mods.getTable(i);
                    @Nullable String id = table.getString("modId");
                    if (id != null) result.add(id);
                }
            }
        } catch (java.util.zip.ZipException | IllegalArgumentException | IllegalStateException e) {
            return Set.of();
        }
        return Set.copyOf(result);
    }

    /// Bounds uncompressed metadata size to avoid ZIP bombs.
    private static String zipText(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry.getSize() < 0 || entry.getSize() > 1024 * 1024) throw new IOException("Mod 元数据过大");
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] data = input.readNBytes(1024 * 1024 + 1);
            if (data.length > 1024 * 1024) throw new IOException("Mod 元数据过大");
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    /// Resolves relative paths and rejects traversal, platform escapes, and symbolic-link ancestors.
    private Path safe(String relative) throws IOException {
        if (relative.isEmpty() || relative.contains("\\") || relative.contains(":") || relative.startsWith("/")
                || Arrays.stream(relative.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")))
            throw new IOException("更新路径无效：" + relative);
        Path dest = root.resolve(relative).normalize();
        if (!dest.startsWith(root)) throw new IOException("更新路径越界");
        Path checked = root;
        for (Path part : root.relativize(dest)) {
            checked = checked.resolve(part);
            if (Files.isSymbolicLink(checked)) throw new IOException("更新路径含符号链接：" + relative);
            if (Files.exists(checked) && !checked.toRealPath().equals(checked))
                throw new IOException("更新路径含目录重定向：" + relative);
        }
        return dest;
    }

    /// Keeps DCTweaks outside all automatic replacement, regardless of version or case.
    private static boolean protectedMod(String value) {
        return value.toLowerCase(Locale.ROOT).contains("dctweaks") || value.equalsIgnoreCase("deceasedcraft");
    }

    /// Computes streamed digests without loading whole JARs into memory.
    private String digest(Path file, String algorithm) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance(algorithm);
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    /// Opens HTTPS only, with finite timeouts and a bounded redirect chain.
    private static HttpURLConnection connect(URI uri) throws IOException {
        for (int i = 0; i < 6; i++) {
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IOException("下载只允许 HTTPS 地址");
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "Allegiance-HMCL/1");
            int code = connection.getResponseCode();
            if (code == 404) { connection.disconnect(); throw new FileNotFoundException("未发布此文件或清单"); }
            if (Set.of(301, 302, 303, 307, 308).contains(code)) {
                @Nullable String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("下载重定向缺少地址");
                uri = uri.resolve(location);
                continue;
            }
            if (code != 200) { connection.disconnect(); throw new IOException("更新请求失败 HTTP " + code + "（" + uri.getHost() + "）"); }
            return connection;
        }
        throw new IOException("下载重定向次数过多");
    }

    /// Reads small HTTPS JSON responses with a hard uncompressed byte limit.
    private byte[] read(URI uri, int limit) throws IOException {
        HttpURLConnection connection = connector.apply(uri);
        try (InputStream input = connection.getInputStream()) {
            byte[] data = input.readNBytes(limit + 1);
            if (data.length > limit) throw new IOException("更新清单过大");
            return data;
        } finally { connection.disconnect(); }
    }

    /// Streams one file into staging, rejecting overlong responses and cancellations.
    private void copy(URI uri, Path target, long limit) throws IOException {
        HttpURLConnection connection = connector.apply(uri);
        try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[65536];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkInterrupted();
                total += count;
                if (total > limit) throw new IOException("下载超过声明大小");
                output.write(buffer, 0, count);
            }
        } finally { connection.disconnect(); }
    }

    /// Writes journal/receipt changes atomically in the same directory.
    private static void write(Path path, JsonObject value) throws IOException {
        Path temp = Files.createTempFile(path.getParent(), "sync-", ".tmp");
        try {
            Files.writeString(temp, JSON.toJson(value), StandardCharsets.UTF_8);
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    /// Reads a bounded local transaction file.
    private static JsonObject parse(Path path) throws IOException {
        if (Files.size(path) > MAX_JSON) throw new IOException("本地同步记录过大");
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /// Requires a nonempty textual manifest field.
    private static String text(JsonObject object, String name) {
        String value = optional(object, name);
        if (value.isEmpty()) throw new IllegalArgumentException("缺少字段 " + name);
        return value;
    }

    /// Treats missing or JSON-null optional fields as absent.
    private static String optional(JsonObject object, String name) {
        @Nullable JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    /// Honors launch cancellation before mutations and within all file streams.
    private void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new InterruptedIOException("已取消客户端同步");
    }

    /// Local single-JAR recovery tool, usable with the built launcher on its class path.
    public static void main(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("identify")) {
            System.out.println("识别基础版本：" + identify(Path.of(args[1]).toRealPath()));
            return;
        }
        if (args.length != 4 || !args[0].equals("restore"))
            throw new IOException("用法：restore <游戏运行目录> <备份事务编号> <mods/新文件名.jar>；先关闭游戏");
        new AllegianceSync(Path.of(args[1]), URI.create(DEFAULT_ENDPOINT), System.out::println, warning -> false).restore(args[2], args[3]);
        System.out.println("已恢复此 Mod；再次启动仍遵循服务器生效版本。长期回档请管理者发布单 Mod 回档候选。");
    }
}
