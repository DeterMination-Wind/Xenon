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

import determination.xenon.Metadata;
import determination.xenon.mindustry.save.MindustryLaunchSaveService;
import determination.xenon.mindustry.uuid.MindustryPlayerLaunchHook;
import determination.xenon.mindustry.uuid.MindustrySettingsBin;
import determination.xenon.mindustry.uuid.UuidProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-level glue: import a local jar into the version registry, then
 * launch it through {@link XenonLauncher}. Keeps the UI side simple —
 * a single async call wires up everything.
 */
public final class MindustryLaunchService {

    /**
     * Heuristic: pull a "build-XYZ" or numeric identifier out of a
     * Mindustry-shaped jar filename. Falls back to {@code 0} when the
     * jar does not follow any familiar naming convention.
     */
    private static final Pattern BUILD_PATTERN = Pattern.compile("(?i)(?:build[-_])?(\\d{2,4})");

    private MindustryLaunchService() {
    }

    /**
     * Sniff a Mindustry jar's build number from its filename.
     * Returns 0 when nothing recognizable is found.
     */
    public static int sniffBuildNumber(Path jar) {
        if (jar == null) return 0;
        String name = jar.getFileName().toString();
        Matcher m = BUILD_PATTERN.matcher(name);
        int best = 0;
        while (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v > best && v < 10000) best = v;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return best;
    }

    /** Best-effort variant detection from a jar filename. */
    public static VersionVariant sniffVariant(Path jar) {
        if (jar == null) return VersionVariant.CUSTOM;
        String n = jar.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.contains("mindustryx") || n.contains("mindustry-x")) return VersionVariant.MINDUSTRY_X;
        if (n.contains("cn-arc") || n.contains("cn_arc")) return VersionVariant.CN_ARC;
        if (n.contains("foo")) return VersionVariant.FOO;
        if (n.contains("be-desktop") || n.contains("-be-")) return VersionVariant.BE;
        if (n.contains("mindustry")) return VersionVariant.VANILLA;
        return VersionVariant.CUSTOM;
    }

    /**
     * Copy {@code jar} into {@code <config>/versions/<id>/<id>.jar} and
     * register a {@link MindustryVersion}. Caller can pre-fill any
     * fields it has better information for; everything blank is sniffed
     * from the file.
     */
    public static MindustryVersion importLocalJar(XenonGameRepository repo,
                                                  Path jar,
                                                  String id,
                                                  MindustryVersion template) throws IOException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("version id required");
        }
        if (jar == null || !Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("jar not found: " + jar);
        }

        Path versionRoot = repo.getVersionRoot(id);
        Files.createDirectories(versionRoot);
        Path targetJar = versionRoot.resolve(id + ".jar");
        Files.copy(jar, targetJar, StandardCopyOption.REPLACE_EXISTING);

        MindustryVersion v = template != null ? template : new MindustryVersion();
        v.setId(id);
        if (v.getName() == null || v.getName().isBlank()) v.setName(id);
        if (v.getVariant() == VersionVariant.CUSTOM) v.setVariant(sniffVariant(jar));
        if (v.getBuild() <= 0) v.setBuild(sniffBuildNumber(jar));
        if (v.getJarPath() == null || v.getJarPath().isBlank()) v.setJarPath(id + ".jar");
        if (v.getJavaReq() <= 0) v.setJavaReq(v.getBuild() > 0 && v.getBuild() < 140 ? 8 : 17);

        repo.save(v);
        return v;
    }

    /** Build {@link LaunchOptions} for an installed version. */
    public static LaunchOptions buildLaunchOptions(XenonGameRepository repo,
                                                   MindustryVersion version) throws IOException, InterruptedException {
        return buildLaunchOptions(repo, version, null);
    }

    /**
     * Build {@link LaunchOptions} and inject {@code -Dmindustry.player.*}
     * for the given player profile. {@code playerProfile == null} omits
     * the hook entirely (the engine then keeps whatever is in its own
     * settings file, fresh-random on first run).
     */
    public static LaunchOptions buildLaunchOptions(XenonGameRepository repo,
                                                   MindustryVersion version,
                                                   UuidProfile playerProfile) throws IOException, InterruptedException {
        Path versionRoot = repo.getVersionRoot(version.getId());
        Path jar = version.resolveJar(versionRoot);
        if (!Files.isRegularFile(jar)) {
            throw new IOException("Mindustry jar missing: " + jar);
        }
        Path java = MindustryJavaPicker.resolveJavaExecutable(version, versionRoot);
        String launchSaveFile = version.getLaunchSaveFile();
        if (launchSaveFile != null && !launchSaveFile.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            version.setLaunchSaveFile(null);
            repo.save(version);
            launchSaveFile = null;
        }
        Path dataDir = MindustryLaunchSaveService.prepare(
                versionRoot,
                version.resolveDataDir(versionRoot),
                launchSaveFile);

        LaunchOptions.Builder builder = LaunchOptions.builder()
                .javaExecutable(java)
                .jar(jar)
                .workingDirectory(version.resolveWorkingDirectory(versionRoot))
                .dataDir(dataDir)
                .jvmArgs(LaunchOptions.tokenize(version.getJvmArgs()))
                .gameArgs(LaunchOptions.tokenize(version.getGameArgs()));

        if (playerProfile != null
                && playerProfile.uuid != null && !playerProfile.uuid.isBlank()
                && playerProfile.nickname != null && !playerProfile.nickname.isBlank()) {
            // The JVM-property hook only works if the user has the
            // companion uidManager mod installed. Vanilla / MindustryX
            // both ignore those -D args, so write the values directly
            // into settings.bin too — that's the source of truth the
            // engine reads on startup regardless of mods.
            MindustryPlayerLaunchHook.applyTo(builder, playerProfile);
            MindustrySettingsBin.setPlayerProfile(dataDir,
                    playerProfile.uuid, playerProfile.nickname);
        }
        return builder.build();
    }

    /** {@code <config>/versions} convenience accessor. */
    public static Path defaultVersionsRoot() {
        return Metadata.getVersionsDirectory();
    }
}
