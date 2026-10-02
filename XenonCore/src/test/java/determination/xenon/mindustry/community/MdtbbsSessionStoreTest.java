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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests atomic session persistence and corruption tolerance.
@NotNullByDefault
public final class MdtbbsSessionStoreTest {
    @Test
    public void roundTripsSession(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("community").resolve("session.json");
        MdtbbsSessionStore store = new MdtbbsSessionStore(file);
        MdtbbsOauthSession session = new MdtbbsOauthSession("access", "refresh",
                Instant.ofEpochMilli(123456789L), "openid profile", "client-1");

        store.save(session);
        MdtbbsOauthSession loaded = store.load().orElseThrow();

        assertEquals(session.accessToken(), loaded.accessToken());
        assertEquals(session.refreshToken(), loaded.refreshToken());
        assertEquals(session.expiresAt(), loaded.expiresAt());
        assertEquals(session.scope(), loaded.scope());
        assertEquals(session.clientId(), loaded.clientId());

        store.clear();
        assertTrue(store.load().isEmpty());
    }

    @Test
    public void malformedFileIsTreatedAsAbsent(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("session.json");
        Files.writeString(file, "not json", StandardCharsets.UTF_8);
        MdtbbsSessionStore store = new MdtbbsSessionStore(file);

        Optional<MdtbbsOauthSession> loaded = store.load();

        assertTrue(loaded.isEmpty());
    }
}
