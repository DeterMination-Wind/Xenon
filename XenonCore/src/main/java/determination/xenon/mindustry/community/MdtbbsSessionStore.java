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

import com.google.gson.Gson;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;

/// Persists one MindAuth OAuth session as JSON.
///
/// Writes go through a sibling temp file and an atomic move so a crash cannot
/// leave a half-written session behind. The store is deliberately separate
/// from launcher settings because tokens must never be logged or exported.
@NotNullByDefault
public final class MdtbbsSessionStore {
    /// JSON reader and writer for the session entry.
    private static final Gson GSON = new Gson();
    /// Backing session file.
    private final Path file;

    /// Creates a store for the given session file.
    ///
    /// @param file target JSON file; parent directories are created on save
    public MdtbbsSessionStore(Path file) {
        this.file = file;
    }

    /// Loads the persisted session, if any.
    ///
    /// A malformed file is treated as absent so a corrupted token cache never
    /// blocks the launcher.
    ///
    /// @return the stored session, or an empty optional
    public Optional<MdtbbsOauthSession> load() {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            Entry entry = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Entry.class);
            if (entry == null || entry.accessToken == null || entry.accessToken.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new MdtbbsOauthSession(
                    entry.accessToken,
                    entry.refreshToken,
                    Instant.ofEpochMilli(entry.expiresAtEpochMillis),
                    entry.scope == null ? "" : entry.scope,
                    entry.clientId == null ? "" : entry.clientId));
        } catch (IOException | RuntimeException e) {
            Logger.LOG.warning("Ignoring unreadable MDTbbs session file: " + e.getMessage());
            return Optional.empty();
        }
    }

    /// Saves the session atomically.
    ///
    /// @param session session to persist
    /// @throws IOException when the file cannot be written
    public void save(MdtbbsOauthSession session) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Entry entry = new Entry();
        entry.accessToken = session.accessToken();
        entry.refreshToken = session.refreshToken();
        entry.expiresAtEpochMillis = session.expiresAt().toEpochMilli();
        entry.scope = session.scope();
        entry.clientId = session.clientId();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(entry), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /// Deletes the persisted session; missing files are ignored.
    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            Logger.LOG.warning("Failed to delete MDTbbs session file: " + e.getMessage());
        }
    }

    /// JSON shape of one persisted session.
    private static final class Entry {
        /// Bearer token.
        String accessToken;
        /// Rotating refresh token; Gson may leave this `null`.
        @Nullable String refreshToken;
        /// Access-token expiry in epoch milliseconds.
        long expiresAtEpochMillis;
        /// Granted scopes.
        String scope;
        /// Owning public client id.
        String clientId;
    }
}
