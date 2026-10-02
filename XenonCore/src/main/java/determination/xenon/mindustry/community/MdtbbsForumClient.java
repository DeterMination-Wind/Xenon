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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Reader and writer for the MDTBBS forum surface (`/api/v1`).
///
/// The client itself is stateless; authentication is injected through the
/// [`MdtbbsApiClient`] token provider, so public reads work anonymously while
/// writes automatically use the current session. Parsing is defensive because
/// V1 may add fields and list responses sometimes omit embedded authors.
@NotNullByDefault
public final class MdtbbsForumClient {
    /// REST transport shared with the account coordinator.
    private final MdtbbsApiClient api;

    /// Creates a forum client over the given transport.
    ///
    /// @param api API transport carrying the bearer token
    public MdtbbsForumClient(MdtbbsApiClient api) {
        this.api = api;
    }

    /// Web profile URL of a forum user.
    ///
    /// @param userId user id such as `92`
    /// @return absolute profile page URL
    public static String profileUrl(long userId) {
        return "https://mdtbbs.cn/users/" + userId;
    }

    /// One forum category.
    ///
    /// @param id        category id used by thread filters
    /// @param name      display name
    /// @param slug      URL slug, possibly empty
    /// @param color     hex accent color, possibly empty
    /// @param icon      icon hint, possibly empty
    /// @param postCount number of threads in the category
    public record Category(long id, String name, String slug, String color,
                           String icon, long postCount) {
    }

    /// Public author summary.
    ///
    /// @param id        user id
    /// @param username  display name
    /// @param avatarUrl avatar URL, or `null` when the user has none
    public record Author(long id, String username, @Nullable String avatarUrl) {
    }

    /// Thread row shown in the community list.
    ///
    /// @param id         thread id
    /// @param title      thread title
    /// @param pinned     whether the thread is pinned
    /// @param locked     whether replies are disabled
    /// @param viewCount  total views
    /// @param replyCount total replies
    /// @param createdAt  creation time
    /// @param author     embedded author, or `null` when the list omits it
    /// @param category   embedded category, or `null` when omitted
    /// @param excerpt    plain-text preview
    public record ThreadSummary(long id, String title, boolean pinned, boolean locked,
                                long viewCount, long replyCount, Instant createdAt,
                                @Nullable Author author, @Nullable Category category,
                                String excerpt) {
    }

    /// Thread detail with the canonical body and the first page of replies.
    ///
    /// @param summary            summary fields
    /// @param contentJson        canonical Tiptap JSON body, or empty
    /// @param contentText        plain-text body used as a fallback
    /// @param likeCount          total likes
    /// @param liked              whether the current user liked the thread
    /// @param bookmarked         whether the current user bookmarked the thread
    /// @param owner              whether the current user owns the thread
    /// @param replies            replies embedded in the detail response
    public record ThreadDetail(ThreadSummary summary, String contentJson, String contentText,
                               long likeCount, boolean liked, boolean bookmarked, boolean owner,
                               List<Reply> replies) {
        /// Thread id.
        public long id() { return summary.id(); }

        /// Thread title.
        public String title() { return summary.title(); }

        /// Whether the thread is pinned.
        public boolean pinned() { return summary.pinned(); }

        /// Whether replies are disabled.
        public boolean locked() { return summary.locked(); }

        /// Total views.
        public long viewCount() { return summary.viewCount(); }

        /// Total replies.
        public long replyCount() { return summary.replyCount(); }

        /// Creation time.
        public Instant createdAt() { return summary.createdAt(); }

        /// Embedded author, or `null`.
        public @Nullable Author author() { return summary.author(); }

        /// Embedded category, or `null`.
        public @Nullable Category category() { return summary.category(); }
    }

    /// One reply in a thread.
    ///
    /// @param id             reply id
    /// @param userId         author user id
    /// @param author         embedded author, or `null` when omitted
    /// @param contentText    plain-text body
    /// @param contentJson    canonical Tiptap JSON body, or empty
    /// @param likeCount      total likes
    /// @param createdAt      creation time
    /// @param parentReplyId  parent reply id, or `null` for top-level replies
    /// @param owner          whether the current user owns the reply
    public record Reply(long id, long userId, @Nullable Author author, String contentText,
                        String contentJson, long likeCount, Instant createdAt,
                        @Nullable Long parentReplyId, boolean owner) {
    }

    /// One page of results with pagination metadata.
    ///
    /// @param items   page entries
    /// @param page    1-based page number
    /// @param limit   requested page size
    /// @param total   total entry count, or -1 when unknown
    /// @param hasMore whether the next page exists
    public record Page<T>(List<T> items, int page, int limit, int total, boolean hasMore) {
        public Page {
            items = List.copyOf(items);
        }
    }

    /// One notification entry.
    ///
    /// @param id        notification id
    /// @param type      event type string
    /// @param title     headline
    /// @param body      optional body text
    /// @param read      whether the notification was already read
    /// @param createdAt creation time
    public record Notification(long id, String type, String title, String body,
                               boolean read, Instant createdAt) {
    }

    /// Current user with permission hints.
    ///
    /// @param id                 user id
    /// @param username           display name
    /// @param avatarUrl          avatar URL, or `null`
    /// @param phoneVerified      whether phone verification is complete
    /// @param canCreateThread    whether thread creation is currently allowed
    /// @param canReply           whether replying is currently allowed
    /// @param threadCreateReason denial reason for thread creation, or `null`
    /// @param replyCreateReason  denial reason for replies, or `null`
    public record Me(long id, String username, @Nullable String avatarUrl, boolean phoneVerified,
                     boolean canCreateThread, boolean canReply,
                     @Nullable String threadCreateReason, @Nullable String replyCreateReason) {
    }

    /// Loads the visible forum categories.
    ///
    /// @return categories in server order
    /// @throws IOException when the request fails
    public List<Category> categories() throws IOException {
        JsonObject root = api.get("/categories");
        List<Category> result = new ArrayList<>();
        for (JsonElement element : dataArray(root)) {
            JsonObject object = asObject(element);
            if (object == null) continue;
            result.add(new Category(
                    longOf(object, "id", 0),
                    stringOf(object, "name"),
                    stringOf(object, "slug"),
                    stringOf(object, "color"),
                    stringOf(object, "icon"),
                    longOf(object, "post_count", 0)));
        }
        return result;
    }

    /// Lists threads with an optional category filter and full-text search.
    ///
    /// @param categoryId category filter, or `null` for all categories
    /// @param page       1-based page number
    /// @param limit      page size, clamped to 1..50
    /// @param query      search keywords, or `null`/blank for the normal feed
    /// @return one page of thread summaries
    /// @throws IOException when the request fails
    public Page<ThreadSummary> threads(@Nullable Long categoryId, int page, int limit,
                                       @Nullable String query) throws IOException {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("limit", Integer.toString(Math.max(1, Math.min(50, limit))));
        parameters.put("page", Integer.toString(Math.max(1, page)));
        if (categoryId != null) parameters.put("category_id", Long.toString(categoryId));
        if (query != null && !query.isBlank()) {
            parameters.put("q", query.trim());
            parameters.put("sort", "relevance");
        }
        JsonObject root = api.get("/threads", parameters);
        List<ThreadSummary> items = new ArrayList<>();
        for (JsonElement element : dataArray(root)) {
            JsonObject object = asObject(element);
            if (object != null) items.add(parseThread(object));
        }
        return pageOf(root, items);
    }

    /// Loads one thread with the replies embedded in the detail response.
    ///
    /// @param id thread id
    /// @return the thread detail
    /// @throws IOException when the request fails
    public ThreadDetail thread(long id) throws IOException {
        JsonObject data = dataObject(api.get("/threads/" + id));
        List<Reply> replies = new ArrayList<>();
        for (JsonElement element : arrayOf(data, "replies")) {
            JsonObject object = asObject(element);
            if (object != null) replies.add(parseReply(object));
        }
        return parseDetail(data, replies);
    }

    /// Loads one page of replies.
    ///
    /// @param threadId thread id
    /// @param page     1-based page number
    /// @param limit    page size, clamped to 1..50
    /// @return one page of replies
    /// @throws IOException when the request fails
    public Page<Reply> replies(long threadId, int page, int limit) throws IOException {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("page", Integer.toString(Math.max(1, page)));
        parameters.put("limit", Integer.toString(Math.max(1, Math.min(50, limit))));
        JsonObject root = api.get("/threads/" + threadId + "/replies", parameters);
        List<Reply> items = new ArrayList<>();
        for (JsonElement element : dataArray(root)) {
            JsonObject object = asObject(element);
            if (object != null) items.add(parseReply(object));
        }
        return pageOf(root, items);
    }

    /// Loads the authenticated profile and permission hints.
    ///
    /// @return the current user
    /// @throws IOException when the request fails or no token is available
    public Me me() throws IOException {
        return parseMe(dataObject(api.getAuth("/me", null)));
    }

    /// Creates a thread from Markdown and returns its id.
    ///
    /// @param title      thread title
    /// @param categoryId category id, or 0 to let the server pick the default
    /// @param markdown   Markdown body accepted by the compatibility path
    /// @return the created thread id, or -1 when the response omitted it
    /// @throws IOException when the request fails
    public long createThread(String title, long categoryId, String markdown) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("title", title);
        if (categoryId > 0) body.addProperty("category_id", categoryId);
        body.addProperty("content", markdown);
        return longOf(dataObject(api.post("/threads", body)), "id", -1);
    }

    /// Creates a reply from Markdown and returns its id.
    ///
    /// @param threadId      thread to reply to
    /// @param markdown      Markdown body accepted by the compatibility path
    /// @param parentReplyId parent reply, or `null` for a top-level reply
    /// @return the created reply id, or -1 when the response omitted it
    /// @throws IOException when the request fails
    public long createReply(long threadId, String markdown,
                            @Nullable Long parentReplyId) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("content", markdown);
        if (parentReplyId != null) body.addProperty("parent_reply_id", parentReplyId);
        return longOf(dataObject(api.post("/threads/" + threadId + "/replies", body)), "id", -1);
    }

    /// Sets or clears the authenticated user's like on a thread.
    ///
    /// @param threadId thread id
    /// @param liked    desired state
    /// @throws IOException when the request fails
    public void setThreadLiked(long threadId, boolean liked) throws IOException {
        if (liked) {
            api.put("/threads/" + threadId + "/like", null);
        } else {
            api.delete("/threads/" + threadId + "/like", null);
        }
    }

    /// Sets or clears the authenticated user's bookmark on a thread.
    ///
    /// @param threadId  thread id
    /// @param bookmarked desired state
    /// @throws IOException when the request fails
    public void setThreadBookmarked(long threadId, boolean bookmarked) throws IOException {
        if (bookmarked) {
            api.put("/threads/" + threadId + "/bookmark", null);
        } else {
            api.delete("/threads/" + threadId + "/bookmark", null);
        }
    }

    /// Lists notifications for the authenticated user.
    ///
    /// @param page  1-based page number
    /// @param limit page size, clamped to 1..50
    /// @return one page of notifications
    /// @throws IOException when the request fails
    public Page<Notification> notifications(int page, int limit) throws IOException {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("page", Integer.toString(Math.max(1, page)));
        parameters.put("limit", Integer.toString(Math.max(1, Math.min(50, limit))));
        JsonObject root = api.getAuth("/notifications", parameters);
        List<Notification> items = new ArrayList<>();
        for (JsonElement element : dataArray(root)) {
            JsonObject object = asObject(element);
            if (object != null) items.add(parseNotification(object));
        }
        return pageOf(root, items);
    }

    /// Returns the unread notification count.
    ///
    /// @return unread count, or 0 when the response has no value
    /// @throws IOException when the request fails
    public long unreadNotificationCount() throws IOException {
        JsonElement data = api.getAuth("/notifications/unread-count", null).get("data");
        if (data == null || data.isJsonNull()) return 0;
        if (data.isJsonObject()) {
            return longOf(data.getAsJsonObject(), "count",
                    longOf(data.getAsJsonObject(), "unread_count", 0));
        }
        return data.getAsLong();
    }

    /// Parses a thread summary row.
    private static ThreadSummary parseThread(JsonObject object) {
        JsonObject authorObject = objectOf(object, "author");
        JsonObject categoryObject = objectOf(object, "category");
        return new ThreadSummary(
                longOf(object, "id", 0),
                stringOf(object, "title"),
                boolOf(object, "is_pinned", false),
                boolOf(object, "is_locked", false),
                longOf(object, "view_count", 0),
                longOf(object, "reply_count", 0),
                instantOf(object, "created_at"),
                authorObject == null ? flatAuthorOf(object) : parseAuthor(authorObject),
                categoryObject == null ? null : new Category(
                        longOf(categoryObject, "id", 0),
                        stringOf(categoryObject, "name"),
                        stringOf(categoryObject, "slug"),
                        stringOf(categoryObject, "color"),
                        stringOf(categoryObject, "icon"),
                        0),
                stringOf(object, "excerpt"));
    }

    /// Parses a thread detail object with its embedded replies.
    private static ThreadDetail parseDetail(JsonObject object, List<Reply> replies) {
        JsonObject viewer = objectOf(object, "viewer");
        return new ThreadDetail(
                parseThread(object),
                jsonOf(object, "content_json"),
                firstNonBlank(stringOf(object, "content_text"), stringOf(object, "content")),
                longOf(object, "like_count", 0),
                viewer != null && boolOf(viewer, "liked", false),
                viewer != null && boolOf(viewer, "bookmarked", false),
                boolOf(object, "is_owner", false),
                replies);
    }

    /// Parses one reply object.
    ///
    /// Replies carry flat `author_name` / `author_avatar_url` fields instead of
    /// the nested `author` object used by thread rows, so both shapes are
    /// supported.
    private static Reply parseReply(JsonObject object) {
        JsonObject authorObject = objectOf(object, "author");
        JsonElement parent = object.get("parent_reply_id");
        Long parentId = parent == null || parent.isJsonNull() ? null : parent.getAsLong();
        return new Reply(
                longOf(object, "id", 0),
                longOf(object, "user_id", 0),
                authorObject == null ? flatAuthorOf(object) : parseAuthor(authorObject),
                firstNonBlank(stringOf(object, "content_text"), stringOf(object, "content")),
                jsonOf(object, "content_json"),
                longOf(object, "like_count", 0),
                instantOf(object, "created_at"),
                parentId,
                boolOf(object, "is_owner", false));
    }

    /// Parses one author object.
    private static Author parseAuthor(JsonObject object) {
        return new Author(longOf(object, "id", 0), stringOf(object, "username"),
                nullableString(object, "avatar_url"));
    }

    /// Builds an author from the flat `author_*` fields used by replies.
    private static @Nullable Author flatAuthorOf(JsonObject object) {
        String username = stringOf(object, "author_name");
        if (username.isBlank()) username = stringOf(object, "username");
        if (username.isBlank()) return null;
        return new Author(longOf(object, "user_id", 0), username,
                nullableString(object, "author_avatar_url"));
    }

    /// Parses one notification object.
    private static Notification parseNotification(JsonObject object) {
        return new Notification(
                longOf(object, "id", 0),
                stringOf(object, "type"),
                firstNonBlank(stringOf(object, "title"), stringOf(object, "subject")),
                firstNonBlank(stringOf(object, "body"), stringOf(object, "message")),
                boolOf(object, "read", boolOf(object, "is_read", false)),
                instantOf(object, "created_at"));
    }

    /// Parses the `/me` response.
    private static Me parseMe(JsonObject object) {
        JsonObject verification = objectOf(object, "verification");
        JsonObject permissions = objectOf(object, "permissions");
        JsonObject threadCreate = permissions == null ? null : objectOf(permissions, "thread_create");
        JsonObject replyCreate = permissions == null ? null : objectOf(permissions, "reply_create");
        return new Me(
                longOf(object, "id", 0),
                stringOf(object, "username"),
                nullableString(object, "avatar_url"),
                verification != null && boolOf(verification, "phone", false),
                threadCreate == null || boolOf(threadCreate, "allowed", false),
                replyCreate == null || boolOf(replyCreate, "allowed", false),
                threadCreate == null ? null : nullableString(threadCreate, "reason"),
                replyCreate == null ? null : nullableString(replyCreate, "reason"));
    }

    /// Reads the `data` array of a list response.
    private static JsonArray dataArray(JsonObject root) {
        JsonElement data = root.get("data");
        return data != null && data.isJsonArray() ? data.getAsJsonArray() : new JsonArray();
    }

    /// Reads the `data` object of a detail response.
    private static JsonObject dataObject(JsonObject root) {
        JsonObject data = objectOf(root, "data");
        return data == null ? new JsonObject() : data;
    }

    /// Builds a page record from the envelope metadata.
    private static <T> Page<T> pageOf(JsonObject root, List<T> items) {
        JsonObject meta = objectOf(root, "meta");
        JsonObject pagination = meta == null ? null : objectOf(meta, "pagination");
        if (pagination == null) pagination = meta == null ? new JsonObject() : meta;
        int page = (int) longOf(pagination, "page", 1);
        int limit = (int) longOf(pagination, "limit", Math.max(1, items.size()));
        int total = (int) longOf(pagination, "total", items.size());
        boolean hasMore = boolOf(pagination, "has_more", false);
        return new Page<>(items, page, limit, total, hasMore);
    }

    /// Casts an element to a JSON object, or returns `null`.
    private static @Nullable JsonObject asObject(@Nullable JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /// Reads a nested object field, or returns `null`.
    private static @Nullable JsonObject objectOf(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /// Reads a nested array field, or returns an empty array.
    private static JsonArray arrayOf(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    /// Reads a string field, or returns an empty string.
    private static String stringOf(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    /// Reads a nullable string field.
    private static @Nullable String nullableString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    /// Reads a long field with a fallback for missing or malformed values.
    private static long longOf(JsonObject object, String key, long fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return fallback;
        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /// Reads a boolean field with a fallback for missing or malformed values.
    private static boolean boolOf(JsonObject object, String key, boolean fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return fallback;
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /// Reads an ISO-8601 timestamp, falling back to the epoch.
    private static Instant instantOf(JsonObject object, String key) {
        String text = stringOf(object, key);
        if (text.isBlank()) return Instant.EPOCH;
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    /// Serializes a JSON body field back to text, or returns an empty string.
    private static String jsonOf(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return "";
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    /// Returns the first non-blank string.
    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second == null ? "" : second;
    }
}
