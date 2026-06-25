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
package determination.xenon.mindustry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import determination.xenon.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Detects existing desktop Mindustry installations that are not already in
 * Xenon's managed {@code versions/} layout.
 *
 * <p>The important target is Steam on Windows:
 * {@code Mindustry/Mindustry.json} points at {@code jre/desktop.jar}, while
 * the real data directory is {@code Mindustry/saves/}. Registering that
 * directory as one normal {@link MindustryVersion} lets the existing Mindustry
 * management UI handle mods, saves, schematics and launch.</p>
 */
@NotNullByDefault
public final class MindustryInstallationDiscovery {
    private static final String MINDUSTRY_JSON = "Mindustry.json";
    private static final String DEFAULT_STEAM_ID = "steam-mindustry";
    private static final String DEFAULT_STEAM_NAME = "Steam Mindustry";
    private static final @Unmodifiable List<String> FALLBACK_JAR_PATHS = List.of(
            "jre/desktop.jar",
            "desktop.jar",
            "Mindustry.jar",
            "mindustry.jar"
    );

    private MindustryInstallationDiscovery() {
    }

    /**
     * Try to interpret {@code directory} as a desktop Mindustry installation.
     *
     * @return installation metadata, or empty if the directory does not contain
     *         a runnable Mindustry jar.
     */
    public static Optional<DiscoveredInstallation> discover(@Nullable Path directory) {
        if (directory == null) {
            return Optional.empty();
        }
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }

        Optional<JsonObject> launcherConfig = readLauncherConfig(root.resolve(MINDUSTRY_JSON));
        Optional<Path> jar = findJar(root, launcherConfig.orElse(null));
        if (jar.isEmpty()) {
            return Optional.empty();
        }

        Path dataDir = findDataDir(root);
        boolean steam = isSteamInstall(root, dataDir);
        BuildMetadata buildMetadata = readBuildMetadata(jar.get());
        @Nullable Path javaHome = findJavaHome(root, launcherConfig.orElse(null)).orElse(null);
        List<String> jvmArgs = readJvmArgs(launcherConfig.orElse(null));

        return Optional.of(new DiscoveredInstallation(
                steam ? DEFAULT_STEAM_ID : sanitizeId(root.getFileName().toString()),
                steam ? DEFAULT_STEAM_NAME : root.getFileName().toString(),
                root,
                jar.get(),
                javaHome,
                dataDir,
                root,
                buildMetadata.variant(),
                buildMetadata.build(),
                buildMetadata.buildType(),
                jvmArgs
        ));
    }

    private static Optional<JsonObject> readLauncherConfig(Path json) {
        if (!Files.isRegularFile(json)) {
            return Optional.empty();
        }
        try {
            JsonElement element = JsonParser.parseString(Files.readString(json));
            return element.isJsonObject() ? Optional.of(element.getAsJsonObject()) : Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Path> findJar(Path root, @Nullable JsonObject launcherConfig) {
        if (launcherConfig != null) {
            JsonElement classPath = launcherConfig.get("classPath");
            if (classPath instanceof JsonArray array) {
                for (JsonElement element : array) {
                    if (!element.isJsonPrimitive()) {
                        continue;
                    }
                    String raw = element.getAsString();
                    if (!raw.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                        continue;
                    }
                    Path jar = resolveInstallPath(root, raw);
                    if (Files.isRegularFile(jar)) {
                        return Optional.of(jar);
                    }
                }
            }
        }

        for (String candidate : FALLBACK_JAR_PATHS) {
            Path jar = resolveInstallPath(root, candidate);
            if (Files.isRegularFile(jar)) {
                return Optional.of(jar);
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> findJavaHome(Path root, @Nullable JsonObject launcherConfig) {
        if (launcherConfig != null) {
            JsonElement jrePath = launcherConfig.get("jrePath");
            if (jrePath != null && jrePath.isJsonPrimitive()) {
                Path home = resolveInstallPath(root, jrePath.getAsString());
                if (hasJavaExecutable(home)) {
                    return Optional.of(home);
                }
            }
        }

        Path bundled = root.resolve("jre").toAbsolutePath().normalize();
        return hasJavaExecutable(bundled) ? Optional.of(bundled) : Optional.empty();
    }

    private static boolean hasJavaExecutable(Path javaHome) {
        return Files.isRegularFile(javaHome.resolve("bin").resolve(OperatingSystem.CURRENT_OS.getJavaExecutable()));
    }

    private static List<String> readJvmArgs(@Nullable JsonObject launcherConfig) {
        if (launcherConfig == null) {
            return List.of();
        }
        JsonElement vmArgs = launcherConfig.get("vmArgs");
        if (!(vmArgs instanceof JsonArray array)) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) {
                String value = element.getAsString();
                if (!value.isBlank()) {
                    args.add(value);
                }
            }
        }
        return List.copyOf(args);
    }

    private static Path findDataDir(Path root) {
        Path steamDataDir = root.resolve("saves").toAbsolutePath().normalize();
        if (Files.isDirectory(steamDataDir)) {
            return steamDataDir;
        }
        return MindustryVersion.defaultMindustryDataDir();
    }

    private static boolean isSteamInstall(Path root, Path dataDir) {
        return Files.isRegularFile(root.resolve("steam_api64.dll"))
                || Files.isRegularFile(root.resolve("steam_appid.txt"))
                || Files.isRegularFile(dataDir.resolve("steam_autocloud.vdf"));
    }

    private static BuildMetadata readBuildMetadata(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("version.properties");
            if (entry == null) {
                return BuildMetadata.unknown();
            }
            Properties properties = new Properties();
            try (InputStream input = zip.getInputStream(entry)) {
                properties.load(input);
            }
            int build = parseBuild(properties.getProperty("build"));
            String type = properties.getProperty("type", "");
            String modifier = properties.getProperty("modifier", "");
            VersionVariant variant = modifier.toLowerCase(Locale.ROOT).contains("be")
                    ? VersionVariant.BE
                    : VersionVariant.VANILLA;
            String buildType = type.equalsIgnoreCase("official") ? "stable" : type.toLowerCase(Locale.ROOT);
            if (buildType.isBlank()) {
                buildType = "custom";
            }
            return new BuildMetadata(build, buildType, variant);
        } catch (IOException ignored) {
            return BuildMetadata.unknown();
        }
    }

    private static int parseBuild(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        int dot = raw.indexOf('.');
        String major = dot >= 0 ? raw.substring(0, dot) : raw;
        try {
            return Integer.parseInt(major);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static Path resolveInstallPath(Path root, String raw) {
        Path path = Path.of(raw);
        return (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
    }

    private static String sanitizeId(String raw) {
        String sanitized = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9._-]", "-");
        return sanitized.isBlank() ? "mindustry" : sanitized;
    }

    /** Metadata extracted from a discovered installation. */
    public static final class DiscoveredInstallation {
        private final String suggestedId;
        private final String displayName;
        private final Path root;
        private final Path jar;
        private final @Nullable Path javaHome;
        private final Path dataDir;
        private final Path workingDirectory;
        private final VersionVariant variant;
        private final int build;
        private final String buildType;
        private final @Unmodifiable List<String> jvmArgs;

        /** Create immutable metadata for one discovered Mindustry installation. */
        public DiscoveredInstallation(String suggestedId,
                                      String displayName,
                                      Path root,
                                      Path jar,
                                      @Nullable Path javaHome,
                                      Path dataDir,
                                      Path workingDirectory,
                                      VersionVariant variant,
                                      int build,
                                      String buildType,
                                      List<String> jvmArgs) {
            this.suggestedId = suggestedId;
            this.displayName = displayName;
            this.root = root;
            this.jar = jar;
            this.javaHome = javaHome;
            this.dataDir = dataDir;
            this.workingDirectory = workingDirectory;
            this.variant = variant;
            this.build = build;
            this.buildType = buildType;
            this.jvmArgs = List.copyOf(jvmArgs);
        }

        /** Suggested stable Xenon instance id. */
        public String getSuggestedId() { return suggestedId; }

        /** User-facing instance name. */
        public String getDisplayName() { return displayName; }

        /** Installation root directory. */
        public Path getRoot() { return root; }

        /** Runnable Mindustry desktop jar. */
        public Path getJar() { return jar; }

        /** Bundled Java home, or {@code null} to use Xenon's picker. */
        public @Nullable Path getJavaHome() { return javaHome; }

        /** Data directory containing saves, mods, maps and settings. */
        public Path getDataDir() { return dataDir; }

        /** Process working directory to use when launching this jar. */
        public Path getWorkingDirectory() { return workingDirectory; }

        /** Detected Mindustry variant. */
        public VersionVariant getVariant() { return variant; }

        /** Detected build number, or 0 if unknown. */
        public int getBuild() { return build; }

        /** Detected build type such as {@code stable} or {@code custom}. */
        public String getBuildType() { return buildType; }

        /** JVM arguments supplied by the installation launcher metadata. */
        public @Unmodifiable List<String> getJvmArgs() { return jvmArgs; }
    }

    private record BuildMetadata(int build, String buildType, VersionVariant variant) {
        private static BuildMetadata unknown() {
            return new BuildMetadata(0, "custom", VersionVariant.CUSTOM);
        }
    }
}
