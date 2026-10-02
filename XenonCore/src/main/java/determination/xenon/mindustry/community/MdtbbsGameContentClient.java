/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package determination.xenon.mindustry.community;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import determination.xenon.mindustry.download.ProgressCallback;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/// Reader and writer for the MDTBBS game-content surface (`/api/v1/game-content`).
///
/// Covers blueprint and map discovery, blueprint code retrieval, map file
/// downloads with redirect handling, and the moderated upload flows (blueprint
/// JSON and map multipart sessions).
@NotNullByDefault
public final class MdtbbsGameContentClient {
    /// Poll interval while a map upload is processed.
    private static final long UPLOAD_POLL_MILLIS = 1500L;
    /// How long to wait for map processing before giving up.
    private static final long UPLOAD_TIMEOUT_MILLIS = 120_000L;

    /// REST transport shared with the account coordinator.
    private final MdtbbsApiClient api;

    /// Creates a game-content client over the given transport.
    ///
    /// @param api API transport carrying the bearer token
    public MdtbbsGameContentClient(MdtbbsApiClient api) {
        this.api = api;
    }

    /// One cursor-paginated page of content entries.
    ///
    /// @param items      page entries
    /// @param nextCursor opaque cursor for the next page, or empty
    /// @param hasMore    whether another page exists
    public record ContentPage<T>(List<T> items, String nextCursor, boolean hasMore) {
        public ContentPage {
            items = List.copyOf(items);
        }
    }

    /// One blueprint summary.
    ///
    /// @param id            blueprint id such as `bp_...`
    /// @param title         display title
    /// @param summary       short description
    /// @param author        author name, or empty
    /// @param authorId      author user id, or 0 when unknown
    /// @param gameVersion   target game version, or empty
    /// @param previewUrl    server-rendered preview URL, or empty when absent
    /// @param previewWidth  schematic width in blocks, or 0 when unknown
    /// @param previewHeight schematic height in blocks, or 0 when unknown
    /// @param tags          tag list
    /// @param downloads     download count
    /// @param likes         like count
    public record Blueprint(String id, String title, String summary, String author,
                            long authorId, String gameVersion, String previewUrl,
                            long previewWidth, long previewHeight,
                            List<String> tags, long downloads, long likes) {
    }

    /// One map summary with the fields needed to install it.
    ///
    /// @param id            map id such as `map_...`
    /// @param title         display title
    /// @param summary       short description
    /// @param author        author name, or empty
    /// @param authorId      author user id, or 0 when unknown
    /// @param gameVersion   target game version, or empty
    /// @param previewUrl    server-rendered preview URL, or empty when absent
    /// @param previewWidth  map width in tiles, or 0 when unknown
    /// @param previewHeight map height in tiles, or 0 when unknown
    /// @param mode          game mode, or empty
    /// @param size          file size in bytes, or 0 when unknown
    /// @param sha256        SHA-256 of the map file, or empty
    /// @param tags          tag list
    /// @param downloads     download count
    /// @param likes         like count
    public record MapItem(String id, String title, String summary, String author,
                          long authorId, String gameVersion, String previewUrl,
                          long previewWidth, long previewHeight,
                          String mode, long size, String sha256,
                          List<String> tags, long downloads, long likes) {
    }

    /// Lists blueprints.
    ///
    /// @param query  search keywords, or `null`
    /// @param cursor opaque cursor, or `null` for the first page
    /// @param limit  page size, clamped to 1..50
    /// @return one page of blueprints
    /// @throws IOException when the request fails
    public ContentPage<Blueprint> blueprints(@Nullable String query, @Nullable String cursor,
                                             int limit) throws IOException {
        return listPage("/game-content/blueprints", query, cursor, limit,
                this::parseBlueprint);
    }

    /// Lists maps.
    ///
    /// @param query  search keywords, or `null`
    /// @param cursor opaque cursor, or `null` for the first page
    /// @param limit  page size, clamped to 1..50
    /// @return one page of maps
    /// @throws IOException when the request fails
    public ContentPage<MapItem> maps(@Nullable String query, @Nullable String cursor,
                                     int limit) throws IOException {
        return listPage("/game-content/maps", query, cursor, limit,
                this::parseMap);
    }

    /// Loads one map with file metadata.
    ///
    /// @param id map id such as `map_...`
    /// @return the map summary enriched from the detail response
    /// @throws IOException when the request fails
    public MapItem mapDetail(String id) throws IOException {
        return parseMap(MdtbbsJson.dataObject(api.get("/game-content/maps/" + id)));
    }

    /// Returns the base64 schematic code of a blueprint.
    ///
    /// @param id blueprint id such as `bp_...`
    /// @return the code, or an empty string when unavailable
    /// @throws IOException when the request fails
    public String blueprintCode(String id) throws IOException {
        return MdtbbsJson.stringOf(MdtbbsJson.dataObject(
                api.get("/game-content/blueprints/" + id + "/code")), "code");
    }

    /// Preview image URL of a blueprint, loadable anonymously.
    ///
    /// @param id blueprint id
    /// @return absolute preview URL
    public String blueprintPreviewUrl(String id) {
        return api.baseUrl() + "/game-content/blueprints/" + id + "/preview";
    }

    /// Preview image URL of a map, loadable anonymously.
    ///
    /// @param id map id
    /// @return absolute preview URL
    public String mapPreviewUrl(String id) {
        return api.baseUrl() + "/game-content/maps/" + id + "/preview";
    }

    /// Downloads a small public asset such as a preview image.
    ///
    /// The caller is expected to handle failures quietly; previews are
    /// optional decorations and must never surface as task errors.
    ///
    /// @param url absolute preview URL
    /// @return the raw response bytes
    /// @throws IOException when the request fails or the server returns an error
    public byte[] downloadBytes(String url) throws IOException {
        HttpResponse<InputStream> response = api.followRedirect(
                api.openRawUrl(url, false), false);
        if (response.statusCode() / 100 != 2) {
            try (InputStream ignored = response.body()) {
                // Drain so the connection can be reused before reporting failure.
            }
            throw new IOException("Preview returned HTTP " + response.statusCode());
        }
        try (InputStream input = response.body()) {
            return input.readAllBytes();
        }
    }

    /// Downloads a map file, following the signed redirect without leaking the token.
    ///
    /// @param id       map id
    /// @param target   destination `.msav` file
    /// @param progress optional progress callback
    /// @return the destination path
    /// @throws IOException when the download fails
    public Path downloadMap(String id, Path target, @Nullable ProgressCallback progress)
            throws IOException {
        HttpResponse<InputStream> response = api.followRedirect(api.openRaw(
                "/game-content/maps/" + id + "/download/file", null, false), false);
        if (response.statusCode() / 100 != 2) {
            try (InputStream ignored = response.body()) {
                // Drain so the connection can be reused before reporting failure.
            }
            throw new IOException("Map download returned HTTP " + response.statusCode());
        }
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        long total = response.headers().firstValueAsLong("Content-Length").orElse(0L);
        try (InputStream input = response.body();
             OutputStream output = Files.newOutputStream(target,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[64 * 1024];
            long readTotal = 0;
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
                readTotal += read;
                if (progress != null) progress.onProgress(readTotal, total);
            }
        }
        return target;
    }

    /// Submits a blueprint for moderation.
    ///
    /// @param title       blueprint title
    /// @param description short description, may be empty
    /// @param tags        tags, at most 30 entries server-side
    /// @param code        base64 schematic code
    /// @return created blueprint id, or an empty string when the response omitted it
    /// @throws IOException when the request fails
    public String uploadBlueprint(String title, String description, List<String> tags,
                                  String code) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("title", title);
        body.addProperty("description", description);
        body.add("tags", tagsArray(tags));
        body.addProperty("code", code);
        return idOf(MdtbbsJson.dataObject(api.post("/game-content/blueprints", body)));
    }

    /// Uploads a map through the multipart session and submits it for moderation.
    ///
    /// @param file        `.msav` file, at most 20 MiB server-side
    /// @param title       map title
    /// @param description short description, may be empty
    /// @param tags        tags, at most 30 entries server-side
    /// @return created resource id, or -1 when the response omitted it
    /// @throws IOException when hashing, uploading or processing fails
    public long uploadMap(Path file, String title, String description, List<String> tags)
            throws IOException {
        byte[] fileBytes = Files.readAllBytes(file);
        String sha256 = sha256Hex(fileBytes);
        String boundary = "----XenonBoundary" + System.nanoTime();
        byte[] multipart = buildMapUploadBody(boundary, file.getFileName().toString(),
                fileBytes, sha256);
        JsonObject created = api.postRaw("/game-content/maps/uploads",
                "multipart/form-data; boundary=" + boundary, multipart);
        String uploadId = MdtbbsJson.stringOf(MdtbbsJson.dataObject(created), "uploadId");
        if (uploadId.isBlank()) {
            throw new IOException("Map upload response has no uploadId");
        }
        awaitUpload(uploadId);

        JsonObject complete = new JsonObject();
        complete.addProperty("title", title);
        complete.addProperty("description", description);
        complete.add("tags", tagsArray(tags));
        return MdtbbsJson.longOf(MdtbbsJson.dataObject(
                api.post("/game-content/maps/uploads/" + uploadId + "/complete", complete)),
                "resourceId", -1);
    }

    /// Waits until the server finishes parsing an uploaded map.
    private void awaitUpload(String uploadId) throws IOException {
        long deadline = System.currentTimeMillis() + UPLOAD_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            JsonObject data = MdtbbsJson.dataObject(
                    api.getAuth("/game-content/maps/uploads/" + uploadId, null));
            String status = MdtbbsJson.stringOf(data, "status");
            if ("uploaded".equals(status) || "completed".equals(status)) return;
            if ("failed".equals(status) || "expired".equals(status)) {
                throw new IOException("Map upload ended with status " + status);
            }
            try {
                Thread.sleep(UPLOAD_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for map processing", e);
            }
        }
        throw new IOException("Map upload processing timed out");
    }

    /// Fetches and parses one cursor-paginated content page.
    private <T> ContentPage<T> listPage(String path, @Nullable String query,
                                        @Nullable String cursor, int limit,
                                        Function<JsonObject, T> parser) throws IOException {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("limit", Integer.toString(Math.max(1, Math.min(50, limit))));
        if (query != null && !query.isBlank()) parameters.put("q", query.trim());
        if (cursor != null && !cursor.isBlank()) parameters.put("cursor", cursor);
        JsonObject root = api.get(path, parameters);
        JsonObject data = MdtbbsJson.dataObject(root);
        List<T> items = new ArrayList<>();
        for (JsonElement element : MdtbbsJson.arrayOf(data, "data")) {
            JsonObject object = MdtbbsJson.asObject(element);
            if (object != null) items.add(parser.apply(object));
        }
        JsonObject pagination = MdtbbsJson.objectOf(data, "pagination");
        String nextCursor = pagination == null ? ""
                : MdtbbsJson.stringOf(pagination, "nextCursor");
        boolean hasMore = pagination != null && MdtbbsJson.boolOf(pagination, "hasMore", false);
        return new ContentPage<>(items, nextCursor, hasMore);
    }

    /// Parses one blueprint entry.
    private Blueprint parseBlueprint(JsonObject object) {
        JsonObject preview = MdtbbsJson.objectOf(object, "preview");
        return new Blueprint(
                idOf(object),
                MdtbbsJson.stringOf(object, "title"),
                MdtbbsJson.stringOf(object, "summary"),
                authorOf(object),
                authorIdOf(object),
                gameVersionOf(object),
                previewUrlOf(object),
                preview == null ? 0 : MdtbbsJson.longOf(preview, "width", 0),
                preview == null ? 0 : MdtbbsJson.longOf(preview, "height", 0),
                MdtbbsJson.stringListOf(object, "tags"),
                statOf(object, "downloads"),
                statOf(object, "likes"));
    }

    /// Parses one map entry, including the file metadata of detail responses.
    private MapItem parseMap(JsonObject object) {
        JsonObject file = MdtbbsJson.objectOf(object, "file");
        JsonObject map = MdtbbsJson.objectOf(object, "map");
        JsonObject preview = MdtbbsJson.objectOf(object, "preview");
        return new MapItem(
                idOf(object),
                MdtbbsJson.stringOf(object, "title"),
                MdtbbsJson.stringOf(object, "summary"),
                authorOf(object),
                authorIdOf(object),
                gameVersionOf(object),
                previewUrlOf(object),
                preview == null ? 0 : MdtbbsJson.longOf(preview, "width", 0),
                preview == null ? 0 : MdtbbsJson.longOf(preview, "height", 0),
                map == null ? "" : MdtbbsJson.stringOf(map, "mode"),
                file == null ? 0 : MdtbbsJson.longOf(file, "size", 0),
                file == null ? "" : MdtbbsJson.stringOf(file, "sha256"),
                MdtbbsJson.stringListOf(object, "tags"),
                statOf(object, "downloads"),
                statOf(object, "likes"));
    }

    /// Reads the stable public id, which may be a string or a number.
    private static String idOf(JsonObject object) {
        JsonElement id = object.get("id");
        if (id == null || id.isJsonNull()) {
            id = object.get("public_id");
        }
        return id == null || id.isJsonNull() ? "" : id.getAsString();
    }

    /// Reads the `preview.thumbnail` path of an entry and resolves it against
    /// the API origin.
    ///
    /// @param object entry with an optional `preview` object
    /// @return absolute preview URL, or an empty string when absent
    private String previewUrlOf(JsonObject object) {
        JsonObject preview = MdtbbsJson.objectOf(object, "preview");
        String thumbnail = preview == null ? "" : MdtbbsJson.stringOf(preview, "thumbnail");
        return resolveAssetUrl(thumbnail);
    }

    /// Resolves an API-relative asset path against the configured origin.
    ///
    /// @param path absolute or API-relative path
    /// @return absolute URL, or an empty string for a blank path
    private String resolveAssetUrl(@Nullable String path) {
        if (path == null || path.isBlank()) return "";
        if (path.startsWith("http")) return path;
        URI uri = URI.create(api.baseUrl());
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        return path.startsWith("/") ? origin + path : origin + "/" + path;
    }

    /// Reads the author name from an object or plain string field.
    private static String authorOf(JsonObject object) {
        JsonElement author = object.get("author");
        if (author == null || author.isJsonNull()) return "";
        if (author.isJsonObject()) {
            JsonObject authorObject = author.getAsJsonObject();
            String username = MdtbbsJson.stringOf(authorObject, "username");
            return username.isBlank() ? MdtbbsJson.stringOf(authorObject, "name") : username;
        }
        return author.getAsString();
    }

    /// Reads the numeric author id from the nested author object.
    private static long authorIdOf(JsonObject object) {
        JsonObject author = MdtbbsJson.objectOf(object, "author");
        return author == null ? 0 : MdtbbsJson.longOf(author, "id", 0);
    }

    /// Reads `game.version` from an entry.
    private static String gameVersionOf(JsonObject object) {
        JsonObject game = MdtbbsJson.objectOf(object, "game");
        return game == null ? "" : MdtbbsJson.stringOf(game, "version");
    }

    /// Reads one `stats` counter.
    private static long statOf(JsonObject object, String key) {
        JsonObject stats = MdtbbsJson.objectOf(object, "stats");
        return stats == null ? 0 : MdtbbsJson.longOf(stats, key, 0);
    }

    /// Builds a JSON array from tag strings.
    private static JsonArray tagsArray(List<String> tags) {
        JsonArray array = new JsonArray();
        for (String tag : tags) {
            if (tag != null && !tag.isBlank()) array.add(tag.trim());
        }
        return array;
    }

    /// Builds the multipart body for a map upload session.
    private static byte[] buildMapUploadBody(String boundary, String fileName,
                                             byte[] fileBytes, String sha256) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(fileBytes.length + 512);
        writeUtf8(out, "--" + boundary + "\r\n");
        writeUtf8(out, "Content-Disposition: form-data; name=\"file\"; filename=\""
                + fileName + "\"\r\n");
        writeUtf8(out, "Content-Type: application/octet-stream\r\n\r\n");
        out.write(fileBytes);
        writeUtf8(out, "\r\n--" + boundary + "\r\n");
        writeUtf8(out, "Content-Disposition: form-data; name=\"sha256\"\r\n\r\n");
        writeUtf8(out, sha256 + "\r\n");
        writeUtf8(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    /// Writes UTF-8 text into the multipart buffer.
    private static void writeUtf8(ByteArrayOutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    /// Computes the lowercase hex SHA-256 of a byte array.
    private static String sha256Hex(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }
}
