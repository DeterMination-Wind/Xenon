/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package determination.xenon.mindustry.download;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import determination.xenon.mindustry.VersionVariant;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vanilla version source backed by the MDT File manifest.
 *
 * <p>See <a href="https://mdtbbs.cn/posts/186">像素工厂文件站 API</a>.
 * The launcher reads known fields and ignores anything it does not
 * understand. Download URLs come from {@code download_url}; they are
 * resolved against the manifest origin and are not invented from the
 * site's directory layout.</p>
 */
@NotNullByDefault
public final class MdtbbsVersionList extends MindustryVersionList {
    static final String MANIFEST_URL =
            "https://file.mdtbbs.cn/api/v1/mindustry/manifest.json";

    private final String manifestUrl;
    private final String clientVersion;
    private final HttpClient http;

    public MdtbbsVersionList(GitHubReleaseClient fallbackClient) {
        this(fallbackClient, "@develop@");
    }

    /** Creates the MDTbbs source with the launcher version used for attribution. */
    public MdtbbsVersionList(GitHubReleaseClient fallbackClient, String clientVersion) {
        this(fallbackClient, clientVersion, MANIFEST_URL, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(ProxySelector.getDefault())
                .build());
    }

    MdtbbsVersionList(GitHubReleaseClient fallbackClient, String clientVersion,
                      String manifestUrl, HttpClient http) {
        super(VersionVariant.VANILLA, fallbackClient);
        this.manifestUrl = manifestUrl;
        this.clientVersion = clientVersion == null || clientVersion.isBlank()
                ? "@develop@" : clientVersion;
        this.http = http;
    }

    @Override
    public List<MindustryRemoteVersion> refresh() throws IOException {
        try {
            JsonObject root = fetchManifest();
            JsonObject game = selectGame(root);
            if (game == null) throw new IOException("MDTbbs manifest has no Mindustry game");
            JsonArray releases = array(game, "releases");
            List<MindustryRemoteVersion> result = new ArrayList<>();
            if (releases != null) {
                for (JsonElement element : releases) {
                    if (element == null || !element.isJsonObject()) continue;
                    MindustryRemoteVersion version = parseRelease(element.getAsJsonObject());
                    if (version != null) result.add(version);
                }
            }
            result.sort(Comparator.comparing(MdtbbsVersionList::numericVersion)
                    .thenComparing(MindustryRemoteVersion::getTagName)
                    .reversed());
            if (!result.isEmpty()) return result;
            throw new IOException("MDTbbs manifest returned no Mindustry builds");
        } catch (IOException | RuntimeException e) {
            Logger.LOG.warning("MDTbbs version feed failed (" + e.getMessage()
                    + "); falling back to GitHub");
            return new VanillaVersionList(client).refresh();
        }
    }

    private @Nullable MindustryRemoteVersion parseRelease(JsonObject release) {
        String tag = text(release, "tag");
        if (tag == null || tag.isBlank()) return null;
        JsonArray assets = array(release, "assets");
        if (assets == null) return null;

        JsonObject chosen = null;
        for (JsonElement element : assets) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject asset = element.getAsJsonObject();
            if (!isDesktopJar(asset)) continue;
            if (chosen == null || prefer(asset, chosen)) chosen = asset;
        }
        if (chosen == null) return null;

        String relative = text(chosen, "download_url");
        if (relative == null || relative.isBlank()) return null;
        String downloadUrl = resolve(manifestUrl, relative);
        String fileName = text(chosen, "file_name");
        if (fileName == null || fileName.isBlank()) fileName = "Mindustry.jar";
        long size = chosen.has("size") && chosen.get("size").isJsonPrimitive()
                ? chosen.get("size").getAsLong() : 0L;
        String repo = text(release, "source_repository");
        if (repo == null) repo = "Anuken/Mindustry";
        String fallback = "https://github.com/" + repo + "/releases/download/"
                + tag + "/" + fileName;

        String channel = text(release, "channel");
        String buildType = "stable".equals(channel) ? "stable"
                : (channel == null || channel.isBlank() ? "stable" : channel);
        String version = tag.replaceFirst("^[vV]", "");
        Map<String, MindustryRemoteVersion.Artifact> artifacts = new LinkedHashMap<>();
        MindustryRemoteVersion.Artifact artifact = new MindustryRemoteVersion.Artifact(
                "universal", downloadUrl, size, fileName, false, fallback);
        artifacts.put("universal", artifact);
        return new MindustryRemoteVersion(parseBuildNumber(version),
                buildType, VersionVariant.VANILLA, downloadUrl, null, size, tag,
                fileName, artifacts);
    }

    private JsonObject fetchManifest() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(manifestUrl))
                .version(HttpClient.Version.HTTP_1_1)
                .GET()
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Xenon-Launcher/" + clientVersion)
                .header("Accept", "application/json")
                .build();
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + " for " + manifestUrl);
            }
            JsonElement parsed = JsonParser.parseString(response.body());
            if (!parsed.isJsonObject()) throw new IOException("MDTbbs manifest is not a JSON object");
            return parsed.getAsJsonObject();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fetching " + manifestUrl, e);
        }
    }

    private static @Nullable JsonObject selectGame(JsonObject root) {
        JsonArray games = array(root, "games");
        if (games == null) return null;
        JsonObject first = null;
        for (JsonElement element : games) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject game = element.getAsJsonObject();
            if (first == null) first = game;
            if ("mindustry".equals(text(game, "id"))) return game;
        }
        return first;
    }

    private static boolean isDesktopJar(JsonObject asset) {
        String name = text(asset, "file_name");
        if (name == null || !"Mindustry.jar".equalsIgnoreCase(name)) return false;
        String type = text(asset, "type");
        String platform = text(asset, "platform");
        if (type == null && platform == null) return true;
        return "desktop".equals(type) || "desktop".equals(platform);
    }

    /** Prefer the {@code /v8/} copy when the manifest lists the same jar twice. */
    private static boolean prefer(JsonObject candidate, JsonObject current) {
        String candidateUrl = text(candidate, "download_url");
        String currentUrl = text(current, "download_url");
        boolean candidateV8 = candidateUrl != null && candidateUrl.contains("/v8/");
        boolean currentV8 = currentUrl != null && currentUrl.contains("/v8/");
        return candidateV8 && !currentV8;
    }

    private static String resolve(String manifestUrl, String downloadUrl) {
        if (downloadUrl.startsWith("http://") || downloadUrl.startsWith("https://")) {
            return downloadUrl;
        }
        return URI.create(manifestUrl).resolve(downloadUrl).toString();
    }

    private static @Nullable JsonArray array(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonArray()) return null;
        return object.getAsJsonArray(name);
    }

    private static @Nullable String text(JsonObject object, String name) {
        if (!object.has(name) || object.get(name).isJsonNull() || !object.get(name).isJsonPrimitive()) {
            return null;
        }
        return object.get(name).getAsString();
    }

    private static int parseBuildNumber(String version) {
        int dot = version.indexOf('.');
        String major = dot < 0 ? version : version.substring(0, dot);
        try {
            return Integer.parseInt(major);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static BigDecimal numericVersion(MindustryRemoteVersion version) {
        String tag = version.getTagName().replaceFirst("^[vV]", "");
        try {
            return new BigDecimal(tag);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
