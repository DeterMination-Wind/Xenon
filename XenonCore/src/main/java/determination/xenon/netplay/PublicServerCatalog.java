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
package determination.xenon.netplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import determination.xenon.mindustry.download.MirrorSelector;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Fetches Mindustry's public server directory.
///
/// The data comes from Anuken's `MindustryServerList` repository, which the
/// game itself consumes. Responses are cached under
/// `<cacheRoot>/netplay/servers.json` so the browser still works when the
/// network or GitHub is unreachable.
@NotNullByDefault
public final class PublicServerCatalog {

    /// Upstream JSON documents, merged in order.
    public static final @Unmodifiable List<String> SOURCES = List.of(
            "https://raw.githubusercontent.com/Anuken/MindustryServerList/main/servers_v8.json",
            "https://raw.githubusercontent.com/Anuken/MindustryServerList/main/servers_be.json");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    /// One public server entry.
    ///
    /// @param name      display name
    /// @param addresses host:port candidates; the first is the primary one
    /// @param bleedingEdge whether the entry came from the Bleeding-Edge list
    public record Server(String name, @Unmodifiable List<String> addresses, boolean bleedingEdge) {

        /// The address the UI offers first.
        public String primaryAddress() {
            return addresses.isEmpty() ? "" : addresses.get(0);
        }
    }

    /// Result of one load, including whether it came from the disk cache.
    ///
    /// @param servers merged server entries
    /// @param cached  whether the network failed and the cache was used
    public record LoadResult(@Unmodifiable List<Server> servers, boolean cached) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .proxy(ProxySelector.getDefault())
            .build();
    private final @Nullable Path cacheRoot;

    /// Creates a catalog reader.
    ///
    /// @param cacheRoot the launcher caches directory, or `null` to disable caching
    public PublicServerCatalog(@Nullable Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    /// Loads the merged server list, falling back to the on-disk cache.
    ///
    /// @return the server entries and whether they were served from cache
    /// @throws IOException when the network failed and no cache exists
    public LoadResult load() throws IOException {
        try {
            List<Server> merged = new ArrayList<>();
            for (String source : SOURCES) {
                boolean bleedingEdge = source.contains("servers_be");
                merged.addAll(parse(fetch(source), bleedingEdge));
            }
            if (merged.isEmpty()) {
                throw new IOException("Server list feed returned no entries");
            }
            writeCache(merged);
            return new LoadResult(List.copyOf(merged), false);
        } catch (IOException e) {
            List<Server> cached = readCache();
            if (!cached.isEmpty()) {
                Logger.LOG.warning("Public server list unavailable (" + e.getMessage()
                        + "); serving " + cached.size() + " cached entries");
                return new LoadResult(cached, true);
            }
            throw e;
        }
    }

    /// Parses one `[{name, address: [...]}]` document.
    ///
    /// @param json    upstream JSON
    /// @param bleedingEdge whether the rows belong to the Bleeding-Edge list
    /// @return parsed entries; malformed rows are skipped
    public static List<Server> parse(@Nullable String json, boolean bleedingEdge) {
        List<Server> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                return out;
            }
            JsonArray array = root.getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String name = object.has("name") && object.get("name").isJsonPrimitive()
                        ? object.get("name").getAsString()
                        : "";
                List<String> addresses = new ArrayList<>();
                if (object.has("address") && object.get("address").isJsonArray()) {
                    for (JsonElement address : object.getAsJsonArray("address")) {
                        if (address.isJsonPrimitive() && !address.getAsString().isBlank()) {
                            addresses.add(address.getAsString().trim());
                        }
                    }
                }
                if (name.isBlank() || addresses.isEmpty()) {
                    continue;
                }
                out.add(new Server(name, List.copyOf(addresses), bleedingEdge));
            }
        } catch (RuntimeException e) {
            Logger.LOG.warning("Cannot parse the public server list: " + e.getMessage());
        }
        return out;
    }

    /// Fetches one document through the GitHub mirror when available.
    private String fetch(String url) throws IOException {
        String mirrored = url;
        try {
            mirrored = MirrorSelector.getInstance().wrap(url);
        } catch (RuntimeException e) {
            Logger.LOG.warning("Mirror selection failed for " + url + ": " + e.getMessage());
        }
        IOException lastError = null;
        for (String candidate : mirrored.equals(url) ? List.of(url) : List.of(mirrored, url)) {
            try {
                return get(candidate);
            } catch (IOException e) {
                lastError = e;
                Logger.LOG.warning("Server list fetch failed via " + candidate + ": " + e.getMessage());
            }
        }
        throw lastError != null ? lastError : new IOException("Cannot fetch " + url);
    }

    /// Performs one GET request.
    private String get(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(READ_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "Xenon-Launcher")
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fetching " + url, e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    /// Persists the merged list for offline use.
    private void writeCache(List<Server> servers) {
        if (cacheRoot == null) {
            return;
        }
        Path file = cacheRoot.resolve("netplay").resolve("servers.json");
        try {
            Files.createDirectories(Objects.requireNonNull(file.getParent()));
            StringBuilder json = new StringBuilder("[\n");
            for (int i = 0; i < servers.size(); i++) {
                Server server = servers.get(i);
                json.append("  {\"name\": \"").append(escape(server.name()))
                        .append("\", \"be\": ").append(server.bleedingEdge())
                        .append(", \"address\": [");
                for (int j = 0; j < server.addresses().size(); j++) {
                    if (j > 0) {
                        json.append(", ");
                    }
                    json.append('"').append(escape(server.addresses().get(j))).append('"');
                }
                json.append("]}").append(i + 1 < servers.size() ? "," : "").append('\n');
            }
            json.append("]\n");
            Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.LOG.warning("Cannot cache the public server list: " + e.getMessage());
        }
    }

    /// Reads the cached list written by [#writeCache].
    private List<Server> readCache() {
        if (cacheRoot == null) {
            return List.of();
        }
        Path file = cacheRoot.resolve("netplay").resolve("servers.json");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            List<Server> out = new ArrayList<>();
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                return List.of();
            }
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String name = object.has("name") ? object.get("name").getAsString() : "";
                boolean be = object.has("be") && object.get("be").getAsBoolean();
                List<String> addresses = new ArrayList<>();
                if (object.has("address")) {
                    for (JsonElement address : object.getAsJsonArray("address")) {
                        addresses.add(address.getAsString());
                    }
                }
                if (!name.isBlank() && !addresses.isEmpty()) {
                    out.add(new Server(name, List.copyOf(addresses), be));
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            Logger.LOG.warning("Cannot read the cached public server list: " + e.getMessage());
            return List.of();
        }
    }

    /// Minimal JSON string escaping for the cache writer.
    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
