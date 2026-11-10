// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.launch;

import org.jackhuang.hmcl.util.platform.CommandBuilder;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Child game JVMs need the explicit Windows fixes used by their launcher, with user overrides honored.
@NotNullByDefault
class DefaultLauncherRuntimeTest {
    /// Inherits only the supported runtime properties and never duplicates an explicit child override.
    @Test void childRuntimeInheritsFixesWithoutOverwritingUserOptions() {
        @Nullable String previousType = System.getProperty("javax.net.ssl.trustStoreType");
        @Nullable String previousTemp = System.getProperty("jdk.net.unixdomain.tmpdir");
        try {
            System.setProperty("javax.net.ssl.trustStoreType", "Windows-ROOT");
            System.setProperty("jdk.net.unixdomain.tmpdir", "C:/Windows/Temp");
            CommandBuilder inherited = new CommandBuilder();
            DefaultLauncher.inheritWindowsRuntimeOptions(inherited);
            boolean windows = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS;
            assertEquals(windows, !inherited.noneMatch(arg -> arg.equals("-Djavax.net.ssl.trustStoreType=Windows-ROOT")));
            assertEquals(windows, !inherited.noneMatch(arg -> arg.equals("-Djdk.net.unixdomain.tmpdir=C:/Windows/Temp")));

            CommandBuilder overridden = new CommandBuilder();
            overridden.add("-Djdk.net.unixdomain.tmpdir=D:/GameTemp");
            overridden.add("-Djavax.net.ssl.trustStoreType=PKCS12");
            DefaultLauncher.inheritWindowsRuntimeOptions(overridden);
            assertTrue(overridden.noneMatch(arg -> arg.equals("-Djdk.net.unixdomain.tmpdir=C:/Windows/Temp")));
            assertTrue(overridden.noneMatch(arg -> arg.equals("-Djavax.net.ssl.trustStoreType=Windows-ROOT")));
            System.setProperty("javax.net.ssl.trustStoreType", "JKS");
            System.clearProperty("jdk.net.unixdomain.tmpdir");
            CommandBuilder defaults = new CommandBuilder();
            DefaultLauncher.inheritWindowsRuntimeOptions(defaults);
            assertTrue(defaults.noneMatch(arg -> arg.startsWith("-Djavax.net.ssl.") || arg.startsWith("-Djdk.net.unixdomain.")));
        } finally {
            if (previousType == null) System.clearProperty("javax.net.ssl.trustStoreType");
            else System.setProperty("javax.net.ssl.trustStoreType", previousType);
            if (previousTemp == null) System.clearProperty("jdk.net.unixdomain.tmpdir");
            else System.setProperty("jdk.net.unixdomain.tmpdir", previousTemp);
        }
    }
}
