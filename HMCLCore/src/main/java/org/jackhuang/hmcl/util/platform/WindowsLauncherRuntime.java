// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.util.platform;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Files;
import java.nio.file.Path;

/// Applies the Windows settings previously supplied by the maintenance CMD to native EXE startup.
@NotNullByDefault
public final class WindowsLauncherRuntime {
    /// Utility class only.
    private WindowsLauncherRuntime() { }

    /// Uses the Windows certificate store and a short socket directory unless explicitly overridden.
    public static void initialize() {
        if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS) return;
        if (System.getProperty("javax.net.ssl.trustStoreType") == null)
            System.setProperty("javax.net.ssl.trustStoreType", "Windows-ROOT");
        if (System.getProperty("jdk.net.unixdomain.tmpdir") == null) {
            Path directory = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "Temp");
            if (Files.isDirectory(directory) && Files.isWritable(directory))
                System.setProperty("jdk.net.unixdomain.tmpdir", directory.toString());
        }
    }
}
