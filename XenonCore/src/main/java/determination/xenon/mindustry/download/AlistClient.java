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
package determination.xenon.mindustry.download;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal client for an Alist (https://alist.nn.ci) instance — the
 * filesystem-style API ({@code /api/fs/list} for directories,
 * {@code /d/<path>} for direct download with 302 to the underlying mirror).
 *
 * <p>Used as a fast first-priority source for the MindustryX variant; the
 * site at {@code 47.238.248.194:5244/Github/MindustryX} mirrors every
 * TinyLake release a few minutes after publish, far ahead of all
 * GitHub mirrors.</p>
 */
public final class AlistClient {

    public static final String DEFAULT_BASE = "http://47.238.248.194:5244";

    private final String base;
    private final HttpClient http;

    public AlistClient() {
        this(DEFAULT_BASE);
    }

    public AlistClient(String base) {
        this.base = base.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * List the contents of {@code path} (alist-relative, e.g.
     * {@code /Github/MindustryX}). Returns directories <em>and</em> files,
     * caller filters as appropriate.
     */
    public List<Entry> list(String path) throws IOException {
        // Hand-rolled JSON to avoid pulling Gson into this class — the
        // payload is fixed-shape and small.
        String body = "{\"path\":\"" + path.replace("\"", "\\\"")
                + "\",\"password\":\"\",\"page\":1,\"per_page\":200,\"refresh\":false}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/fs/list"))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Xenon-Launcher")
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted contacting " + base, e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Alist HTTP " + resp.statusCode() + " for " + path);
        }

        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (root.get("code") == null || root.get("code").getAsInt() != 200) {
            String msg = root.has("message") ? root.get("message").getAsString() : "unknown";
            throw new IOException("Alist API error: " + msg);
        }
        JsonObject data = root.getAsJsonObject("data");
        if (data == null) return new ArrayList<>();
        JsonArray content = data.getAsJsonArray("content");
        List<Entry> out = new ArrayList<>();
        if (content == null) return out;
        for (JsonElement el : content) {
            JsonObject obj = el.getAsJsonObject();
            Entry e = new Entry();
            e.name = obj.get("name").getAsString();
            e.size = obj.has("size") && !obj.get("size").isJsonNull()
                    ? obj.get("size").getAsLong() : 0;
            e.isDir = obj.has("is_dir") && obj.get("is_dir").getAsBoolean();
            if (obj.has("modified") && !obj.get("modified").isJsonNull()) {
                try {
                    e.modified = Instant.parse(obj.get("modified").getAsString());
                } catch (DateTimeParseException ignored) {
                    e.modified = null;
                }
            }
            out.add(e);
        }
        return out;
    }

    /** Public direct-download URL for an alist path. Server responds 302 to a mirror. */
    public String directUrl(String path) {
        if (!path.startsWith("/")) path = "/" + path;
        return base + "/d" + path;
    }

    public static final class Entry {
        public String name;
        public long size;
        public boolean isDir;
        public Instant modified;

        @Override
        public String toString() {
            return (isDir ? "[dir] " : "") + name + " (" + size + " bytes)";
        }
    }
}
