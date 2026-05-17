/*
 * Xenon Launcher
 * Copyright (C) 2025-2026  Xenon contributors
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
package determination.xenon.terracotta;

import com.google.gson.annotations.SerializedName;
import determination.xenon.Metadata;
import determination.xenon.task.FileDownloadTask;
import determination.xenon.terracotta.provider.AbstractTerracottaProvider;
import determination.xenon.terracotta.provider.GeneralProvider;
import determination.xenon.terracotta.provider.MacOSProvider;
import determination.xenon.util.gson.JsonSerializable;
import determination.xenon.util.gson.JsonUtils;
import determination.xenon.util.i18n.LocaleUtils;
import determination.xenon.util.i18n.LocalizedText;
import determination.xenon.util.io.FileUtils;
import determination.xenon.util.io.NetworkUtils;
import determination.xenon.util.platform.Architecture;
import determination.xenon.util.platform.OSVersion;
import determination.xenon.util.platform.OperatingSystem;
import determination.xenon.util.versioning.VersionNumber;
import determination.xenon.util.versioning.VersionRange;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static determination.xenon.util.logging.Logger.LOG;

public final class TerracottaMetadata {
    private TerracottaMetadata() {
    }

    private record Options(String version, String classifier) {
        public String replace(String value) {
            return value.replace("${version}", version).replace("${classifier}", classifier);
        }
    }

    @JsonSerializable
    public record Link(
            @SerializedName("desc") LocalizedText description,
            @SerializedName("link") String link
    ) {
    }

    @JsonSerializable
    private record Package(
            @SerializedName("hash") String hash,
            @SerializedName("files") Map<String, String> files
    ) {
    }

    @JsonSerializable
    private record Config(
            @SerializedName("version_latest") String latest,

            @SerializedName("packages") Map<String, Package> pkgs,
            @SerializedName("downloads") List<String> downloads,
            @SerializedName("downloads_CN") List<String> downloadsCN,
            @SerializedName("links") List<Link> links
    ) {
        private @Nullable TerracottaBundle resolve(Options options) {
            Package pkg = pkgs.get(options.classifier);
            if (pkg == null) {
                return null;
            }

            Stream<String> stream = downloads.stream(), streamCN = downloadsCN.stream();
            List<URI> links = (LocaleUtils.IS_CHINA_MAINLAND ? Stream.concat(streamCN, stream) : Stream.concat(stream, streamCN))
                    .map(link -> URI.create(options.replace(link)))
                    .toList();

            Map<String, FileDownloadTask.IntegrityCheck> files = pkg.files.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    entry -> new FileDownloadTask.IntegrityCheck("SHA-512", entry.getValue())
            ));

            return new TerracottaBundle(
                    Metadata.DEPENDENCIES_DIRECTORY.resolve(options.replace("terracotta/${version}")).toAbsolutePath(),
                    links, new FileDownloadTask.IntegrityCheck("SHA-512", pkg.hash),
                    files
            );
        }
    }

    public static final AbstractTerracottaProvider PROVIDER;
    public static final String PACKAGE_NAME;
    public static final List<Link> PACKAGE_LINKS;
    public static final String FEEDBACK_LINK = NetworkUtils.withQuery("https://docs.hmcl.net/multiplayer/feedback.html", Map.of(
            "v", "v1",
            "launcher_version", Metadata.VERSION
    ));

    private static final String LATEST;

    static {
        Config config;
        try (InputStream is = TerracottaMetadata.class.getResourceAsStream("/assets/terracotta.json")) {
            config = JsonUtils.fromNonNullJsonFully(is, Config.class);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }

        LATEST = config.latest;

        Options options = new Options(config.latest, OperatingSystem.CURRENT_OS.getCheckedName() + "-" + Architecture.SYSTEM_ARCH.getCheckedName());
        TerracottaBundle bundle = config.resolve(options);
        AbstractTerracottaProvider provider;
        if (bundle == null || (provider = locateProvider(bundle, options)) == null) {
            PROVIDER = null;
            PACKAGE_NAME = null;
            PACKAGE_LINKS = null;
        } else {
            PROVIDER = provider;
            PACKAGE_NAME = options.replace("terracotta-${version}-${classifier}-pkg.tar.gz");

            List<Link> packageLinks = config.links.stream()
                    .map(link -> new Link(link.description, options.replace(link.link)))
                    .collect(Collectors.toList());
            Collections.shuffle(packageLinks);
            PACKAGE_LINKS = Collections.unmodifiableList(packageLinks);
        }
    }

    @Nullable
    private static AbstractTerracottaProvider locateProvider(TerracottaBundle bundle, Options options) {
        String prefix = options.replace("terracotta-${version}-${classifier}");

        // FIXME: As HMCL is a cross-platform application, developers may mistakenly locate
        //        non-existent files in non-native platform logic without assertion errors during debugging.
        return switch (OperatingSystem.CURRENT_OS) {
            case WINDOWS -> {
                if (!OperatingSystem.SYSTEM_VERSION.isAtLeast(OSVersion.WINDOWS_10))
                    yield null;

                yield new GeneralProvider(bundle, bundle.locate(prefix + ".exe"));
            }
            case LINUX, FREEBSD -> new GeneralProvider(bundle, bundle.locate(prefix));
            case MACOS -> new MacOSProvider(
                    bundle, bundle.locate(prefix), bundle.locate(prefix + ".pkg")
            );
            default -> null;
        };
    }

    public static void removeLegacyVersionFiles() {
        try (DirectoryStream<Path> terracotta = collectLegacyVersionFiles()) {
            if (terracotta == null)
                return;

            for (Path path : terracotta) {
                try {
                    FileUtils.deleteDirectory(path);
                } catch (IOException e) {
                    LOG.warning(String.format("Unable to remove legacy terracotta files: %s", path), e);
                }
            }
        } catch (IOException e) {
            LOG.warning("Unable to remove legacy terracotta files.", e);
        }
    }

    public static boolean hasLegacyVersionFiles() throws IOException {
        try (DirectoryStream<Path> terracotta = collectLegacyVersionFiles()) {
            return terracotta != null && terracotta.iterator().hasNext();
        }
    }

    private static @Nullable DirectoryStream<Path> collectLegacyVersionFiles() throws IOException {
        Path terracottaDir = Metadata.DEPENDENCIES_DIRECTORY.resolve("terracotta");
        if (Files.notExists(terracottaDir))
            return null;

        VersionRange<VersionNumber> range = VersionNumber.atMost(LATEST);
        return Files.newDirectoryStream(terracottaDir, path -> {
            String name = FileUtils.getName(path);
            return !LATEST.equals(name) && range.contains(VersionNumber.asVersion(name));
        });
    }
}
