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
package determination.xenon.util.instance;

import com.google.gson.Gson;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import static determination.xenon.util.logging.Logger.LOG;

/// Directory-backed registry of running launcher instances.
///
/// Every process stores one record per instance as
/// `<pid>-<8hex>.json` next to a zero-byte `<pid>-<8hex>.lock` sentinel.
/// The process that created a record keeps an exclusive [FileLock] on the
/// sentinel until it exits, so the OS releases the lock even after a crash
/// and another launcher can detect that the record is stale. Owners are
/// created with [register], discovered with [findAll] and checked with
/// [isAlive]; [unregister] and [close] release the lock and delete the files.
///
/// All methods are safe to call from any thread and every I/O failure is
/// contained: a broken registry only disables instance detection, it never
/// prevents the launcher from starting.
@NotNullByDefault
public final class LauncherInstanceRegistry implements AutoCloseable {

    /// Probes whether a loopback control port still accepts connections.
    ///
    /// The probe is the fallback used when the sentinel lock cannot be
    /// inspected, and it is injectable so tests can simulate unreachable
    /// instances without opening sockets.
    @FunctionalInterface
    public interface PortProbe {
        /// Returns whether the given loopback port accepts a TCP connection.
        ///
        /// @param port loopback port of the instance control server
        /// @param timeoutMillis connection timeout in milliseconds
        /// @return `true` when the connection attempt succeeded
        boolean isReachable(int port, int timeoutMillis);
    }

    /// JSON codec used for the registration records.
    private static final Gson GSON = new Gson();

    /// Suffix of the data files holding the serialized instances.
    private static final String JSON_SUFFIX = ".json";

    /// Suffix of the sentinel files holding the liveness lock.
    private static final String LOCK_SUFFIX = ".lock";

    /// Connection timeout of the fallback port probe.
    private static final int PORT_PROBE_TIMEOUT_MILLIS = 300;

    /// Directory holding the instance records.
    private final Path directory;

    /// Fallback probe used when the sentinel lock cannot be inspected.
    private final PortProbe portProbe;

    /// Registration owned by this registry, or `null` when nothing is registered.
    private @Nullable Registration registration;

    /// One registration owned by this process.
    ///
    /// @param instance registered launcher instance
    /// @param jsonFile file holding the serialized instance
    /// @param lockFile sentinel file holding the exclusive lock
    /// @param lockChannel channel that keeps the sentinel file open
    /// @param fileLock exclusive lock that proves this process is alive
    private record Registration(LauncherInstance instance, Path jsonFile, Path lockFile,
                                FileChannel lockChannel, FileLock fileLock) {
    }

    /// Creates a registry that probes loopback ports with a short timeout.
    ///
    /// @param directory directory holding the instance records; it is created
    ///                  on demand by [register]
    public LauncherInstanceRegistry(Path directory) {
        this(directory, LauncherInstanceRegistry::probeDefault);
    }

    /// Creates a registry with an explicit port probe.
    ///
    /// @param directory directory holding the instance records
    /// @param portProbe fallback probe used when the sentinel lock is unusable
    public LauncherInstanceRegistry(Path directory, PortProbe portProbe) {
        this.directory = Objects.requireNonNull(directory);
        this.portProbe = Objects.requireNonNull(portProbe);
    }

    /// Registers one instance and takes the sentinel lock for it.
    ///
    /// The record is written to a temporary file first and then moved into
    /// place, so other processes never observe a half-written record. Any
    /// previous registration of this registry is released first.
    ///
    /// @param instance instance to register
    /// @throws IOException when the directory or the record cannot be written
    ///                     or the sentinel lock is already held
    public synchronized void register(LauncherInstance instance) throws IOException {
        Objects.requireNonNull(instance);
        unregister();
        Files.createDirectories(directory);

        String baseName = instance.pid() + "-" + randomSuffix();
        Path jsonFile = directory.resolve(baseName + JSON_SUFFIX);
        Path lockFile = directory.resolve(baseName + LOCK_SUFFIX);
        Path tempFile = directory.resolve(baseName + JSON_SUFFIX + ".tmp");

        byte[] bytes = GSON.toJson(instance).getBytes(StandardCharsets.UTF_8);
        Files.write(tempFile, bytes,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(tempFile, jsonFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some filesystems cannot move atomically; a plain replace is
            // still better than writing the record in place.
            Files.move(tempFile, jsonFile, StandardCopyOption.REPLACE_EXISTING);
        }

        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException ignored) {
            // The current JVM already holds this lock; treat it as busy below.
        }
        if (lock == null) {
            channel.close();
            Files.deleteIfExists(jsonFile);
            Files.deleteIfExists(lockFile);
            throw new IOException("Launcher instance lock " + lockFile + " is already held");
        }

        registration = new Registration(instance, jsonFile, lockFile, channel, lock);
        LOG.info("Registered launcher instance " + instance.pid() + " on port " + instance.port());
    }

    /// Returns every readable registration ordered by startup time.
    ///
    /// Blank or malformed records are skipped silently and a missing or
    /// unreadable directory simply yields an empty list.
    ///
    /// @return all readable instances, sorted by [LauncherInstance#startedAt()]
    ///         in ascending order
    public synchronized List<LauncherInstance> findAll() {
        List<LauncherInstance> instances = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + JSON_SUFFIX)) {
            for (Path file : stream) {
                LauncherInstance instance = read(file);
                if (instance != null) {
                    instances.add(instance);
                }
            }
        } catch (IOException e) {
            LOG.warning("Failed to list launcher instances in " + directory, e);
            return instances;
        } catch (RuntimeException e) {
            LOG.warning("Failed to parse launcher instances in " + directory, e);
            return instances;
        }
        instances.sort(Comparator.comparingLong(LauncherInstance::startedAt));
        return instances;
    }

    /// Checks whether the owning process of an instance is still running.
    ///
    /// The sentinel lock is authoritative: when it can be acquired the owner
    /// has exited, so both record files are deleted and `false` is returned.
    /// When the lock cannot be inspected (for example on filesystems without
    /// byte-range locks) the loopback control port is probed instead.
    ///
    /// @param instance instance previously returned by [findAll]
    /// @return `true` while the owning launcher process is still alive
    public synchronized boolean isAlive(LauncherInstance instance) {
        Path jsonFile = findJsonFile(instance);
        if (jsonFile == null) {
            return probe(instance);
        }

        String fileName = jsonFile.getFileName().toString();
        Path lockFile = directory.resolve(
                fileName.substring(0, fileName.length() - JSON_SUFFIX.length()) + LOCK_SUFFIX);
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException ignored) {
                // This JVM owns the lock, which proves the instance is alive.
                return true;
            }
            if (lock == null) {
                return true;
            }
            try {
                lock.release();
            } catch (IOException ignored) {
                // Releasing is best effort; the record is stale either way.
            }
            LOG.info("Removing stale launcher instance record " + fileName);
            deleteQuietly(jsonFile);
            deleteQuietly(lockFile);
            return false;
        } catch (IOException e) {
            LOG.warning("Failed to inspect launcher instance lock " + lockFile, e);
            return probe(instance);
        }
    }

    /// Releases this registry's own registration and deletes its files.
    ///
    /// The method is idempotent; calling it when nothing is registered does
    /// nothing.
    public synchronized void unregister() {
        Registration current = registration;
        registration = null;
        if (current == null) {
            return;
        }
        LOG.info("Unregistering launcher instance " + current.instance().pid());
        try {
            current.fileLock().release();
        } catch (IOException ignored) {
            // Best effort only; the lock dies with the process anyway.
        }
        try {
            current.lockChannel().close();
        } catch (IOException ignored) {
            // Best effort only; the channel dies with the process anyway.
        }
        deleteQuietly(current.lockFile());
        deleteQuietly(current.jsonFile());
    }

    /// Releases this registry's own registration; same as [unregister].
    @Override
    public synchronized void close() {
        unregister();
    }

    /// Finds the data file that backs the given instance.
    private @Nullable Path findJsonFile(LauncherInstance instance) {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + JSON_SUFFIX)) {
            for (Path file : stream) {
                LauncherInstance candidate = read(file);
                if (candidate != null
                        && candidate.pid() == instance.pid()
                        && candidate.startedAt() == instance.startedAt()
                        && candidate.token().equals(instance.token())) {
                    return file;
                }
            }
        } catch (IOException e) {
            LOG.warning("Failed to scan launcher instance directory " + directory, e);
        }
        return null;
    }

    /// Reads and validates one record, returning `null` for unusable files.
    private static @Nullable LauncherInstance read(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                return null;
            }
            return validate(GSON.fromJson(content, LauncherInstance.class));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /// Returns the instance when it carries a plausible identity, else `null`.
    private static @Nullable LauncherInstance validate(@Nullable LauncherInstance instance) {
        if (instance == null
                || instance.pid() <= 0L
                || instance.port() <= 0
                || instance.port() > 65535
                || instance.token() == null
                || instance.token().isBlank()
                || instance.version() == null
                || instance.startedAt() <= 0L) {
            return null;
        }
        return instance;
    }

    /// Probes the control port of an instance with the fallback probe.
    private boolean probe(LauncherInstance instance) {
        return portProbe.isReachable(instance.port(), PORT_PROBE_TIMEOUT_MILLIS);
    }

    /// Default probe that opens a TCP connection to the loopback port.
    private static boolean probeDefault(int port, int timeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeoutMillis);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /// Deletes a file, logging instead of propagating failures.
    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warning("Failed to delete launcher instance file " + file, e);
        }
    }

    /// Generates the random part of a record file name.
    private static String randomSuffix() {
        return String.format("%08x", ThreadLocalRandom.current().nextInt());
    }
}
