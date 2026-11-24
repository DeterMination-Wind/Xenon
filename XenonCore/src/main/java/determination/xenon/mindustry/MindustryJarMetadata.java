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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/// Identity of one Mindustry client jar, read from the jar itself.
///
/// Mindustry's build writes {@code version.properties} into the jar root with
/// {@code number} (major version), {@code build} (numeric build id),
/// {@code type} (for example {@code official} or {@code bleeding-edge}),
/// {@code modifier}, {@code buildDate} and {@code commitHash}. The desktop
/// launcher also declares {@code Main-Class} in the jar manifest.
///
/// Importing a jar used to guess the build from the file name, which assigns
/// the wrong Java requirement (and therefore a launch failure) whenever a user
/// renames the download. Reading the properties removes that guess: the file
/// name is only consulted for fork identity (MindustryX / CN-ARC / Foo), which
/// those builds do not encode in {@code version.properties}.
///
/// The reader is deliberately forgiving: a jar without
/// {@code version.properties} or without a manifest still produces a metadata
/// object with the missing fields set to {@code null}, so a custom launcher jar
/// can still be imported.
///
/// @param build               numeric build id from {@code version.properties}, or `null`
/// @param versionNumber       major game version ({@code 7}, {@code 8}, ...), or `null`
/// @param type                raw {@code type} value ({@code official}, {@code bleeding-edge}, ...), or `null`
/// @param modifier            raw {@code modifier} value ({@code release}, {@code beta}, ...), or `null`
/// @param buildDate           human-readable build date, or `null`
/// @param commitHash          upstream commit hash when the build recorded one, or `null`
/// @param mainClass           {@code Main-Class} from the manifest, or `null`
/// @param hasVersionProperties whether the jar carried a readable {@code version.properties}
@NotNullByDefault
public record MindustryJarMetadata(
        @Nullable Integer build,
        @Nullable Integer versionNumber,
        @Nullable String type,
        @Nullable String modifier,
        @Nullable String buildDate,
        @Nullable String commitHash,
        @Nullable String mainClass,
        boolean hasVersionProperties
) {

    /// Properties entry Mindustry's build writes into the jar root.
    public static final String VERSION_PROPERTIES = "version.properties";

    /// Largest accepted {@code version.properties}; anything bigger is not a Mindustry build.
    private static final int MAX_PROPERTIES_BYTES = 64 * 1024;

    /// Reads the identity of `jar` without extracting it.
    ///
    /// @param jar path to a jar file
    /// @return parsed metadata; absent fields are `null`
    /// @throws IOException when the file is missing, unreadable or not a zip/jar
    public static MindustryJarMetadata read(Path jar) throws IOException {
        Objects.requireNonNull(jar, "jar");
        if (!Files.isRegularFile(jar)) {
            throw new IOException("Jar not found: " + jar);
        }

        Properties props = new Properties();
        boolean hasProps = false;
        try (JarFile file = new JarFile(jar.toFile())) {
            @Nullable JarEntry entry = file.getJarEntry(VERSION_PROPERTIES);
            if (entry != null && (entry.getSize() < 0 || entry.getSize() <= MAX_PROPERTIES_BYTES)) {
                try (InputStream in = file.getInputStream(entry)) {
                    // Bound the read as well: a zip entry may report an unknown
                    // size, and a crafted archive must not be able to inflate an
                    // unbounded properties document into memory.
                    byte[] bytes = in.readNBytes(MAX_PROPERTIES_BYTES + 1);
                    if (bytes.length <= MAX_PROPERTIES_BYTES) {
                        props.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
                        hasProps = true;
                    }
                }
            }
            @Nullable String main = mainClassOf(file);
            return new MindustryJarMetadata(
                    intOrNull(props.getProperty("build")),
                    intOrNull(props.getProperty("number")),
                    blankToNull(props.getProperty("type")),
                    blankToNull(props.getProperty("modifier")),
                    blankToNull(props.getProperty("buildDate")),
                    blankToNull(props.getProperty("commitHash")),
                    main,
                    hasProps);
        }
    }

    /// Whether the manifest declares Mindustry's own launcher class.
    ///
    /// Forks that rename the package or ship their own launcher still start
    /// with {@code mindustry.} for CN-ARC / Foo / MindustryX desktop builds.
    public boolean hasGameMainClass() {
        return mainClass != null && mainClass.startsWith("mindustry.");
    }

    /// Whether the jar looks like a runnable Mindustry client.
    ///
    /// True when the manifest declares a Mindustry main class or the jar
    /// carries {@code version.properties}; a plain library jar is neither.
    public boolean isMindustryClient() {
        return hasGameMainClass() || hasVersionProperties;
    }

    /// Variant hint derived from {@code type}.
    ///
    /// Only the values Mindustry's own build can emit are mapped; forks keep
    /// {@code type=official}, so callers must still fall back to a file-name
    /// sniff for MindustryX / CN-ARC / Foo identity.
    ///
    /// @return the variant implied by {@code type}, or `null` when unknown
    public @Nullable VersionVariant variantHint() {
        if (type == null) {
            return null;
        }
        String t = type.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "official", "stable" -> VersionVariant.VANILLA;
            case "bleeding-edge", "bleedingedge", "be", "snapshot", "nightly" -> VersionVariant.BE;
            case "mindustryx", "mindustry-x", "x" -> VersionVariant.MINDUSTRY_X;
            case "cn-arc", "cn_arc", "arc" -> VersionVariant.CN_ARC;
            case "foo", "fooclient", "foo-client" -> VersionVariant.FOO;
            default -> null;
        };
    }

    /// Build-type label used by [MindustryVersion#setBuildType(String)].
    ///
    /// Mindustry writes {@code release} / {@code beta} / {@code custom build};
    /// the launcher treats anything that is not a real release as `custom` so
    /// the version list does not claim a prerelease badge it cannot verify.
    ///
    /// @return the normalized build type, or `null` when the jar recorded none
    public @Nullable String normalizedBuildType() {
        if (modifier == null) {
            return null;
        }
        String m = modifier.toLowerCase(Locale.ROOT);
        if (m.contains("release") || m.contains("stable")) {
            return "release";
        }
        if (m.contains("beta")) {
            return "beta";
        }
        if (m.contains("alpha")) {
            return "alpha";
        }
        return "custom";
    }

    /// Short human-readable description for import dialogs and logs.
    ///
    /// @return for example {@code "Mindustry 8 · build 27771 · bleeding-edge"}
    public String describe() {
        StringBuilder sb = new StringBuilder("Mindustry");
        if (versionNumber != null) {
            sb.append(' ').append(versionNumber);
        }
        if (build != null) {
            sb.append(" · build ").append(build);
        }
        if (type != null) {
            sb.append(" · ").append(type);
        }
        if (!hasVersionProperties) {
            sb.append(" · no version.properties");
        }
        return sb.toString();
    }

    /// Reads {@code Main-Class} from the jar manifest.
    ///
    /// @param file open jar
    /// @return the declared main class, or `null` when the manifest is absent or blank
    private static @Nullable String mainClassOf(JarFile file) {
        try {
            @Nullable Manifest manifest = file.getManifest();
            if (manifest == null) {
                return null;
            }
            Attributes attributes = manifest.getMainAttributes();
            return blankToNull(attributes.getValue(Attributes.Name.MAIN_CLASS));
        } catch (IOException e) {
            return null;
        }
    }

    /// Parses a decimal property, tolerating a non-numeric mindustry build string.
    private static @Nullable Integer intOrNull(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            int value = Integer.parseInt(trimmed);
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /// Returns `null` for blank or absent text.
    private static @Nullable String blankToNull(@Nullable String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
