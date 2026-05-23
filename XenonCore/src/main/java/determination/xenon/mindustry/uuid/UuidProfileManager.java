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
package determination.xenon.mindustry.uuid;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.reflect.TypeToken;

/**
 * Persistent registry of Mindustry {@link UuidProfile}s — Xenon's
 * stand-in for HMCL's account list.
 *
 * <p>State lives in {@code <configDir>/uuid-profiles.json}, a single
 * Gson-encoded JSON array of {@link UuidProfile} objects in creation
 * order. The file is loaded on construction; mutations (
 * {@link #create(String)}, {@link #rename(String, String)},
 * {@link #delete(String)}, {@link #touch(String)}) atomically rewrite it
 * via a tmp-and-rename. {@link #all()} / {@link #get(String)} are pure
 * reads.</p>
 *
 * <p>The class is fully synchronized: all public methods take the
 * monitor of {@code this}, so it is safe to share a single instance
 * across UI and launch threads. The JSON file is treated as the source
 * of truth — if it parses cleanly the in-memory state mirrors it, and
 * if it does not the manager logs a warning and starts empty rather
 * than throwing during construction.</p>
 *
 * <p>On first run with no file, {@link #all()} returns an empty list:
 * Xenon's UI is expected to prompt the user to create the first profile
 * rather than have a default appear silently with a random nickname.</p>
 */
public final class UuidProfileManager {

    /** Filename inside {@code configDir} where profiles are persisted. */
    public static final String FILE_NAME = "uuid-profiles.json";

    private static final Type LIST_TYPE = new TypeToken<List<UuidProfile>>() {}.getType();

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(Instant.class, new InstantAdapter())
            .create();

    private final Path file;
    /**
     * Profiles keyed by UUID, iteration order = creation order. Backed
     * by {@link LinkedHashMap} so insertion order survives saves /
     * loads.
     */
    private final LinkedHashMap<String, UuidProfile> profiles = new LinkedHashMap<>();

    /**
     * @param configDir directory the launcher uses for its own config;
     *                  {@link #FILE_NAME} is resolved against it
     * @throws NullPointerException if {@code configDir} is {@code null}
     */
    public UuidProfileManager(Path configDir) {
        this.file = Objects.requireNonNull(configDir, "configDir").resolve(FILE_NAME);
        loadFromDisk();
    }

    /** Absolute path of the on-disk JSON file. */
    public synchronized Path getFile() {
        return file;
    }

    /**
     * @return a snapshot of all known profiles, in creation order.
     *         Empty when the on-disk file is missing or empty.
     */
    public synchronized List<UuidProfile> all() {
        return List.copyOf(profiles.values());
    }

    /** Look up a profile by its UUID string. */
    public synchronized Optional<UuidProfile> get(String uuid) {
        if (uuid == null) return Optional.empty();
        return Optional.ofNullable(profiles.get(uuid));
    }

    /**
     * Create a new profile with a freshly-generated UUID and the given
     * nickname, then persist. The new profile is appended to the end of
     * the creation-order list.
     *
     * @param nickname display name; must be non-blank
     * @return the persisted profile
     */
    public synchronized UuidProfile create(String nickname) {
        Objects.requireNonNull(nickname, "nickname");
        String trimmed = nickname.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("nickname must not be blank");
        }
        // Practically zero collision risk with 128 random bits, but
        // staying defensive keeps the invariant tight.
        String uuid;
        do {
            uuid = UuidGenerator.generate();
        } while (profiles.containsKey(uuid));

        UuidProfile p = new UuidProfile(uuid, trimmed, Instant.now());
        profiles.put(uuid, p);
        try {
            persist();
        } catch (IOException e) {
            // Roll back so the in-memory state stays consistent with disk.
            profiles.remove(uuid);
            Logger.LOG.warning("Failed to persist new UUID profile " + uuid, e);
            throw new UncheckedPersistException(e);
        }
        Logger.LOG.info("Created Mindustry UUID profile " + uuid + " (" + trimmed + ")");
        return p;
    }

    /**
     * Update the nickname of {@code uuid} in place and persist.
     *
     * @throws IOException if the file cannot be rewritten
     * @throws IllegalArgumentException if no profile with that UUID
     *         exists, or {@code newName} is blank
     */
    public synchronized void rename(String uuid, String newName) throws IOException {
        Objects.requireNonNull(newName, "newName");
        String trimmed = newName.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("nickname must not be blank");
        }
        UuidProfile p = profiles.get(uuid);
        if (p == null) {
            throw new IllegalArgumentException("Unknown UUID: " + uuid);
        }
        String old = p.nickname;
        p.nickname = trimmed;
        try {
            persist();
        } catch (IOException e) {
            p.nickname = old;
            throw e;
        }
        Logger.LOG.info("Renamed UUID profile " + uuid + ": '" + old + "' -> '" + trimmed + "'");
    }

    /**
     * Remove the profile with the given UUID and persist. No-op when
     * the UUID is unknown.
     */
    public synchronized void delete(String uuid) throws IOException {
        UuidProfile removed = profiles.remove(uuid);
        if (removed == null) {
            return;
        }
        try {
            persist();
        } catch (IOException e) {
            // Restore prior in-memory state on failure.
            profiles.put(uuid, removed);
            // Re-key isn't enough by itself: LinkedHashMap will have
            // appended at the tail. Rebuild in original order from a
            // saved snapshot when we actually care; for now the failure
            // path is logged and propagated and the UI will reload.
            throw e;
        }
        Logger.LOG.info("Deleted UUID profile " + uuid);
    }

    /**
     * Update {@link UuidProfile#lastUsedAt} of the given profile to
     * {@code now()} and persist. No-op when the UUID is unknown so
     * launch code can call this unconditionally.
     */
    public synchronized void touch(String uuid) {
        UuidProfile p = profiles.get(uuid);
        if (p == null) return;
        Instant prev = p.lastUsedAt;
        p.lastUsedAt = Instant.now();
        try {
            persist();
        } catch (IOException e) {
            p.lastUsedAt = prev;
            Logger.LOG.warning("Failed to persist lastUsedAt for UUID profile " + uuid, e);
        }
    }

    /**
     * @return the profile with the most recent {@link UuidProfile#lastUsedAt},
     *         or {@link Optional#empty()} if the registry is empty.
     *         Profiles missing a timestamp (older files) sort last.
     */
    public synchronized Optional<UuidProfile> getActive() {
        UuidProfile best = null;
        for (UuidProfile p : profiles.values()) {
            if (best == null) {
                best = p;
                continue;
            }
            if (compareLastUsed(p, best) > 0) {
                best = p;
            }
        }
        return Optional.ofNullable(best);
    }

    private static int compareLastUsed(UuidProfile a, UuidProfile b) {
        Instant ai = a.lastUsedAt != null ? a.lastUsedAt : Instant.MIN;
        Instant bi = b.lastUsedAt != null ? b.lastUsedAt : Instant.MIN;
        return ai.compareTo(bi);
    }

    private void loadFromDisk() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.LOG.warning("Failed to read UUID profiles file " + file, e);
            return;
        }
        if (text.isBlank()) {
            return;
        }
        List<UuidProfile> parsed;
        try {
            parsed = GSON.fromJson(text, LIST_TYPE);
        } catch (JsonSyntaxException e) {
            Logger.LOG.warning("Malformed UUID profiles file " + file + ": " + e.getMessage());
            return;
        }
        if (parsed == null) {
            return;
        }
        for (UuidProfile p : parsed) {
            if (p == null || p.uuid == null || p.uuid.isBlank()) {
                continue;
            }
            if (p.nickname == null) {
                p.nickname = "";
            }
            if (p.createdAt == null) {
                p.createdAt = Instant.EPOCH;
            }
            if (p.lastUsedAt == null) {
                p.lastUsedAt = p.createdAt;
            }
            if (p.note == null) {
                p.note = "";
            }
            profiles.put(p.uuid, p);
        }
    }

    private void persist() throws IOException {
        Files.createDirectories(file.getParent());
        List<UuidProfile> snapshot = new ArrayList<>(profiles.values());
        String json = GSON.toJson(snapshot, LIST_TYPE);
        // tmp-and-rename keeps a power-loss event from leaving us with
        // a half-written profiles file that fails to parse on restart.
        Path tmp = Files.createTempFile(file.getParent(), "uuid-profiles-", ".json.tmp");
        try {
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // Some filesystems (notably some Windows network shares) reject ATOMIC_MOVE.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /** Visible for callers that prefer not to deal with checked exceptions inside reactive UI flows. */
    public static final class UncheckedPersistException extends RuntimeException {
        UncheckedPersistException(IOException cause) {
            super(cause);
        }
    }

    /**
     * ISO-8601 string round-trip for {@link Instant} so the on-disk
     * file is human-readable instead of an epoch-millis number.
     */
    private static final class InstantAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            switch (in.peek()) {
                case NULL:
                    in.nextNull();
                    return null;
                case STRING:
                    String s = in.nextString();
                    if (s.isBlank()) {
                        return null;
                    }
                    try {
                        return Instant.parse(s);
                    } catch (DateTimeParseException e) {
                        throw new IOException("Invalid Instant value: " + s, e);
                    }
                case NUMBER:
                    return Instant.ofEpochMilli(in.nextLong());
                default:
                    throw new IOException("Unexpected JSON token for Instant: " + in.peek());
            }
        }
    }
}
