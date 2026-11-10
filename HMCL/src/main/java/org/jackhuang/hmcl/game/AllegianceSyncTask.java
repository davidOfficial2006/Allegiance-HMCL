// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.game;

import org.jackhuang.hmcl.sync.AllegianceSync;
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
        try {
            new AllegianceSync(instance.getRunDirectory(),
                    URI.create(System.getProperty("hmcl.allegiance.endpoint", AllegianceSync.DEFAULT_ENDPOINT)),
                    message -> { setName(message); LOG.info("[Allegiance Sync] " + message); }, this::confirm, this::isCancelled)
                    .synchronizeIfRecognized(message -> {
                        LOG.warning("[Allegiance Sync] " + message);
                        runInFX(() -> Controllers.dialog(message, "当前整合包无法同步", MessageType.INFO));
                    });
        } catch (IOException e) {
            LOG.warning("[Allegiance Sync] " + e.getMessage(), e);
            throw new Failure(e);
        }
    }

    /// Waits only on the background task; cancel/close returns false and leaves conflict JARs untouched.
    private boolean confirm(String warning) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        runInFX(() -> Controllers.confirm(warning, "客户端兼容风险", MessageType.WARNING,
                () -> result.complete(true), () -> result.complete(false)));
        try {
            while (!isCancelled()) {
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
