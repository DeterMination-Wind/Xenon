/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package determination.xenon.mindustry.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import determination.xenon.mindustry.download.MirrorDownloader;
import determination.xenon.mindustry.download.ProgressCallback;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Read-only client for {@code api.mindustry.top/maps}.
 */
public final class MindustryTopMapRepository {
    public static final String SITE_URL = "https://www.mindustry.top/map";
    public static final String API_ROOT = "https://api.mindustry.top";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final MirrorDownloader downloader;

    public MindustryTopMapRepository(Path cacheRoot) {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.downloader = new MirrorDownloader(cacheRoot);
    }

    /**
     * Fetch one page from {@code /maps/list}.
     *
     * @param offset feed offset used by the backend's {@code begin=} query
     * @param search raw search string; blank means "all maps"
     */
    public List<MindustryRemoteMap> fetchPage(int offset, String search) throws IOException {
        String encoded = URLEncoder.encode(search == null ? "" : search, StandardCharsets.UTF_8);
        String url = API_ROOT + "/maps/list?begin=" + Math.max(0, offset) + "&search=" + encoded;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(READ_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "Xenon-Launcher")
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted contacting " + url, e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + " from " + url);
        }
        return parseList(resp.body());
    }

    /**
     * Download one {@code .msav} archive into {@code target}.
     *
     * <p>{@code expectedSize} is unknown for this API, so callers typically pass
     * {@code 0}; {@link MirrorDownloader} still uses the response
     * {@code Content-Length} when the server provides it.</p>
     */
    public void downloadMap(int thread, Path target, ProgressCallback progress)
            throws IOException {
        downloader.download(API_ROOT + "/maps/" + thread + ".msav", target, 0L, progress);
    }

    static List<MindustryRemoteMap> parseList(String json) {
        JsonElement root = JsonParser.parseString(Objects.requireNonNull(json, "json"));
        if (!root.isJsonArray()) {
            return List.of();
        }
        JsonArray arr = root.getAsJsonArray();
        List<MindustryRemoteMap> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) continue;
            MindustryRemoteMap map = parseEntry(el.getAsJsonObject());
            if (map != null) out.add(map);
        }
        return List.copyOf(out);
    }

    private static MindustryRemoteMap parseEntry(JsonObject obj) {
        int id = intValue(obj, "id", 0);
        if (id <= 0) return null;
        return new MindustryRemoteMap(
                id,
                string(obj, "latest"),
                string(obj, "name"),
                string(obj, "desc"),
                string(obj, "preview"),
                stringArray(obj, "tags"),
                intValue(obj, "width", 0),
                intValue(obj, "height", 0),
                string(obj, "mode"));
    }

    private static String string(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return "";
        JsonElement el = obj.get(key);
        if (!el.isJsonPrimitive()) return "";
        try {
            String raw = el.getAsString();
            return raw == null ? "" : raw;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int intValue(JsonObject obj, String key, int def) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return def;
        JsonElement el = obj.get(key);
        if (!el.isJsonPrimitive()) return def;
        try {
            if (el.getAsJsonPrimitive().isNumber()) return el.getAsInt();
            String s = el.getAsString().trim();
            return s.isEmpty() ? def : Integer.parseInt(s);
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static List<String> stringArray(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return List.of();
        JsonElement el = obj.get(key);
        if (!el.isJsonArray()) return List.of();
        JsonArray arr = el.getAsJsonArray();
        List<String> out = new ArrayList<>(arr.size());
        for (JsonElement item : arr) {
            if (item != null && item.isJsonPrimitive()) {
                try {
                    String text = item.getAsString();
                    if (text != null && !text.isBlank()) out.add(text);
                } catch (RuntimeException ignored) {
                }
            }
        }
        return List.copyOf(out);
    }
}
