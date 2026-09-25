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
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a GitHub release asset to the 像素工厂文件站 copy listed in the
 * MDT File manifest. Callers try this URL before any other mirror.
 */
@NotNullByDefault
public final class MdtbbsFileMirror {
    private static final Pattern GITHUB_ASSET = Pattern.compile(
            "^https?://github\\.com/([^/]+/[^/]+)/releases/download/([^/]+)/([^?#]+)$");
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8))
            .proxy(ProxySelector.getDefault())
            .build();

    private static final Object LOCK = new Object();
    private static @Nullable Map<String, String> cached;
    private static long cachedAt;

    private MdtbbsFileMirror() {
    }

    /**
     * @return an absolute file.mdtbbs.cn URL when the manifest lists this asset, otherwise {@code null}
     */
    public static @Nullable String lookup(String githubUrl) {
        Matcher matcher = GITHUB_ASSET.matcher(githubUrl == null ? "" : githubUrl);
        if (!matcher.matches()) return null;
        String repo = matcher.group(1);
        String tag = decode(matcher.group(2));
        String file = decode(matcher.group(3));
        try {
            return index().get(key(repo, tag, file));
        } catch (IOException e) {
            Logger.LOG.warning("MDT File manifest unavailable, skipping file-station mirror: "
                    + e.getMessage());
            return null;
        }
    }

    /** Package-visible for tests. Later duplicate keys lose when the new URL is not the {@code /v8/} copy. */
    static Map<String, String> indexFrom(String manifestJson, String manifestUrl) {
        JsonElement parsed = JsonParser.parseString(manifestJson);
        if (!parsed.isJsonObject()) return Map.of();
        JsonObject root = parsed.getAsJsonObject();
        JsonArray games = root.has("games") && root.get("games").isJsonArray()
                ? root.getAsJsonArray("games") : null;
        if (games == null) return Map.of();
        Map<String, String> index = new HashMap<>();
        for (JsonElement gameElement : games) {
            if (gameElement == null || !gameElement.isJsonObject()) continue;
            JsonObject game = gameElement.getAsJsonObject();
            if (!game.has("releases") || !game.get("releases").isJsonArray()) continue;
            for (JsonElement releaseElement : game.getAsJsonArray("releases")) {
                if (releaseElement == null || !releaseElement.isJsonObject()) continue;
                JsonObject release = releaseElement.getAsJsonObject();
                String tag = text(release, "tag");
                String repo = text(release, "source_repository");
                if (tag == null || repo == null) continue;
                if (!release.has("assets") || !release.get("assets").isJsonArray()) continue;
                for (JsonElement assetElement : release.getAsJsonArray("assets")) {
                    if (assetElement == null || !assetElement.isJsonObject()) continue;
                    JsonObject asset = assetElement.getAsJsonObject();
                    String name = text(asset, "file_name");
                    String relative = text(asset, "download_url");
                    if (name == null || relative == null || relative.isBlank()) continue;
                    String absolute = relative.startsWith("http://") || relative.startsWith("https://")
                            ? relative : URI.create(manifestUrl).resolve(relative).toString();
                    String mapKey = key(repo, tag, name);
                    String existing = index.get(mapKey);
                    if (existing == null || (absolute.contains("/v8/") && !existing.contains("/v8/"))) {
                        index.put(mapKey, absolute);
                    }
                }
            }
        }
        return index;
    }

    private static Map<String, String> index() throws IOException {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (cached != null && now - cachedAt < CACHE_TTL.toMillis()) return cached;
            HttpRequest request = HttpRequest.newBuilder(URI.create(MdtbbsVersionList.MANIFEST_URL))
                    .version(HttpClient.Version.HTTP_1_1)
                    .GET()
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "Xenon-Launcher")
                    .header("Accept", "application/json")
                    .build();
            try {
                HttpResponse<String> response = HTTP.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() / 100 != 2) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                cached = indexFrom(response.body(), MdtbbsVersionList.MANIFEST_URL);
                cachedAt = now;
                return cached;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted fetching MDT File manifest", e);
            }
        }
    }

    private static String key(String repo, String tag, String fileName) {
        return repo.toLowerCase(Locale.ROOT) + "\n" + tag + "\n" + fileName.toLowerCase(Locale.ROOT);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static @Nullable String text(JsonObject object, String name) {
        if (!object.has(name) || object.get(name).isJsonNull() || !object.get(name).isJsonPrimitive()) {
            return null;
        }
        return object.get(name).getAsString();
    }
}
