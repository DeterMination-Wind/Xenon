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
package determination.xenon.mindustry.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import determination.xenon.Metadata;
import determination.xenon.mindustry.LaunchOptions;
import determination.xenon.mindustry.MindustryLaunchService;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.XenonGameRepository;
import determination.xenon.mindustry.download.MindustryDownloadSource;
import determination.xenon.mindustry.modpack.XenonModpackPacker;
import determination.xenon.setting.ConfigHolder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/// Headless command-line entry point.
///
/// Runs before JavaFX starts. Supports listing, launching and exporting
/// instances plus a small whitelist of settings. Any unknown flag exits with
/// code 2 instead of starting the UI, so scripts can rely on the contract.
@NotNullByDefault
public final class XenonCli {

    /// Exit code for a successful command.
    public static final int OK = 0;
    /// Exit code for a failed command.
    public static final int FAILURE = 1;
    /// Exit code for invalid arguments or an unknown setting key.
    public static final int USAGE = 2;

    /// Runs the CLI when the first argument is a known flag.
    ///
    /// @param args process arguments
    /// @return the exit code, or `null` when the normal UI should start
    public static @Nullable Integer runIfCli(String[] args) {
        if (args == null || args.length == 0 || args[0] == null || !args[0].startsWith("--")) {
            return null;
        }
        try {
            return run(args);
        } catch (Exception e) {
            System.err.println("xenon: " + e.getMessage());
            return FAILURE;
        }
    }

    /// Dispatches one command.
    private static int run(String[] args) throws Exception {
        return switch (args[0]) {
            case "--help", "-h" -> {
                printUsage();
                yield OK;
            }
            case "--list" -> list();
            case "--launch" -> launch(args);
            case "--export" -> export(args);
            case "--get" -> get(args);
            case "--set" -> set(args);
            default -> {
                System.err.println("xenon: unknown option " + args[0]);
                printUsage();
                yield USAGE;
            }
        };
    }

    /// Prints available commands.
    private static void printUsage() {
        System.out.println("""
                Xenon Launcher CLI

                  xenon --help
                  xenon --list
                  xenon --launch <instance-id>
                  xenon --export <instance-id> <output.zip>
                  xenon --get <key>
                  xenon --set <key> <value>

                Setting keys: download.source, download.speed, github.token
                Exit codes: 0 success, 1 failure, 2 usage error
                """);
    }

    /// Lists installed instances.
    private static int list() throws IOException {
        XenonGameRepository repo = repository();
        List<MindustryVersion> versions = repo.all().stream()
                .sorted(Comparator.comparing(MindustryVersion::getName))
                .toList();
        for (MindustryVersion version : versions) {
            System.out.printf("%s\t%s\tbuild %d%n",
                    version.getId(), version.getName(), version.getBuild());
        }
        return OK;
    }

    /// Launches one instance and waits for it.
    private static int launch(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("xenon: --launch needs an instance id");
            return USAGE;
        }
        XenonGameRepository repo = repository();
        MindustryVersion version = find(repo, args[1]);
        if (version == null) {
            System.err.println("xenon: instance not found: " + args[1]);
            return FAILURE;
        }
        LaunchOptions options = MindustryLaunchService.buildLaunchOptions(repo, version);
        ProcessBuilder builder = new ProcessBuilder(options.buildCommandLine()).inheritIO();
        builder.directory(options.getWorkingDirectory().toFile());
        builder.environment().put("MINDUSTRY_DATA_DIR",
                options.getDataDir().toAbsolutePath().toString());
        Process process = builder.start();
        return process.waitFor();
    }

    /// Exports one instance as a Xenon modpack.
    private static int export(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("xenon: --export needs an instance id and an output file");
            return USAGE;
        }
        XenonGameRepository repo = repository();
        MindustryVersion version = find(repo, args[1]);
        if (version == null) {
            System.err.println("xenon: instance not found: " + args[1]);
            return FAILURE;
        }
        Path target = Path.of(args[2]).toAbsolutePath().normalize();
        XenonModpackPacker.pack(repo, version, target);
        System.out.println(target);
        return OK;
    }

    /// Reads one whitelisted setting.
    private static int get(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("xenon: --get needs a key");
            return USAGE;
        }
        initConfig();
        return switch (args[1]) {
            case "download.source" -> {
                System.out.println(ConfigHolder.globalConfig().getMindustryDownloadSource().name());
                yield OK;
            }
            case "download.speed" -> {
                System.out.println(ConfigHolder.globalConfig().getMindustryDownloadSpeedKbps());
                yield OK;
            }
            case "github.token" -> {
                System.out.println(ConfigHolder.config().getGithubToken());
                yield OK;
            }
            default -> unknownKey(args[1]);
        };
    }

    /// Writes one whitelisted setting.
    private static int set(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("xenon: --set needs a key and a value");
            return USAGE;
        }
        initConfig();
        return switch (args[1]) {
            case "download.source" -> {
                try {
                    ConfigHolder.globalConfig().setMindustryDownloadSource(
                            MindustryDownloadSource.valueOf(args[2].toUpperCase()));
                } catch (IllegalArgumentException e) {
                    System.err.println("xenon: valid sources are "
                            + java.util.Arrays.toString(MindustryDownloadSource.values()));
                    yield USAGE;
                }
                yield OK;
            }
            case "download.speed" -> {
                try {
                    ConfigHolder.globalConfig().setMindustryDownloadSpeedKbps(
                            Integer.parseInt(args[2]));
                } catch (NumberFormatException e) {
                    System.err.println("xenon: download.speed needs an integer");
                    yield USAGE;
                }
                yield OK;
            }
            case "github.token" -> {
                ConfigHolder.config().setGithubToken(args[2]);
                yield OK;
            }
            default -> unknownKey(args[1]);
        };
    }

    /// Reports an unknown setting key.
    private static int unknownKey(String key) {
        System.err.println("xenon: unknown setting key " + key);
        return USAGE;
    }

    /// Loads the launcher configuration.
    private static void initConfig() throws IOException {
        Files.createDirectories(Metadata.XENON_GLOBAL_DIRECTORY);
        try {
            ConfigHolder.init();
        } catch (IllegalStateException e) {
            // Already loaded by an earlier command in the same process.
        }
    }

    /// Finds one instance by id or display name.
    private static @Nullable MindustryVersion find(XenonGameRepository repo, String id) {
        MindustryVersion byId = repo.get(id).orElse(null);
        if (byId != null) {
            return byId;
        }
        return repo.all().stream()
                .filter(version -> id.equals(version.getName()))
                .findFirst().orElse(null);
    }

    /// Opens the repository selected by the current profile.
    private static XenonGameRepository repository() throws IOException {
        XenonGameRepository repo = new XenonGameRepository(versionsRoot());
        repo.refresh();
        return repo;
    }

    /// Resolves the versions root from the minimal config reader.
    private static Path versionsRoot() {
        Path config = configFile();
        if (config != null && Files.isRegularFile(config)) {
            try {
                JsonObject root = JsonParser.parseString(
                        Files.readString(config, StandardCharsets.UTF_8)).getAsJsonObject();
                String selected = stringOf(root, "selectedProfile");
                JsonObject configurations = root.has("configurations")
                        && root.get("configurations").isJsonObject()
                        ? root.getAsJsonObject("configurations") : null;
                if (configurations != null && !selected.isBlank()
                        && configurations.has(selected)
                        && configurations.get(selected).isJsonObject()) {
                    String gameDir = stringOf(
                            configurations.getAsJsonObject(selected), "gameDir");
                    if (!gameDir.isBlank()) {
                        return Path.of(gameDir).resolve("versions");
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // Fall back to the global versions directory.
            }
        }
        return Metadata.getVersionsDirectory();
    }

    /// Locates the launcher config file.
    private static @Nullable Path configFile() {
        for (String name : List.of("xenon.json", ".xenon.json")) {
            Path candidate = Metadata.XENON_GLOBAL_DIRECTORY.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /// Reads a string field.
    private static String stringOf(JsonObject object, String key) {
        var value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private XenonCli() {
    }
}
