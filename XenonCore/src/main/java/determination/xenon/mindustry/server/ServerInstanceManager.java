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
package determination.xenon.mindustry.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import determination.xenon.util.io.FileUtils;
import determination.xenon.util.logging.Logger;
import determination.xenon.util.platform.OperatingSystem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/**
 * On-disk registry of Mindustry dedicated-server instances installed
 * under {@code <config>/servers/<sid>/}.
 *
 * <p>Each subdirectory must contain a {@code server.json} that
 * deserialises into a {@link ServerInstance}; the directory name and
 * the {@code id} field must match (the id wins on conflict).</p>
 *
 * <p>The manager also keeps a live registry of running
 * {@link ServerProcess} handles so the UI can show which servers are
 * up and route console commands to them. Auto-restart and ScriptAgent
 * integration are deliberately out of scope here — only the launch hook
 * is provided for later workers (W7.5 / W7.6) to build on.</p>
 */
public final class ServerInstanceManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String SERVER_JSON = "server.json";
    private static final Pattern TOKEN = Pattern.compile("\\S+");

    private final Path serversRoot;
    private final Map<String, ServerInstance> instances = new LinkedHashMap<>();
    private final Map<String, ServerProcess> running = new ConcurrentHashMap<>();

    public ServerInstanceManager(Path serversRoot) {
        this.serversRoot = Objects.requireNonNull(serversRoot, "serversRoot");
    }

    public Path getServersRoot() {
        return serversRoot;
    }

    /** Re-read all {@code server.json} files. Safe to call repeatedly. */
    public synchronized void refresh() {
        instances.clear();
        if (!Files.isDirectory(serversRoot)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(serversRoot)) {
            for (Path dir : stream) {
                if (!Files.isDirectory(dir)) continue;
                Path json = dir.resolve(SERVER_JSON);
                if (!Files.isRegularFile(json)) continue;
                try {
                    String text = Files.readString(json, StandardCharsets.UTF_8);
                    ServerInstance v = GSON.fromJson(text, ServerInstance.class);
                    if (v == null) continue;
                    if (v.getId() == null || v.getId().isBlank()) {
                        v.setId(dir.getFileName().toString());
                    }
                    instances.put(v.getId(), v);
                } catch (Exception ex) {
                    Logger.LOG.warning("Failed to load Mindustry server " + dir.getFileName() + ": " + ex);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public synchronized Optional<ServerInstance> get(String id) {
        return Optional.ofNullable(instances.get(id));
    }

    public synchronized Collection<ServerInstance> all() {
        return new ArrayList<>(instances.values());
    }

    public synchronized List<String> ids() {
        return new ArrayList<>(instances.keySet());
    }

    public Path getServerRoot(String id) {
        return serversRoot.resolve(id);
    }

    /**
     * Persist an instance (creating its directory if needed) and refresh
     * the in-memory cache. Returns the resolved server-root directory.
     */
    public synchronized Path save(ServerInstance inst) throws IOException {
        Objects.requireNonNull(inst, "inst");
        if (inst.getId() == null || inst.getId().isBlank()) {
            throw new IllegalArgumentException("Mindustry server id must be set");
        }
        Path root = getServerRoot(inst.getId());
        Files.createDirectories(root);
        Path json = root.resolve(SERVER_JSON);
        Files.writeString(json, GSON.toJson(inst), StandardCharsets.UTF_8);
        instances.put(inst.getId(), inst);
        return root;
    }

    /** Remove a server entry on disk. Returns true if anything was deleted. */
    public synchronized boolean delete(String id) throws IOException {
        if (running.containsKey(id)) {
            throw new IOException("server " + id + " is still running, stop it first");
        }
        Path root = getServerRoot(id);
        boolean removed = instances.remove(id) != null;
        if (Files.isDirectory(root)) {
            FileUtils.deleteDirectory(root);
            removed = true;
        }
        return removed;
    }

    /**
     * Spawn the server process for {@code id}. The data directory is
     * created if it does not exist yet. Caller controls how stdout/stderr
     * lines are consumed via {@code stdout}/{@code stderr}; pass
     * {@code null} to silently drop them.
     *
     * <p>The returned {@link ServerProcess} is also stored in
     * {@link #getRunningProcesses()} until it exits, at which point the
     * registry entry is removed automatically.</p>
     */
    public ServerProcess start(String id,
                               Consumer<String> stdout,
                               Consumer<String> stderr) throws IOException {
        ServerInstance inst;
        synchronized (this) {
            inst = instances.get(id);
        }
        if (inst == null) {
            throw new IOException("server instance not found: " + id);
        }
        ServerProcess existing = running.get(id);
        if (existing != null && existing.isAlive()) {
            throw new IOException("server " + id + " is already running");
        }

        Path serverRoot = getServerRoot(id);
        Path jar = inst.resolveJar(serverRoot);
        Path dataDir = inst.resolveDataDir(serverRoot);

        Files.createDirectories(serverRoot);
        Files.createDirectories(dataDir);

        if (!Files.isRegularFile(jar)) {
            throw new IOException("server jar not found: " + jar);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(resolveJavaExecutable(inst).toString());
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dmindustry.data.dir=" + dataDir.toAbsolutePath());
        cmd.addAll(tokenize(inst.getJvmArgs()));
        cmd.add("-jar");
        cmd.add(jar.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(serverRoot.toFile());
        pb.environment().put("MINDUSTRY_DATA_DIR", dataDir.toAbsolutePath().toString());
        pb.redirectErrorStream(false);

        Logger.LOG.info("Xenon launching server " + id + ": " + String.join(" ", cmd));
        Process raw = pb.start();
        ServerProcess sp = new ServerProcess(id, raw, stdout, stderr);
        running.put(id, sp);
        // Drop the entry once the process has exited so the UI sees an
        // accurate view. Auto-restart (W7.6) hooks in by chaining onto
        // this future itself.
        sp.onExit().whenComplete((code, err) -> running.remove(id, sp));
        return sp;
    }

    /** Live snapshot of currently running servers, keyed by instance id. */
    public Map<String, ServerProcess> getRunningProcesses() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(running));
    }

    /**
     * Pick the {@code java} executable to run a server with. Honours
     * {@link ServerInstance#getJavaHome()} when set, otherwise defers to
     * the {@code java} found on {@code PATH}.
     */
    private static Path resolveJavaExecutable(ServerInstance inst) {
        String home = inst.getJavaHome();
        String exeName = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? "java.exe" : "java";
        if (home != null && !home.isBlank()) {
            Path candidate = Path.of(home).resolve("bin").resolve(exeName);
            if (Files.isExecutable(candidate) || Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return Path.of(exeName);
    }

    /** Tokenise a free-form JVM args string from settings into a list. */
    private static List<String> tokenize(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        Matcher m = TOKEN.matcher(raw);
        List<String> out = new ArrayList<>();
        while (m.find()) out.add(m.group());
        return out;
    }
}
