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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Reads / writes the Mindustry dedicated-server {@code config.json} for
 * one {@link ServerInstance}.
 *
 * <p>The file lives at {@code <dataDir>/config/config.json}, where
 * {@code <dataDir>} is whatever {@link ServerInstance#resolveDataDir(Path)}
 * resolves to for the instance's server-root directory. Missing files
 * are treated as a default-valued {@link ServerConfig} rather than as
 * an error, since Mindustry only writes the file once a player tweaks
 * a setting.</p>
 *
 * <p>{@link #save(ServerConfig)} also nudges the running
 * {@link ServerProcess} (if any) with Mindustry's real
 * {@code config key value} console command so the new values take effect
 * without a restart. If the server isn't running, or the command fails to
 * write to its stdin, the save itself still succeeds.</p>
 */
public final class ServerConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ServerInstance instance;
    private final ServerInstanceManager manager;
    private final Path configFile;

    public ServerConfigManager(ServerInstance inst, ServerInstanceManager mgr) {
        this.instance = Objects.requireNonNull(inst, "inst");
        this.manager = Objects.requireNonNull(mgr, "mgr");
        Path dataDir = inst.resolveDataDir(mgr.getServerRoot(inst.getId()));
        this.configFile = dataDir.resolve("config").resolve("config.json");
    }

    /** Absolute path to the {@code config/config.json} this manager binds to. */
    public Path getConfigFile() {
        return configFile;
    }

    /**
     * Read {@code config.json}. Returns a default-valued
     * {@link ServerConfig} when the file is missing, empty, or
     * structurally invalid (the parse error is logged).
     */
    public ServerConfig load() throws IOException {
        if (!Files.isRegularFile(configFile)) {
            return new ServerConfig();
        }
        String text = Files.readString(configFile, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return new ServerConfig();
        }
        try {
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                return new ServerConfig();
            }
            JsonObject json = root.getAsJsonObject();
            return ServerConfig.fromJsonObject(json);
        } catch (JsonSyntaxException ex) {
            Logger.LOG.warning("Mindustry server config.json is malformed at "
                    + configFile + ": " + ex.getMessage());
            return new ServerConfig();
        }
    }

    /**
     * Persist {@code cfg} to {@code config.json}, creating parent
     * directories as needed. If the matching server is currently running,
     * a {@code reload-config} console command is sent; failures there
     * are logged but never propagate.
     */
    public void save(ServerConfig cfg) throws IOException {
        Objects.requireNonNull(cfg, "cfg");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, GSON.toJson(cfg.toJsonObject()), StandardCharsets.UTF_8);

        Object port = cfg.get("port");
        if (port instanceof Integer integer) {
            instance.setPort(integer);
            manager.save(instance);
        }

        Map<String, ServerProcess> running = manager.getRunningProcesses();
        ServerProcess proc = running.get(instance.getId());
        if (proc != null && proc.isAlive()) {
            try {
                for (Map.Entry<String, Object> entry : cfg.toCommandValues().entrySet()) {
                    String value = commandValue(entry.getValue());
                    if (value.isEmpty()) {
                        continue;
                    }
                    proc.sendCommand("config " + entry.getKey() + " " + value);
                }
            } catch (IOException ex) {
                Logger.LOG.warning("Failed to push config command to server "
                        + instance.getId() + ": " + ex.getMessage());
            }
        }
    }

    private static String commandValue(Object value) {
        if (value instanceof String s) {
            return s.replace('\r', ' ').replace('\n', ' ').trim();
        }
        return String.valueOf(value);
    }
}
