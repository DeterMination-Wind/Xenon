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
package determination.xenon.netplay;

import determination.xenon.mindustry.download.GitHubAsset;
import determination.xenon.mindustry.download.GitHubRelease;
import determination.xenon.mindustry.download.GitHubReleaseClient;
import determination.xenon.mindustry.download.ProgressCallback;
import determination.xenon.util.io.Unzipper;
import determination.xenon.util.logging.Logger;
import determination.xenon.util.platform.Architecture;
import determination.xenon.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/// Manages a managed EasyTier installation and one P2P room process.
///
/// EasyTier builds a virtual LAN between machines that share a network name
/// and secret. Xenon derives both from a six-digit room code, connects
/// through EasyTier's public shared nodes, and leaves the actual game
/// hosting/joining to Mindustry itself.
///
/// The binary is downloaded on demand (never bundled) and stored under
/// `<installRoot>/<tag>/`; the running room is a child process so stopping
/// the launcher or the room always cleans it up.
@NotNullByDefault
public final class EasyTierRuntime {

    /// Upstream repository that publishes EasyTier builds.
    public static final String UPSTREAM_REPO = "EasyTier/EasyTier";

    /// Official public shared nodes used for peer discovery and relay.
    public static final @Unmodifiable List<String> PUBLIC_PEERS = List.of(
            "tcp://public.easytier.cn:11010",
            "udp://public.easytier.cn:11010");

    /// Default Mindustry server port; the room UI offers `<ip>:6567`.
    public static final int MINDUSTRY_PORT = 6567;

    /// Room codes are exactly six digits.
    private static final Pattern ROOM_PATTERN = Pattern.compile("\\d{6}");

    /// How long the CLI may take before it is considered hung.
    private static final long CLI_TIMEOUT_SECONDS = 8L;

    private final Path installRoot;
    private final @Nullable Path cachesRoot;

    private @Nullable Process process;
    private volatile @Nullable String roomCode;

    /// Creates a runtime rooted at `installRoot`.
    ///
    /// @param installRoot directory that holds downloaded EasyTier builds
    /// @param cachesRoot  launcher caches directory, or `null`
    public EasyTierRuntime(Path installRoot, @Nullable Path cachesRoot) {
        this.installRoot = Objects.requireNonNull(installRoot, "installRoot");
        this.cachesRoot = cachesRoot;
    }

    /// One peer row from `easytier-cli peer`.
    ///
    /// @param ipv4       virtual IPv4 address, possibly empty for relay rows
    /// @param hostname   peer hostname
    /// @param cost       route cost, `Local` for this machine
    /// @param latencyMs  latency column, or `*` when unknown
    /// @param natType    NAT type column
    public record Peer(String ipv4, String hostname, String cost, String latencyMs, String natType) {
        /// Whether this row describes the local node.
        public boolean local() {
            return "local".equalsIgnoreCase(cost);
        }
    }

    /// The installed EasyTier version, or empty when nothing is installed.
    public Optional<String> installedVersion() {
        return Optional.ofNullable(latestVersionDir())
                .map(dir -> dir.getFileName().toString());
    }

    /// Whether EasyTier binaries are present.
    public boolean isInstalled() {
        return coreBinary() != null;
    }

    /// Downloads and extracts the latest EasyTier build for this platform.
    ///
    /// @param progress optional download progress callback
    /// @return the installed version tag
    /// @throws IOException when the platform is unsupported or the download fails
    public String install(@Nullable ProgressCallback progress) throws IOException {
        GitHubReleaseClient client = new GitHubReleaseClient(cachesRoot);
        GitHubRelease release = client.getLatestRelease(UPSTREAM_REPO);
        if (release == null || release.getTagName().isBlank()) {
            throw new IOException("EasyTier has no published release");
        }
        String assetName = pickAssetName(OperatingSystem.CURRENT_OS, Architecture.SYSTEM_ARCH,
                release.getAssets());
        if (assetName == null) {
            throw new IOException("No EasyTier build for " + OperatingSystem.CURRENT_OS.getCheckedName()
                    + "/" + Architecture.SYSTEM_ARCH);
        }
        GitHubAsset asset = release.getAssets().stream()
                .filter(candidate -> assetName.equals(candidate.getName()))
                .findFirst()
                .orElseThrow(() -> new IOException("EasyTier asset disappeared: " + assetName));

        Files.createDirectories(installRoot);
        Path versionDir = installRoot.resolve(release.getTagName());
        Path zip = installRoot.resolve(assetName + ".download");
        try {
            client.downloadAsset(asset, zip, progress);
            // Re-installations replace the previous extraction.
            new Unzipper(zip, versionDir).setReplaceExistentFile(true).unzip();
        } finally {
            Files.deleteIfExists(zip);
        }
        makeExecutable(versionDir);
        if (coreBinary(versionDir) == null) {
            throw new IOException("EasyTier archive did not contain easytier-core");
        }
        Logger.LOG.info("Installed EasyTier " + release.getTagName() + " into " + versionDir);
        return release.getTagName();
    }

    /// Whether a room process is currently running.
    public boolean isRunning() {
        Process current = process;
        return current != null && current.isAlive();
    }

    /// The room code of the running room, if any.
    public @Nullable String currentRoom() {
        return roomCode;
    }

    /// Starts a room: joins the virtual network derived from `roomCode`.
    ///
    /// @param roomCode six-digit room code shared with the other players
    /// @throws IOException when EasyTier is missing, the code is invalid, or
    ///                     the process dies during startup (for example for
    ///                     lack of TUN privileges)
    public void start(String roomCode) throws IOException {
        Objects.requireNonNull(roomCode, "roomCode");
        if (!ROOM_PATTERN.matcher(roomCode).matches()) {
            throw new IOException("Room code must be six digits");
        }
        if (isRunning()) {
            throw new IOException("A room is already running");
        }
        Path core = coreBinary();
        if (core == null) {
            throw new IOException("EasyTier is not installed");
        }
        Path logDir = installRoot.resolve("logs");
        Files.createDirectories(logDir);
        Path logFile = logDir.resolve("room-" + roomCode + ".log");

        List<String> command = new ArrayList<>();
        command.add(core.toString());
        command.add("--network-name");
        command.add("xenon-" + roomCode);
        command.add("--network-secret");
        command.add(deriveSecret(roomCode));
        for (String peer : PUBLIC_PEERS) {
            command.add("-p");
            command.add(peer);
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());
        Process started = builder.start();
        try {
            if (!started.waitFor(2, TimeUnit.SECONDS) || started.isAlive()) {
                process = started;
                this.roomCode = roomCode;
                Logger.LOG.info("EasyTier room " + roomCode + " started (log: " + logFile + ")");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            started.destroyForcibly();
            throw new IOException("Interrupted while starting EasyTier", e);
        }
        // The process exited immediately: surface the tail of its output.
        started.destroyForcibly();
        throw new IOException("EasyTier exited during startup: " + tail(logFile));
    }

    /// Stops the room process, if one is running.
    public void stop() {
        Process current = process;
        process = null;
        roomCode = null;
        if (current == null) {
            return;
        }
        current.destroy();
        try {
            if (!current.waitFor(3, TimeUnit.SECONDS)) {
                current.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
        Logger.LOG.info("EasyTier room stopped");
    }

    /// Runs `easytier-cli peer` and parses the table.
    ///
    /// @return one entry per row of the peer table
    /// @throws IOException when the CLI is missing, hangs or fails
    public List<Peer> peers() throws IOException {
        Path cli = cliBinary();
        if (cli == null) {
            throw new IOException("EasyTier is not installed");
        }
        ProcessBuilder builder = new ProcessBuilder(cli.toString(), "peer");
        builder.redirectErrorStream(true);
        Process cliProcess = builder.start();
        try {
            if (!cliProcess.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                cliProcess.destroyForcibly();
                throw new IOException("easytier-cli peer timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cliProcess.destroyForcibly();
            throw new IOException("Interrupted running easytier-cli", e);
        }
        String output = new String(cliProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return parsePeers(output);
    }

    /// The local virtual IPv4 address reported by the peer table.
    ///
    /// @return the address, or `null` while the network is still coming up
    /// @throws IOException when the CLI cannot be queried
    public @Nullable String virtualIp() throws IOException {
        for (Peer peer : peers()) {
            if (peer.local() && !peer.ipv4().isBlank()) {
                return peer.ipv4();
            }
        }
        return null;
    }

    /// Parses the `easytier-cli peer` table.
    ///
    /// @param output raw CLI output, or `null`
    /// @return parsed peers; malformed rows are skipped
    public static List<Peer> parsePeers(@Nullable String output) {
        List<Peer> peers = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return peers;
        }
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("|")) {
                continue;
            }
            if (trimmed.contains("---") || trimmed.contains("ipv4")
                    || trimmed.contains("hostname")) {
                continue;
            }
            String[] cells = trimmed.split("\\|", -1);
            String ipv4 = cell(cells, 1);
            String hostname = cell(cells, 2);
            String cost = cell(cells, 3);
            String latency = cell(cells, 4);
            String natType = cell(cells, 8);
            if (ipv4.isBlank() && hostname.isBlank()) {
                continue;
            }
            peers.add(new Peer(ipv4, hostname, cost, latency, natType));
        }
        return peers;
    }

    /// Derives the deterministic network secret from a room code.
    ///
    /// Anyone who knows the room code can derive the same secret, which is
    /// exactly the sharing model of a numeric room code.
    ///
    /// @param roomCode six-digit room code
    /// @return hex secret
    public static String deriveSecret(String roomCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("xenon-netplay:" + roomCode).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                builder.append(String.format(Locale.ROOT, "%02x", hash[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory for every JVM; fall back to a plain value.
            return "xenon-" + roomCode;
        }
    }

    /// Picks the EasyTier asset for one platform.
    ///
    /// @param os     operating system
    /// @param arch   CPU architecture
    /// @param assets assets of the release
    /// @return the asset name, or `null` when the platform is unsupported
    public static @Nullable String pickAssetName(OperatingSystem os, Architecture arch,
                                                 @Nullable List<GitHubAsset> assets) {
        if (assets == null || assets.isEmpty()) {
            return null;
        }
        String platform = switch (os) {
            case WINDOWS -> "windows";
            case LINUX -> "linux";
            case MACOS -> "macos";
            default -> null;
        };
        if (platform == null) {
            return null;
        }
        String suffix = switch (arch) {
            case X86_64 -> "x86_64";
            case X86 -> "windows".equals(platform) ? "i686" : null;
            case ARM64 -> "windows".equals(platform) ? "arm64" : "aarch64";
            case ARM32 -> "linux".equals(platform) ? "armv7hf" : null;
            default -> null;
        };
        if (suffix == null) {
            return null;
        }
        String prefix = "easytier-" + platform + "-" + suffix + "-";
        for (GitHubAsset asset : assets) {
            String name = asset.getName();
            if (name != null && name.startsWith(prefix) && name.endsWith(".zip")) {
                return name;
            }
        }
        return null;
    }

    /// The `easytier-core` binary of the newest installation, or `null`.
    private @Nullable Path coreBinary() {
        Path dir = latestVersionDir();
        return dir == null ? null : coreBinary(dir);
    }

    /// The `easytier-cli` binary of the newest installation, or `null`.
    private @Nullable Path cliBinary() {
        Path dir = latestVersionDir();
        if (dir == null) {
            return null;
        }
        Path binary = dir.resolve(cliBinaryName());
        return Files.isRegularFile(binary) ? binary : null;
    }

    /// Resolves `easytier-core` inside one installation directory.
    private static @Nullable Path coreBinary(Path versionDir) {
        Path binary = versionDir.resolve(coreBinaryName());
        return Files.isRegularFile(binary) ? binary : null;
    }

    /// Newest installation directory that contains the core binary.
    private @Nullable Path latestVersionDir() {
        if (!Files.isDirectory(installRoot)) {
            return null;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(installRoot)) {
            return java.util.stream.StreamSupport.stream(stream.spliterator(), false)
                    .filter(Files::isDirectory)
                    .filter(dir -> coreBinary(dir) != null)
                    .max(Comparator.comparingLong(EasyTierRuntime::lastModified))
                    .orElse(null);
        } catch (IOException e) {
            Logger.LOG.warning("Cannot scan the EasyTier install directory: " + e.getMessage());
            return null;
        }
    }

    /// Last-modified stamp of one installation directory.
    private static long lastModified(Path dir) {
        try {
            return Files.getLastModifiedTime(dir).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /// Marks the EasyTier executables runnable on POSIX systems.
    private static void makeExecutable(Path versionDir) {
        for (String name : List.of("easytier-core", "easytier-cli")) {
            Path binary = versionDir.resolve(name);
            if (Files.isRegularFile(binary)) {
                //noinspection ResultOfMethodCallIgnored
                binary.toFile().setExecutable(true, false);
            }
        }
    }

    /// Platform-specific core binary name.
    private static String coreBinaryName() {
        return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                ? "easytier-core.exe"
                : "easytier-core";
    }

    /// Platform-specific CLI binary name.
    private static String cliBinaryName() {
        return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                ? "easytier-cli.exe"
                : "easytier-cli";
    }

    /// Returns one `|`-separated cell, trimmed, or an empty string.
    private static String cell(String[] cells, int index) {
        if (index >= cells.length) {
            return "";
        }
        return cells[index].trim();
    }

    /// Reads the last few lines of a log file for an error message.
    private static String tail(Path logFile) {
        try {
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 6);
            return String.join(" ", lines.subList(from, lines.size()));
        } catch (IOException e) {
            return "(no output)";
        }
    }

    /// Matches a room code; used by the UI to validate input.
    ///
    /// @param roomCode candidate code
    /// @return whether the code is six digits
    public static boolean isValidRoomCode(@Nullable String roomCode) {
        return roomCode != null && ROOM_PATTERN.matcher(roomCode).matches();
    }
}
