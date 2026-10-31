/*
 * Xenon Launcher
 * Copyright (C) 2020-2026  Xenon contributors
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
package determination.xenon.upgrade;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import javafx.application.Platform;
import determination.xenon.EntryPoint;
import determination.xenon.Main;
import determination.xenon.Metadata;
import determination.xenon.java.JavaRuntime;
import determination.xenon.task.Task;
import determination.xenon.task.TaskExecutor;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.UpgradeDialog;
import determination.xenon.ui.construct.MessageDialogPane.MessageType;
import determination.xenon.util.FileSaver;
import determination.xenon.util.StringUtils;
import determination.xenon.util.SwingUtils;
import determination.xenon.util.TaskCancellationAction;
import determination.xenon.util.io.FileUtils;
import determination.xenon.util.io.JarUtils;
import determination.xenon.util.platform.OperatingSystem;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static determination.xenon.ui.FXUtils.checkFxUserThread;
import static determination.xenon.util.Lang.thread;
import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

public final class UpdateHandler {
    /// Magic footer written by `XenonBootstrap` after the embedded payload.
    private static final byte[] PAYLOAD_MAGIC = "XENONPAYLOADV1!!".getBytes(StandardCharsets.US_ASCII);

    /// Size of the bootstrap footer: magic (16) + payload length (8) + SHA-256 (32).
    private static final int PAYLOAD_FOOTER_SIZE = 16 + 8 + 32;

    /// Name of the bundle directory extracted next to the single-file executable.
    private static final String BOOTSTRAP_BUNDLE_NAME = "Xenon.bundle";

    private UpdateHandler() {
    }

    /**
     * @return whether to exit
     */
    public static boolean processArguments(String[] args) {
        breakForceUpdateFeature();

        if (isNestedApplication()) {
            // updated from old versions
            try {
                performMigration();
            } catch (IOException e) {
                LOG.warning("Failed to perform migration", e);
                SwingUtils.showErrorDialog(i18n("fatal.apply_update_failure", Metadata.MANUAL_UPDATE_URL) + "\n" + StringUtils.getStackTrace(e));
            }
            return true;
        }

        if (args.length == 2 && args[0].equals("--apply-to")) {
            if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS && !OperatingSystem.isWindows7OrLater()) {
                SwingUtils.showErrorDialog(i18n("fatal.apply_update_need_win7", Metadata.PUBLISH_URL));
                return true;
            }

            try {
                applyUpdate(Paths.get(args[1]));
            } catch (IOException e) {
                LOG.warning("Failed to apply update", e);
                SwingUtils.showErrorDialog(i18n("fatal.apply_update_failure", Metadata.MANUAL_UPDATE_URL) + "\n" + StringUtils.getStackTrace(e));
            }
            return true;
        }

        if (isFirstLaunchAfterUpgrade()) {
            SwingUtils.showInfoDialog(i18n("fatal.migration_requires_manual_reboot"));
            return true;
        }

        return false;
    }

    public static void updateFrom(RemoteVersion version) {
        checkFxUserThread();

        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS && !OperatingSystem.isWindows7OrLater()) {
            Controllers.dialog(i18n("fatal.apply_update_need_win7", Metadata.PUBLISH_URL), i18n("message.error"), MessageType.ERROR);
            return;
        }

        // Show the changelog first. The download only starts from the
        // dialog's Update button, which calls startUpdate(); cancelling or
        // closing the dialog must not touch the network.
        Controllers.dialog(new UpgradeDialog(version, () -> startUpdate(version)));
    }

    /// Starts the download and handover of one update.
    ///
    /// The artifact is chosen from what this build can actually start: a
    /// portable jar needs a `java` executable, while the Windows single-file
    /// build has no launcher binary in its runtime image and must update
    /// through the `.exe` asset.
    private static void startUpdate(RemoteVersion version) {
        RemoteVersion.Type type = selectArtifact(version);
        if (type == null) {
            Controllers.dialog(i18n("fatal.apply_update_failure", Metadata.MANUAL_UPDATE_URL),
                    i18n("update.failed"), MessageType.ERROR);
            return;
        }
        LOG.info("Updating to " + version + " with the " + type + " artifact");

        Path downloaded;
        try {
            downloaded = Files.createTempFile("xenon-update-", type == RemoteVersion.Type.EXE ? ".exe" : ".jar");
        } catch (IOException e) {
            LOG.warning("Failed to create temp file", e);
            Controllers.dialog(StringUtils.getStackTrace(e), i18n("update.failed"), MessageType.ERROR);
            return;
        }

        Task<?> task = new XenonDownloadTask(version, type, downloaded);

        TaskExecutor executor = task.executor();
        Controllers.taskDialog(executor, i18n("message.downloading"), TaskCancellationAction.NORMAL);
        thread(() -> {
            try {
                boolean success = executor.test();

                if (success) {
                    // Self-integrity check skipped for non-official builds.
                    // See UpdateChecker.checkUpdate() for rationale.

                    saveWindowStatesAndWait();
                    requestUpdate(version, type, downloaded);
                    EntryPoint.exit(0);
                } else {
                    Exception e = executor.getException();
                    LOG.warning("Failed to update to " + version, e);
                    if (e instanceof CancellationException) {
                        Platform.runLater(() -> Controllers.showToast(i18n("message.cancelled")));
                    } else {
                        String message = e != null ? e.toString() : i18n("update.failed");
                        Platform.runLater(() -> Controllers.dialog(message, i18n("update.failed"), MessageType.ERROR));
                    }
                }
            } catch (Throwable e) {
                // Never let the update thread die silently: a failed handover
                // used to leave the update dialog looking like a no-op.
                LOG.warning("Failed to update to " + version, e);
                Platform.runLater(() -> Controllers.dialog(StringUtils.getStackTrace(e), i18n("update.failed"), MessageType.ERROR));
            }
        });
    }

    /// Chooses the artifact this build is able to start.
    ///
    /// @param version release being installed
    /// @return artifact kind, or `null` when the release cannot be applied automatically
    private static @Nullable RemoteVersion.Type selectArtifact(RemoteVersion version) {
        return selectArtifact(version,
                JavaRuntime.getDefault() != null,
                OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS);
    }

    /// Chooses the artifact for the given launcher capabilities.
    ///
    /// A jar is preferred whenever a `java` executable exists. The packaged
    /// Windows build has no such executable and must use the self-contained
    /// `.exe` instead of silently failing in the handover.
    ///
    /// @param version     release being installed
    /// @param canStartJar whether this process can launch a jar
    /// @param windows     whether the current platform is Windows
    /// @return artifact kind, or `null` when no artifact can be applied
    static @Nullable RemoteVersion.Type selectArtifact(RemoteVersion version, boolean canStartJar, boolean windows) {
        if (version.jarUrl() != null && canStartJar) {
            return RemoteVersion.Type.JAR;
        }
        if (version.executableUrl() != null && windows) {
            return RemoteVersion.Type.EXE;
        }
        return null;
    }

    /// Saves window state and pending config writes before the process exits.
    private static void saveWindowStatesAndWait() {
        CompletableFuture<Void> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                Controllers.saveWindowStates();
            } finally {
                future.complete(null);
            }
        });

        try {
            future.get();
        } catch (ExecutionException | InterruptedException ignored) {
            // Ignore
        }

        try {
            FileSaver.waitForAllSaves();
        } catch (InterruptedException ignored) {
            // Ignore
        }
    }

    private static void applyUpdate(Path target) throws IOException {
        LOG.info("Applying update to " + target);

        Path self = getCurrentLocation();
        // Self-integrity check skipped for non-official builds.
        ExecutableHeaderHelper.copyWithHeader(self, target);

        Optional<Path> newFilename = tryRename(target, Metadata.VERSION);
        if (newFilename.isPresent()) {
            LOG.info("Move " + target + " to " + newFilename.get());
            try {
                Files.move(target, newFilename.get());
                target = newFilename.get();
            } catch (IOException e) {
                LOG.warning("Failed to move target", e);
            }
        }

        startJava(target);
    }

    /// Hands the downloaded artifact over to a fresh launcher process.
    private static void requestUpdate(RemoteVersion version, RemoteVersion.Type type, Path downloaded) throws IOException {
        switch (type) {
            case JAR -> startJava(downloaded);
            case EXE -> startExecutableUpdate(version, downloaded);
        }
    }

    /// Installs and starts the Windows single-file launcher.
    ///
    /// The bootstrap executable is not running while the launcher works, so it
    /// can be replaced in place. The new bootstrap retries the `Xenon.bundle`
    /// swap until this process exits, which keeps the running launcher from
    /// blocking the extraction. When the outer executable cannot be located or
    /// written, the downloaded file is started from a fresh directory instead.
    private static void startExecutableUpdate(RemoteVersion version, Path downloaded) throws IOException {
        Path bootstrap = findBootstrapExecutable();
        Path target;
        if (bootstrap != null) {
            try {
                target = replaceExecutable(downloaded, bootstrap);
            } catch (IOException e) {
                LOG.warning("Failed to replace the launcher at " + bootstrap, e);
                target = installExecutableFallback(downloaded, version, launcherDirectory());
            }
        } else {
            target = installExecutableFallback(downloaded, version, launcherDirectory());
        }

        startExecutable(target);
    }

    /// Replaces the outer bootstrap executable with the downloaded one.
    ///
    /// @param downloaded downloaded executable
    /// @param target     bootstrap executable to replace
    /// @return the replaced executable
    /// @throws IOException when the executable cannot be replaced
    static Path replaceExecutable(Path downloaded, Path target) throws IOException {
        Path staging = target.resolveSibling(target.getFileName() + ".new");
        Files.copy(downloaded, staging, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warning("Atomic replacement of the launcher failed; retrying without it", e);
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /// Places the downloaded executable in a visible directory when the outer
    /// bootstrap cannot be replaced.
    ///
    /// @param downloaded downloaded executable
    /// @param version    release being installed
    /// @param directory  preferred directory for the new executable
    /// @return the executable to start
    /// @throws IOException when neither the preferred directory nor a temp directory accepts the file
    static Path installExecutableFallback(Path downloaded, RemoteVersion version, Path directory) throws IOException {
        Path target = directory.resolve("Xenon-" + version.version() + ".exe");
        try {
            Files.move(downloaded, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            LOG.warning("Failed to place the downloaded launcher at " + target, e);
            Path fallback = Files.createTempDirectory("xenon-update-").resolve("Xenon-" + version.version() + ".exe");
            Files.move(downloaded, fallback, StandardCopyOption.REPLACE_EXISTING);
            return fallback;
        }
    }

    /// Starts a native launcher executable and lets it detach from this JVM.
    private static void startExecutable(Path executable) throws IOException {
        Path absolute = executable.toAbsolutePath();
        LOG.info("Starting launcher: " + absolute);
        new ProcessBuilder(absolute.toString())
                .directory(absolute.getParent().toFile())
                .inheritIO()
                .start();
    }

    /// Locates the single-file bootstrap executable that produced the running
    /// `Xenon.bundle`.
    ///
    /// The bootstrap starts the bundled launcher and exits immediately, so its
    /// path is not part of the process tree. The bundle records the payload
    /// hash, and each bootstrap executable stores the same hash in its footer;
    /// matching them identifies the exact executable even when several Xenon
    /// builds share a directory.
    ///
    /// @return the bootstrap executable, or `null` for other layouts
    private static @Nullable Path findBootstrapExecutable() {
        String explicit = System.getProperty("xenon.bootstrap.exe");
        if (StringUtils.isNotBlank(explicit)) {
            Path path = Path.of(explicit);
            if (Files.isRegularFile(path)) {
                return path.toAbsolutePath().normalize();
            }
        }

        Path program = Metadata.PROGRAM_DIRECTORY;
        Path bundle = bootstrapBundleOf(program);
        if (bundle == null) {
            return null;
        }
        return findBootstrapExecutableIn(bundle);
    }

    /// Returns the `Xenon.bundle` directory of a single-file layout.
    ///
    /// The packaged launcher lives at
    /// `<outer>/Xenon.bundle/Xenon/app/Xenon-<version>.jar`, so the bundle is
    /// two levels above the jar directory.
    ///
    /// @param programDirectory directory that contains the launcher jar
    /// @return bundle directory, or `null` for other layouts
    static @Nullable Path bootstrapBundleOf(Path programDirectory) {
        Path appRoot = programDirectory.getParent();
        if (appRoot == null) {
            return null;
        }
        Path bundle = appRoot.getParent();
        return bundle != null && BOOTSTRAP_BUNDLE_NAME.equals(FileUtils.getName(bundle)) ? bundle : null;
    }

    /// Locates the bootstrap executable that produced a bundle directory.
    ///
    /// @param bundle `Xenon.bundle` directory extracted next to the executable
    /// @return matching executable, or `null` when none matches the payload hash
    static @Nullable Path findBootstrapExecutableIn(Path bundle) {
        if (!BOOTSTRAP_BUNDLE_NAME.equals(FileUtils.getName(bundle))) {
            return null;
        }

        Path directory = bundle.getParent();
        if (directory == null) {
            return null;
        }

        String expectedHash;
        try {
            expectedHash = Files.readString(bundle.resolve(".payload.sha256")).trim();
        } catch (IOException e) {
            LOG.warning("Failed to read the bootstrap payload hash", e);
            return null;
        }
        if (expectedHash.isEmpty()) {
            return null;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.exe")) {
            Path match = null;
            String expectedName = "Xenon-" + Metadata.VERSION + ".exe";
            for (Path candidate : stream) {
                String hash = readPayloadHash(candidate);
                if (expectedHash.equalsIgnoreCase(hash)) {
                    if (expectedName.equalsIgnoreCase(FileUtils.getName(candidate))) {
                        return candidate.toAbsolutePath().normalize();
                    }
                    if (match == null) {
                        match = candidate;
                    }
                }
            }
            if (match != null) {
                return match.toAbsolutePath().normalize();
            }
        } catch (IOException e) {
            LOG.warning("Failed to scan for the bootstrap executable", e);
        }
        return null;
    }

    /// Reads the payload SHA-256 from a bootstrap executable footer.
    ///
    /// @param executable candidate executable
    /// @return lowercase hex hash, or `null` when the file is not a Xenon bootstrap
    static @Nullable String readPayloadHash(Path executable) {
        try (SeekableByteChannel channel = Files.newByteChannel(executable, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < PAYLOAD_FOOTER_SIZE) {
                return null;
            }

            ByteBuffer buffer = ByteBuffer.allocate(PAYLOAD_FOOTER_SIZE);
            channel.position(size - PAYLOAD_FOOTER_SIZE);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    return null;
                }
            }
            buffer.flip();

            byte[] magic = new byte[PAYLOAD_MAGIC.length];
            buffer.get(magic);
            if (!Arrays.equals(magic, PAYLOAD_MAGIC)) {
                return null;
            }

            buffer.getLong(); // Payload length
            byte[] hash = new byte[32];
            buffer.get(hash);
            return HexFormat.of().formatHex(hash);
        } catch (IOException e) {
            return null;
        }
    }

    /// Directory that contains the user-visible launcher executable.
    private static Path launcherDirectory() {
        Path program = Metadata.PROGRAM_DIRECTORY;
        Path bundle = bootstrapBundleOf(program);
        if (bundle != null) {
            Path outer = bundle.getParent();
            if (outer != null) {
                return outer;
            }
        }
        if ("app".equals(FileUtils.getName(program))) {
            Path install = program.getParent();
            if (install != null) {
                return install;
            }
        }
        return program;
    }

    /// Starts a new launcher process for the given jar.
    ///
    /// The child receives the current JVM's `-D`/`-X` options plus an
    /// internal marker that disables single-instance detection: this method
    /// is used for update and restart handovers where the parent exits right
    /// after spawning the child, so the child must never wait for a dialog.
    ///
    /// @param jar launcher jar to start
    /// @param appArgs application arguments passed to the child
    /// @throws IOException when the process cannot be started
    public static void startJava(Path jar, String... appArgs) throws IOException {
        JavaRuntime java = JavaRuntime.getDefault();
        if (java == null) {
            // jpackage runtime images (the Windows single-file build) do not
            // ship a java launcher, so a jar cannot be started from here.
            throw new IOException("No Java executable is available to start " + jar);
        }

        List<String> commandline = new ArrayList<>();
        commandline.add(java.getBinary().toString());

        try {
            for (String inputArgument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (inputArgument.startsWith("-D") || inputArgument.startsWith("-X")) {
                    commandline.add(inputArgument);
                }
            }
        } catch (Throwable ignored) {
            // ManagementFactory not available
            for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
                if (entry.getKey() instanceof String key && key.startsWith("xenon.")) {
                    commandline.add("-D" + key + "=" + entry.getValue());
                }
            }
        }

        // Internal handover marker: the parent exits immediately after this
        // call, so the child must not run single-instance negotiation.
        commandline.add("-Dxenon.single_instance=skip");
        commandline.add("-jar");
        commandline.add(jar.toAbsolutePath().toString());
        commandline.addAll(Arrays.asList(appArgs));
        LOG.info("Starting process: " + commandline);
        new ProcessBuilder(commandline)
                .directory(Paths.get("").toAbsolutePath().toFile())
                .inheritIO()
                .start();
    }

    private static Optional<Path> tryRename(Path path, String newVersion) {
        String filename = path.getFileName().toString();
        Matcher matcher = Pattern.compile("^(?<prefix>(?:[hH][mM][cC][lL]|[xX][eE][nN][oO][nN])[.-])(?<version>\\d+(?:\\.\\d+)*)(?<suffix>\\.[^.]+)$").matcher(filename);
        if (matcher.find()) {
            String newFilename = matcher.group("prefix") + newVersion + matcher.group("suffix");
            if (!newFilename.equals(filename)) {
                return Optional.of(path.resolveSibling(newFilename));
            }
        }
        return Optional.empty();
    }

    private static Path getCurrentLocation() throws IOException {
        Path path = JarUtils.thisJarPath();
        if (path == null) {
            throw new IOException("Failed to find current Xenon location");
        }
        return path;
    }

    // ==== support for old versions ===
    private static void performMigration() throws IOException {
        LOG.info("Migrating from old versions");

        startJava(getCurrentLocation());
    }

    /**
     * This method must be called from the main thread.
     */
    private static boolean isNestedApplication() {
        StackTraceElement[] stacktrace = Thread.currentThread().getStackTrace();
        for (int i = 0; i < stacktrace.length; i++) {
            StackTraceElement element = stacktrace[i];
            if (Main.class.getName().equals(element.getClassName()) && "main".equals(element.getMethodName())) {
                // we've reached the main method
                return i + 1 != stacktrace.length;
            }
        }
        return false;
    }

    private static boolean isFirstLaunchAfterUpgrade() {
        Path currentPath = JarUtils.thisJarPath();
        if (currentPath != null) {
            Path updated = Metadata.XENON_GLOBAL_DIRECTORY.resolve("Xenon-" + Metadata.VERSION + ".jar");
            if (currentPath.equals(updated.toAbsolutePath())) {
                return true;
            }
        }
        return false;
    }

    private static void breakForceUpdateFeature() {
        Path hmclVersionJson = Metadata.XENON_GLOBAL_DIRECTORY.resolve("hmclver.json");
        if (Files.isRegularFile(hmclVersionJson)) {
            try {
                Map<?, ?> content = new Gson().fromJson(Files.readString(hmclVersionJson), Map.class);
                Object ver = content.get("ver");
                if (ver instanceof String && ((String) ver).startsWith("3.")) {
                    Files.delete(hmclVersionJson);
                    LOG.info("Successfully broke the force update feature");
                }
            } catch (IOException e) {
                LOG.warning("Failed to break the force update feature", e);
            } catch (JsonParseException e) {
                hmclVersionJson.toFile().delete();
            }
        }
    }
}
