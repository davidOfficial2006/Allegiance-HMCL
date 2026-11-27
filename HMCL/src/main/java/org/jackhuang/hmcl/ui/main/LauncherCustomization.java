// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.ui.main;

import com.google.gson.JsonObject;
import com.jfoenix.controls.JFXButton;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.beans.value.ObservableBooleanValue;
import javafx.beans.value.ObservableValue;
import javafx.scene.image.Image;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.scene.paint.Paint;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.setting.BackgroundType;
import org.jackhuang.hmcl.setting.FontManager;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.sync.LauncherConfigClient;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.PromptDialogPane;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Applies signed presentation data; player preferences are changed only by an explicit restore action.
@NotNullByDefault
public final class LauncherCustomization {
    /// Active verified document, containing only allowlisted fields.
    private static final ReadOnlyObjectWrapper<@Nullable JsonObject> DOCUMENT = new ReadOnlyObjectWrapper<>();
    /// Verified, decoded server wallpaper, applied without writing personal appearance preferences.
    private static final ReadOnlyObjectWrapper<@Nullable Image> NETWORK_BACKGROUND = new ReadOnlyObjectWrapper<>();

    /// Human-readable local status without account information or private signing keys.
    private static final ReadOnlyStringWrapper STATUS = new ReadOnlyStringWrapper("等待连接服务器配置");
    /// Delivery-local key, automatically pinned after the first HTTPS connection validates a publication.
    private static final Path KEY_FILE = Metadata.CURRENT_DIRECTORY.resolve("launcher-config-public-key.txt");
    /// Verified envelope and resource cache, never a game directory.
    private static final Path CACHE = Metadata.HMCL_LOCAL_HOME.resolve("launcher-customization");
    /// Prevents duplicate background fetches from multiple home-page instances.
    private static boolean started;
    /// Prevents overlapping background refreshes and manual rebinding during a fetch.
    private static final javafx.beans.property.ReadOnlyBooleanWrapper FETCHING = new javafx.beans.property.ReadOnlyBooleanWrapper();

    /// Utility class.
    private LauncherCustomization() { }

    /// Resolves the administrator-pinned public key from the local delivery or explicit JVM override.
    private static LauncherConfigClient client() throws IOException {
        String key = System.getProperty("hmcl.launcher.config.publicKey", "").strip();
        if (key.isEmpty() && Files.isRegularFile(KEY_FILE) && Files.size(KEY_FILE) < 4096) key = Files.readString(KEY_FILE).strip();
        if (key.isEmpty()) return LauncherConfigClient.bootstrap(CACHE, KEY_FILE);
        return new LauncherConfigClient(CACHE, key);
    }

    /// Reads verified cache immediately, then fetches in the background without delaying game launch.
    private static void start() {
        if (started || FETCHING.get()) return;
        started = true;
        FETCHING.set(true);
        Task.runAsync(() -> {
            try {
            LauncherConfigClient client;
            boolean firstBinding = !Files.exists(KEY_FILE) && System.getProperty("hmcl.launcher.config.publicKey", "").isBlank();
            try { client = client(); }
            catch (IOException e) {
                LOG.warning("[Launcher configuration] 首次连接或绑定失败：" + e.getMessage());
                FXUtils.runInFX(() -> STATUS.set("配置未更新：" + e.getMessage()));
                return;
            }
            try {
                JsonObject cached = client.cached();
                FXUtils.runInFX(() -> {
                    DOCUMENT.set(cached);
                    STATUS.set("已验证：启动器配置第 " + cached.get("revision").getAsLong() + " 版");
                });
                @Nullable Image wallpaper = loadWallpaper(client, cached, firstBinding);
                FXUtils.runInFX(() -> NETWORK_BACKGROUND.set(wallpaper));
                if (firstBinding) {
                    LOG.info("[Launcher configuration] HTTPS 首次绑定完成，已应用配置第 " + cached.get("revision").getAsLong() + " 版");
                    return;
                }
            } catch (IOException ignored) { }
            try {
                JsonObject document = client.refresh();
                LOG.info("[Launcher configuration] 已应用配置第 " + document.get("revision").getAsLong() + " 版");
                FXUtils.runInFX(() -> { DOCUMENT.set(document); STATUS.set("已验证：启动器配置第 " + document.get("revision").getAsLong() + " 版"); });
                @Nullable Image wallpaper = loadWallpaper(client, document, true);
                FXUtils.runInFX(() -> NETWORK_BACKGROUND.set(wallpaper));
            } catch (IOException e) {
                LOG.warning("[Launcher configuration] " + e.getMessage());
                FXUtils.runInFX(() -> STATUS.set("配置未更新，保留现有设置：" + e.getMessage()));
            }
            } finally { FXUtils.runInFX(() -> FETCHING.set(false)); }
        }).start();
    }

    /// Installs optional title/announcement bindings while leaving the default title style untouched.
    static void attach(StackPane home, Label title, @Nullable Node originalAnnouncement) {
        VBox box = new VBox(12);
        box.setMaxWidth(570);
        box.setMaxHeight(VBox.USE_PREF_SIZE);
        box.setPickOnBounds(false);
        StackPane.setAlignment(box, Pos.TOP_LEFT);
        home.getChildren().add(box);
        Runnable render = () -> {
            @Nullable JsonObject document = DOCUMENT.get();
            box.getChildren().clear();
            title.textProperty().unbind();
            title.textFillProperty().unbind();
            title.textProperty().bind(Controllers.launcherTitleProperty());
            title.textFillProperty().bind(Themes.titleFillProperty());
            title.setStyle("");
            title.setGraphic(null);
            boolean announcement = false;
            if (document != null) {
                JsonObject config = document.getAsJsonObject("config");
                String main = config.get("title").getAsString(), subtitle = config.get("subtitle").getAsString();
                if (!main.isEmpty() || !subtitle.isEmpty()) {
                    title.textProperty().unbind();
                    title.setText((main.isEmpty() ? Controllers.launcherTitleProperty().get() : main) + (subtitle.isEmpty() ? "" : " · " + subtitle));
                }
                String color = config.get("title_color").getAsString();
                if (!color.isEmpty()) { title.textFillProperty().unbind(); title.setTextFill(Color.web(color)); }
                int size = config.get("title_size").getAsInt();
                if (size > 0) title.setStyle("-fx-font-size: " + size + "px;");
                String titleHtml = LauncherConfigClient.localizedHtml(config, "title_html", I18n.isUseChinese());
                if (!titleHtml.isBlank()) {
                    TextFlow rich = richText(titleHtml, title.getText(), title.textFillProperty());
                    if (!subtitle.isEmpty()) {
                        Text suffix = new Text(" · " + subtitle);
                        suffix.fillProperty().bind(title.textFillProperty());
                        rich.getChildren().add(suffix);
                    }
                    title.textProperty().unbind();
                    title.setText("");
                    title.setGraphic(rich);
                }
                announcement = config.get("announcement_enabled").getAsBoolean();
                if (announcement) {
                    Label heading = new Label(config.get("announcement_title").getAsString());
                    heading.getStyleClass().add("title");
                    Label body = new Label(config.get("announcement_body").getAsString());
                    body.setWrapText(true);
                    String headingHtml = LauncherConfigClient.localizedHtml(config, "announcement_title_html", I18n.isUseChinese());
                    String bodyHtml = LauncherConfigClient.localizedHtml(config, "announcement_html", I18n.isUseChinese());
                    Node headingNode = headingHtml.isBlank() ? heading : richText(headingHtml, heading.getText(), heading.textFillProperty());
                    Node bodyNode = bodyHtml.isBlank() ? body : richText(bodyHtml, body.getText(), body.textFillProperty());
                    VBox card = new VBox(12, headingNode, bodyNode);
                    card.getStyleClass().addAll("card", "announcement");
                    card.setStyle("-fx-padding: 16px;");
                    box.getChildren().add(card);
                }
                String address = config.get("server_address").getAsString();
                if (!address.isEmpty()) {
                    JFXButton server = new JFXButton("服务器：" + address + "（点击复制）");
                    server.getStyleClass().add("card");
                    server.setOnAction(e -> FXUtils.copyText(address));
                    box.getChildren().add(server);
                }
            }
            if (originalAnnouncement != null) { originalAnnouncement.setVisible(!announcement); originalAnnouncement.setManaged(!announcement); }
            box.setVisible(!box.getChildren().isEmpty());
            box.setManaged(!box.getChildren().isEmpty());
        };
        DOCUMENT.addListener((observable, oldValue, newValue) -> render.run());
        render.run();
        start();
    }

    /// Observes the current verified server wallpaper for the window background layer.
    public static ObservableValue<@Nullable Image> networkBackgroundProperty() {
        return NETWORK_BACKGROUND.getReadOnlyProperty();
    }

    /// Fetches the signed image by digest and falls back to the personal background on failure.
    private static @Nullable Image loadWallpaper(LauncherConfigClient client, JsonObject document, boolean allowDownload) {
        JsonObject config = document.getAsJsonObject("config");
        if (!config.has("network_background") || config.get("network_background").isJsonNull()) return null;
        try {
            byte[] bytes = client.wallpaper(config.getAsJsonObject("network_background"), allowDownload);
            Image image = new Image(new java.io.ByteArrayInputStream(bytes));
            if (!image.isError()) return image;
        } catch (IOException | RuntimeException e) {
            LOG.warning("[Launcher configuration] 网络背景无法加载：" + e.getMessage());
        }
        return null;
    }

    /// Reads optional appearance fields from both current and older publications.
    private static String appearance(JsonObject config, String field) {
        return config.has(field) && !config.get(field).isJsonNull() ? config.get(field).getAsString() : "";
    }

    /// Converts a small HTML subset to native text nodes, with no web engine or resource loading.
    private static TextFlow richText(String html, String fallback, ObservableValue<? extends Paint> color) {
        TextFlow result = new TextFlow();
        try {
            var parser = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document = parser.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader("<body>" + html + "</body>")));
            appendRich(result, document.getDocumentElement(), color, false, false, false, 0);
        } catch (Exception e) {
            result.getChildren().clear();
            Text text = new Text(fallback);
            text.fillProperty().bind(color);
            result.getChildren().add(text);
        }
        return result;
    }

    /// Walks the allowlisted formatting tags and emits only text.
    private static void appendRich(TextFlow flow, org.w3c.dom.Node parent, ObservableValue<? extends Paint> color,
                                   boolean bold, boolean italic, boolean underline, int depth) {
        if (depth > 32) throw new IllegalArgumentException("HTML 标签嵌套过深");
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            var node = parent.getChildNodes().item(i);
            if (node instanceof org.w3c.dom.Element element) {
                String tag = element.getTagName();
                if (element.hasAttributes() || !java.util.Set.of("b", "strong", "i", "em", "u", "br", "p", "div").contains(tag))
                    throw new IllegalArgumentException("不支持的 HTML 标签或属性");
                if (tag.equals("br")) {
                    flow.getChildren().add(new Text("\n"));
                } else {
                    if ((tag.equals("p") || tag.equals("div")) && !flow.getChildren().isEmpty())
                        flow.getChildren().add(new Text("\n"));
                    appendRich(flow, node, color, bold || tag.equals("b") || tag.equals("strong"),
                            italic || tag.equals("i") || tag.equals("em"), underline || tag.equals("u"), depth + 1);
                }
            } else if (node.getNodeType() == org.w3c.dom.Node.TEXT_NODE) {
                Text text = new Text(node.getTextContent());
                text.fillProperty().bind(color);
                text.setUnderline(underline);
                text.setStyle((bold ? "-fx-font-weight: bold;" : "") + (italic ? "-fx-font-style: italic;" : ""));
                flow.getChildren().add(text);
            }
        }
    }

    /// Applies allowlisted button styles and updates the label when either configuration or the selected game changes.
    static void bindLaunchButton(JFXButton button, Label label, Label detail, ObservableBooleanValue selected) {
        Runnable render = () -> {
            @Nullable JsonObject document = DOCUMENT.get();
            JsonObject config = document == null ? new JsonObject() : document.getAsJsonObject("config");
            String text = appearance(config, "launch_text");
            String color = appearance(config, "launch_color");
            String foreground = appearance(config, "launch_text_color");
            String radius = appearance(config, "launch_radius");
            label.setText(!selected.get() || text.isBlank()
                    ? org.jackhuang.hmcl.util.i18n.I18n.i18n(selected.get() ? "instance.launch" : "instance.launch.empty")
                    : text);
            button.setStyle((color.isEmpty() ? "" : "-fx-background-color:" + color + ";")
                    + (radius.isEmpty() ? "" : "-fx-background-radius:" + radius + " 0 0 " + radius + ";"));
            String textStyle = foreground.isEmpty() ? "" : "-fx-text-fill:" + foreground + ";";
            label.setStyle("-fx-font-size:16px;" + textStyle);
            detail.setStyle("-fx-font-size:12px;" + textStyle);
        };
        DOCUMENT.addListener(ignored -> render.run());
        selected.addListener(ignored -> render.run());
        button.getProperties().put("game-selected", selected);
        render.run();
    }

    /// Adds the player-controlled restore action and explicit trust-binding entry point.
    static Node controls() {
        Label status = new Label();
        status.setWrapText(true);
        status.textProperty().bind(STATUS.getReadOnlyProperty());
        JFXButton refresh = new JFXButton("检查服务器配置");
        refresh.disableProperty().bind(FETCHING);
        refresh.setOnAction(e -> { started = false; start(); });
        JFXButton restore = new JFXButton("恢复服务器推荐设置");
        restore.disableProperty().bind(FETCHING);
        restore.setOnAction(e -> Controllers.confirm("将替换启动器字体、背景和默认游戏内存。实例自己的内存覆盖值仍保留。没有推荐字体或背景时保留现状。是否继续？", "恢复服务器推荐设置", MessageType.WARNING,
                LauncherCustomization::restore, () -> { }));
        JFXButton bind = new JFXButton("高级：手动重绑公钥");
        bind.disableProperty().bind(FETCHING);
        bind.setOnAction(e -> {
            var question = new PromptDialogPane.Builder.StringQuestion("从你自己的后台“启动器设置”页复制公钥。不要粘贴管理密钥；仅信任管理者提供的公钥。", "");
            Controllers.prompt(new PromptDialogPane.Builder("绑定后台签名公钥", (answers, handler) -> {
                try {
                    String key = java.util.Objects.requireNonNull(question.getValue()).strip();
                    if (key.length() > 256) throw new IOException("公钥过长");
                    KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key)));
                    Files.writeString(KEY_FILE, key + "\n");
                    DOCUMENT.set(null);
                    started = false;
                    start();
                    handler.resolve();
                } catch (Exception error) { handler.reject("公钥无效或无法写入交付目录"); }
            }).addQuestion(question));
        });
        return new VBox(10, new Label("服务器推荐设置（自动连接后台）"), status, refresh, restore, bind);
    }

    /// Downloads and validates resources before committing player-selected settings on the JavaFX thread.
    private static void restore() {
        @Nullable JsonObject document = DOCUMENT.get();
        if (document == null) { Controllers.dialog("当前没有通过签名验证的后台配置。请先绑定公钥并发布配置。", "无法恢复推荐设置", MessageType.INFO); return; }
        JsonObject config = document.getAsJsonObject("config");
        Task.supplyAsync(() -> {
            LauncherConfigClient client = client();
            @Nullable Path font = config.get("font").isJsonNull() ? null : client.asset(config.getAsJsonObject("font"), "font");
            @Nullable Path background = config.get("background").isJsonNull() ? null : client.asset(config.getAsJsonObject("background"), "background");
            return new Resources(font, background);
        }).whenComplete(Schedulers.javafx(), (@Nullable Resources resources, @Nullable Exception error) -> {
            if (error != null || resources == null) { Controllers.dialog("素材下载或校验失败，保留原设置。", "恢复失败", MessageType.ERROR); return; }
            @Nullable Font loaded = resources.font() == null ? null : Font.loadFont(resources.font().toUri().toString(), FontManager.DEFAULT_FONT_SIZE);
            if (resources.font() != null && loaded == null) { Controllers.dialog("字体无法加载，保留原设置。", "恢复失败", MessageType.ERROR); return; }
            if (loaded != null) {
                try { Files.copy(CACHE.resolve("config.json"), CACHE.resolve("selected-font-config.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException e) {
                    Controllers.dialog("无法保存已验证的字体记录，保留原设置。", "恢复失败", MessageType.ERROR);
                    return;
                }
                FontManager.setFontFamily(loaded.getFamily());
            }
            if (resources.background() != null) {
                SettingsManager.settings().customBackgroundImagePathProperty().set(resources.background().toString());
                SettingsManager.settings().backgroundTypeProperty().set(BackgroundType.CUSTOM);
                SettingsManager.settings().getThemeAppearanceOverrides().add("background");
            }
            var preset = SettingsManager.getDefaultGameSettingsPresetOrCreate();
            int memory = config.get("memory_mb").getAsInt();
            preset.autoMemoryProperty().setValue(memory == 0);
            if (memory > 0) preset.maxMemoryProperty().setValue(memory);
            Controllers.dialog("已应用服务器推荐设置。实例自己的内存覆盖值保留；可随时在设置中重新选择。", "恢复完成", MessageType.SUCCESS);
        }).start();
    }

    /// Resources already verified by size, digest and passive file type.
    private record Resources(@Nullable Path font, @Nullable Path background) { }
}
