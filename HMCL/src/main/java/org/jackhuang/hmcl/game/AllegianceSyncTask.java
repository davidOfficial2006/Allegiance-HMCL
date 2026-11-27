// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.game;

import org.jackhuang.hmcl.sync.AllegianceSync;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jackhuang.hmcl.ui.DialogUtils;
import java.nio.file.Files;
import java.util.concurrent.CancellationException;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.PromptDialogPane;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Connects the launch task to the filesystem synchronizer without blocking the JavaFX thread.
@NotNullByDefault
public final class AllegianceSyncTask extends Task<Void> {
    /// Captured selected game instance; isolation settings resolve its real run directory.
    private final HMCLGameInstance instance;

    /// First-alignment dialog and labels, accessed only from the JavaFX thread.
    private @Nullable VBox alignment;
    /// Main dependency result and detailed download progress, accessed only from JavaFX.
    private @Nullable Label dependencyStatus;
    /// Current operation shown below the checklist.
    private @Nullable Label detail;
    /// Cancellation requested from the alignment dialog.
    private volatile boolean cancelledByPlayer;
    /// Whether the user had to decide whether to continue with unresolved conflicts.
    private boolean hadConflict;

    /// Creates a cancellable launch task.
    private AllegianceSyncTask(HMCLGameInstance instance) { this.instance = instance; }

    /// Distinguishes synchronization failures from ordinary HMCL dependency failures.
    @NotNullByDefault
    public static final class Failure extends IOException {
        /// Preserves the readable synchronization reason and its underlying cause.
        private Failure(IOException cause) { super(cause.getMessage(), cause); }
    }

    /// Resolves the selected instance's actual run directory, including HMCL isolation settings.
    public static Task<Void> create(HMCLGameInstance instance) {
        return new AllegianceSyncTask(instance).withStage("launch.state.allegiance_sync");
    }

    /// Runs after game/modpack completion; an unidentified pack shows an informational notice and continues.
    @Override public void execute() throws IOException {
        boolean firstAlignment = !Files.isRegularFile(instance.getRunDirectory().resolve(".allegiance-sync/last-sync.json"));
        boolean completed = false;
        try {
            completed = new AllegianceSync(instance.getRunDirectory(),
                    URI.create(System.getProperty("hmcl.allegiance.endpoint", AllegianceSync.DEFAULT_ENDPOINT)),
                    message -> {
                        setName(message);
                        LOG.info("[Allegiance Sync] " + message);
                        if (firstAlignment) runInFX(() -> {
                            if (detail != null) detail.setText(message);
                        });
                    }, this::confirm, () -> isCancelled() || cancelledByPlayer)
                    .synchronizeIfRecognized(message -> {
                        LOG.warning("[Allegiance Sync] " + message);
                        runInFX(() -> Controllers.dialog(message, "当前整合包无法同步", MessageType.INFO));
                    }, requirements -> {
                        if (firstAlignment) runInFX(() -> showAlignment(requirements));
                    });
            if (cancelledByPlayer) throw new CancellationException();
        } catch (IOException e) {
            if (cancelledByPlayer) throw new CancellationException();
            LOG.warning("[Allegiance Sync] " + e.getMessage(), e);
            throw new Failure(e);
        } finally {
            if (firstAlignment) {
                boolean success = completed && !cancelledByPlayer && !isCancelled();
                boolean conflict = hadConflict;
                runInFX(() -> finishAlignment(success, conflict));
            }
        }
    }

    /// Shows the checklist after the base game is ready and the server manifest has been read.
    private void showAlignment(String requirements) {
        if (isCancelled() || cancelledByPlayer) return;
        Label heading = new Label("与服务器对齐");
        heading.setStyle("-fx-font-size:16px;-fx-font-weight:bold;");
        Label version = new Label("游戏版本： " + instance.getVersion() + "   ✓");
        Label dependencies = new Label("服务器依赖： " + requirements + "   检查中…");
        Label progress = new Label("正在核对已安装文件");
        progress.setWrapText(true);
        Button cancel = new Button("取消启动");
        VBox pane = new VBox(12, heading, version, dependencies, progress, cancel);
        pane.getStyleClass().add("task-executor-dialog-layout");
        pane.setPadding(new Insets(20));
        pane.setPrefWidth(460);
        cancel.setOnAction(event -> {
            cancelledByPlayer = true;
            progress.setText("正在取消…");
            cancel.setDisable(true);
        });
        alignment = pane;
        dependencyStatus = dependencies;
        detail = progress;
        Controllers.dialog(pane);
    }

    /// Marks verified requirements complete and returns focus to the normal launch dialog.
    private void finishAlignment(boolean success, boolean conflict) {
        @Nullable VBox pane = alignment;
        if (pane == null) return;
        if (!success) {
            DialogUtils.close(pane);
            return;
        }
        if (dependencyStatus != null) {
            String previous = dependencyStatus.getText().replace("检查中…", "");
            dependencyStatus.setText(previous + (conflict ? "⚠ 已确认兼容风险" : "✓"));
        }
        pane.getChildren().get(pane.getChildren().size() - 1).setDisable(true);
        PauseTransition pause = new PauseTransition(Duration.seconds(1.2));
        pause.setOnFinished(event -> DialogUtils.close(pane));
        pause.play();
    }

    /// Waits only on the background task; cancel/close returns false and leaves conflict JARs untouched.
    private boolean confirm(String warning) {
        hadConflict = true;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        runInFX(() -> Controllers.confirm(warning, "客户端兼容风险", MessageType.WARNING,
                () -> result.complete(true), () -> result.complete(false)));
        try {
            while (!isCancelled() && !cancelledByPlayer) {
                try { return result.get(250, TimeUnit.MILLISECONDS); }
                catch (TimeoutException ignored) { }
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }

    /// Opens HMCL's native choice dialog for a single backed-up managed JAR.
    public static void showRestore(HMCLGameInstance instance) {
        Task.supplyAsync(() -> new AllegianceSync(instance.getRunDirectory(), URI.create(AllegianceSync.DEFAULT_ENDPOINT), m -> { }, w -> false).backups())
                .whenComplete(Schedulers.javafx(), (@Nullable List<AllegianceSync.Backup> entries, @Nullable Exception error) -> {
                    if (error != null) {
                        Controllers.dialog("读取备份失败：" + error.getMessage(), "单 Mod 回档", MessageType.ERROR);
                        return;
                    }
                    if (entries == null || entries.isEmpty()) {
                        Controllers.dialog("当前客户端还没有可恢复的旧 JAR 备份。", "单 Mod 回档", MessageType.INFO);
                        return;
                    }
                    var choice = new PromptDialogPane.Builder.CandidatesQuestion("选择要恢复的旧 JAR", entries.stream().map(AllegianceSync.Backup::description).toArray(String[]::new));
                    var closed = new PromptDialogPane.Builder.BooleanQuestion("我已关闭使用此客户端目录的所有游戏进程", false);
                    Controllers.prompt(new PromptDialogPane.Builder("单 Mod 回档", (answers, handler) -> {
                        @Nullable Integer index = choice.getValue();
                        if (index == null || !Boolean.TRUE.equals(closed.getValue())) {
                            handler.reject("请先选择备份，并确认已关闭游戏");
                            return;
                        }
                        if (LauncherHelper.countMangedProcesses() != 0) {
                            handler.reject("请先关闭此启动器启动的游戏");
                            return;
                        }
                        AllegianceSync.Backup backup = entries.get(index);
                        Task.runAsync(() -> new AllegianceSync(instance.getRunDirectory(), URI.create(AllegianceSync.DEFAULT_ENDPOINT), m -> { }, w -> false)
                                        .restore(backup.transaction(), backup.path()))
                                .whenComplete(Schedulers.javafx(), failure -> {
                                    if (failure != null) handler.reject("回档失败：" + failure.getMessage());
                                    else {
                                        handler.resolve();
                                        Controllers.dialog("此 Mod 已恢复，备份副本保留。\n下次启动仍会同步服务器生效版本；需要长期回档时，请管理者在后台发布对应 Mod 的回档候选。",
                                                "单 Mod 回档完成", MessageType.SUCCESS);
                                    }
                                }).start();
                    }).addQuestion(new PromptDialogPane.Builder.HintQuestion("只恢复选中的 Mod。再次启动仍遵循服务器当前发布；长期回档应由后台发布。"))
                            .addQuestion(choice).addQuestion(closed));
                }).start();
    }
}
