// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.addon.repository;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/// Read-only application entry retained by packaging; uses the real metadata policy without downloading a Mod JAR.
@NotNullByDefault
public final class CurseForgeMetadataProbe {
    /// Utility entry point only.
    private CurseForgeMetadataProbe() { }

    /// Prints public project/file identity and attempted hosts without printing API keys or headers.
    public static void main(String @Unmodifiable [] args) throws IOException {
        if (args.length != 2 || !args[0].matches("[1-9][0-9]{0,9}") || !args[1].matches("[1-9][0-9]{0,9}"))
            throw new IOException("Usage: CurseForgeMetadataProbe <projectID> <fileID>");
        List<String> routes = new ArrayList<>();
        CurseForgeMetadata policy = new CurseForgeMetadata(System.getProperty("hmcl.curseforge.source", "auto"),
                CurseForgeRemoteAddonRepository.API_KEY, request -> {
                    URI uri = URI.create(request.url());
                    routes.add(uri.getHost() + uri.getPath());
                    return CurseForgeMetadata.fetch(request);
                }, System::currentTimeMillis);
        long started = System.currentTimeMillis();
        JsonObject project = policy.get("/v1/mods/" + args[0], TypeToken.get(JsonObject.class)).getAsJsonObject("data");
        JsonObject file = policy.get("/v1/mods/" + args[0] + "/files/" + args[1], TypeToken.get(JsonObject.class)).getAsJsonObject("data");
        if (!project.get("id").getAsString().equals(args[0]) || !file.get("modId").getAsString().equals(args[0])
                || !file.get("id").getAsString().equals(args[1])) throw new IOException("Metadata project/file identity mismatch");
        JsonObject result = new JsonObject();
        result.addProperty("status", "passed");
        result.addProperty("project_id", args[0]);
        result.addProperty("file_id", args[1]);
        result.addProperty("project_name", project.get("name").getAsString());
        result.addProperty("filename", file.get("fileName").getAsString());
        result.addProperty("elapsed_ms", System.currentTimeMillis() - started);
        result.addProperty("jar_download_tested", false);
        result.add("routes", JsonUtils.GSON.toJsonTree(routes));
        System.out.println(JsonUtils.GSON.toJson(result));
    }
}
