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
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.MindustryInstanceName;
import determination.xenon.mindustry.MindustryLaunchService;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.VersionVariant;
import determination.xenon.mindustry.XenonGameRepository;
import determination.xenon.mindustry.download.GitHubReleaseClient;
import determination.xenon.mindustry.download.MindustryDownloadSource;
import determination.xenon.mindustry.download.MindustryRemoteVersion;
import determination.xenon.mindustry.download.MindustryVersionList;
import determination.xenon.mindustry.install.VersionListFactory;
import determination.xenon.mindustry.modpack.XenonModpackPacker;
import determination.xenon.mindustry.save.MindustryBackupNaming;
import determination.xenon.mindustry.save.SaveBackupService;
import determination.xenon.mindustry.save.SaveFileReader;
import determination.xenon.setting.ConfigHolder;
import determination.xenon.util.instance.LauncherInstance;
import determination.xenon.util.instance.LauncherInstanceRegistry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// Headless command-line entry point.
///
/// Runs before JavaFX starts. Lists, launches, exports, imports and deletes
/// Mindustry instances, inspects and restores save backups, compares installed
/// builds with the upstream feeds and reads/writes a small whitelist of
/// settings. Any unknown flag exits with code 2 instead of starting the UI, so
/// scripts can rely on the contract.
///
/// Commands that change data refuse to run while another Xenon launcher owns
/// the same data directory, because the running launcher would overwrite the
/// change the next time it saves its own state.
@NotNullByDefault
public final class XenonCli {

    /// Exit code for a successful command.
    public static final int OK = 0;
    /// Exit code for a failed command.
    public static final int FAILURE = 1;
    /// Exit code for invalid arguments or an unknown setting key.
    public static final int USAGE = 2;
    /// Exit code when an instance or backup does not exist.
    public static final int NOT_FOUND = 3;
    /// Exit code when writing a modpack fails.
    public static final int EXPORT_FAILED = 5;
    /// Exit code when another launcher owns the data directory.
    public static final int LAUNCHER_RUNNING = 6;
    /// Exit code when deleting an instance or backup fails.
    public static final int DELETE_FAILED = 7;
    /// Exit code when importing a jar fails.
    public static final int IMPORT_FAILED = 8;
    /// Exit code when restoring a backup fails.
    public static final int RESTORE_FAILED = 9;
    /// Exit code when at least one checked variant has a newer upstream build.
    public static final int UPDATE_AVAILABLE = 10;
    /// Exit code when no variant feed could be checked.
    public static final int UPDATE_CHECK_FAILED = 11;

    /// Runs the CLI when the first argument is a known flag.
    ///
    /// A leading `--yes` is allowed before the command name; it is removed
    /// from the argument list before dispatch so destructive commands accept
    /// it anywhere on their command line.
    ///
    /// @param args process arguments
    /// @return the exit code, or `null` when the normal UI should start
    public static @Nullable Integer runIfCli(String[] args) {
        if (args == null || args.length == 0 || args[0] == null
                || (!args[0].startsWith("--") && !"-h".equals(args[0]))) {
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
    ///
    /// @param rawArgs process arguments, possibly containing `--yes` anywhere
    /// @return the process exit code
    private static int run(String[] rawArgs) throws Exception {
        boolean yes = false;
        List<String> cleaned = new ArrayList<>(rawArgs.length);
        for (String arg : rawArgs) {
            if ("--yes".equals(arg)) {
                yes = true;
            } else {
                cleaned.add(arg);
            }
        }
        if (cleaned.isEmpty()) {
            printUsage();
            return USAGE;
        }
        String[] args = cleaned.toArray(String[]::new);
        return switch (args[0]) {
            case "--help", "-h" -> {
                printUsage();
                yield OK;
            }
            case "--version" -> version();
            case "--list" -> list();
            case "--list-backups" -> listBackups(args);
            case "--launch" -> launch(args);
            case "--export" -> export(args);
            case "--get" -> get(args);
            case "--set" -> set(args);
            case "--import" -> importJar(args);
            case "--delete-version" -> deleteVersion(args, yes);
            case "--delete-backup" -> deleteBackup(args, yes);
            case "--restore-backup" -> restoreBackup(args, yes);
            case "--check-updates" -> checkUpdates(args);
            default -> {
                System.err.println("xenon: unknown option " + args[0]);
                printUsage();
                yield USAGE;
            }
        };
    }

    /// Prints available commands and the exit-code contract.
    private static void printUsage() {
        System.out.println("""
                Xenon Launcher CLI

                  xenon --help
                  xenon --version
                  xenon --list
                  xenon --list-backups <instance>
                  xenon --launch <instance>
                  xenon --export <instance> <output.zip>
                  xenon --get <key>
                  xenon --set <key> <value>
                  xenon --import <jar> [--as <id>] [--name <display name>] [--variant <variant>]
                  xenon --delete-version <instance> --yes
                  xenon --delete-backup <instance> [backup] --yes
                  xenon --restore-backup <instance> [backup] --yes
                  xenon --check-updates [--variant <variant>]

                Instances accept their id or display name. Matching ignores case,
                repeated or full-width spaces and an optional leading "v", so
                "V146", "v 146" and "Mindustry 146" all find the same instance.

                Backups are addressed by file name or by their timestamp
                (yyyyMMdd-HHmmss). Omitting one picks the newest backup.

                --yes may appear anywhere on the command line. Commands that
                change data refuse to run while a Xenon launcher is running.

                Setting keys: download.source, download.speed, github.token
                Download sources: prefer-domestic, prefer-official, domestic-only, official-only
                Variants: vanilla, be, mindustryx, cn-arc, foo, custom

                Exit codes:
                  0  success
                  1  command failed
                  2  usage error or unknown setting key
                  3  instance or backup not found
                  5  export failed
                  6  a Xenon launcher is running; close it first
                  7  delete failed
                  8  import failed (an existing id is never overwritten)
                  9  restore failed (the current save was not replaced)
                 10  check-updates found a newer build
                 11  check-updates could not reach any variant feed
                  --launch instead returns the game process's own exit code.
                """);
    }

    /// Prints the launcher version.
    ///
    /// @return [`#OK`]
    private static int version() {
        System.out.println(Metadata.VERSION);
        return OK;
    }

    /// Lists installed instances.
    ///
    /// The first three columns (`id`, `name`, `build`) keep their historical
    /// positions; a trailing `name` column makes the display name addressable
    /// by field count for scripts.
    ///
    /// @return [`#OK`]
    private static int list() throws IOException {
        XenonGameRepository repo = repository();
        List<MindustryVersion> versions = repo.all().stream()
                .sorted(Comparator.comparing(MindustryVersion::getName))
                .toList();
        for (MindustryVersion version : versions) {
            String name = version.getName();
            System.out.printf("%s\t%s\tbuild %d\t%s%n",
                    version.getId(), name, version.getBuild(), name);
        }
        return OK;
    }

    /// Lists the save backups of one instance, newest first.
    ///
    /// @param args command arguments; `args[1]` is the instance reference
    /// @return [`#OK`], [`#NOT_FOUND`] or [`#USAGE`]
    private static int listBackups(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("xenon: --list-backups needs an instance");
            return USAGE;
        }
        XenonGameRepository repo = repository();
        MindustryVersion version = find(repo, args[1]);
        if (version == null) {
            return notFound(args[1]);
        }
        Path backupsDir = backupsDirectory(repo, version);
        List<Path> backups = listBackupFiles(backupsDir);
        if (backups.isEmpty()) {
            System.out.println("xenon: no backups in " + backupsDir);
            return OK;
        }
        for (Path backup : backups) {
            System.out.println(MindustryBackupNaming.describe(backup));
        }
        return OK;
    }

    /// Launches one instance and waits for it.
    ///
    /// @param args command arguments; `args[1]` is the instance reference
    /// @return the game process exit code, [`#NOT_FOUND`] or [`#USAGE`]
    private static int launch(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("xenon: --launch needs an instance");
            return USAGE;
        }
        XenonGameRepository repo = repository();
        MindustryVersion version = find(repo, args[1]);
        if (version == null) {
            return notFound(args[1]);
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
    ///
    /// @param args command arguments; `args[1]` is the instance and
    ///     `args[2]` the output file
    /// @return [`#OK`], [`#NOT_FOUND`], [`#USAGE`] or [`#EXPORT_FAILED`]
    private static int export(String[] args) {
        if (args.length < 3) {
            System.err.println("xenon: --export needs an instance and an output file");
            return USAGE;
        }
        try {
            XenonGameRepository repo = repository();
            MindustryVersion version = find(repo, args[1]);
            if (version == null) {
                return notFound(args[1]);
            }
            Path target = Path.of(args[2]).toAbsolutePath().normalize();
            XenonModpackPacker.pack(repo, version, target);
            System.out.println(target);
            return OK;
        } catch (Exception e) {
            System.err.println("xenon: export failed: " + messageOf(e));
            return EXPORT_FAILED;
        }
    }

    /// Reads one whitelisted setting.
    ///
    /// @param args command arguments; `args[1]` is the key
    /// @return [`#OK`], [`#USAGE`] or [`#FAILURE`]
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
    ///
    /// @param args command arguments; `args[1]` is the key and `args[2]` its value
    /// @return [`#OK`], [`#USAGE`] or [`#LAUNCHER_RUNNING`]
    private static int set(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("xenon: --set needs a key and a value");
            return USAGE;
        }
        int running = rejectWhileLauncherRuns("changing settings");
        if (running != OK) {
            return running;
        }
        initConfig();
        return switch (args[1]) {
            case "download.source" -> {
                try {
                    ConfigHolder.globalConfig().setMindustryDownloadSource(
                            MindustryDownloadSource.valueOf(args[2].toUpperCase(Locale.ROOT)));
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

    /// Imports a local Mindustry jar as a new instance.
    ///
    /// @param args command arguments
    /// @return [`#OK`], [`#USAGE`], [`#LAUNCHER_RUNNING`] or [`#IMPORT_FAILED`]
    private static int importJar(String[] args) throws IOException {
        ImportRequest request = parseImport(args);
        if (request == null) {
            System.err.println("xenon: --import needs a jar and accepts --as, --name and --variant");
            return USAGE;
        }
        Path jar = Path.of(request.jar()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            System.err.println("xenon: jar not found: " + jar);
            return USAGE;
        }
        VersionVariant variant = parseVariant(request.variant());
        if (request.variant() != null && variant == null) {
            System.err.println("xenon: unknown variant " + request.variant());
            return USAGE;
        }
        int running = rejectWhileLauncherRuns("importing an instance");
        if (running != OK) {
            return running;
        }
        XenonGameRepository repo = repository();
        String requestedId = request.id() == null ? null : request.id().trim();
        if (requestedId != null && !XenonGameRepository.isValidId(requestedId)) {
            System.err.println("xenon: invalid instance id " + requestedId
                    + " (letters, digits, dot, dash and underscore only)");
            return USAGE;
        }
        String id = requestedId == null
                ? MindustryImportFlow.uniqueId(repo, jarFileName(jar))
                : requestedId;
        if (repo.has(id)) {
            System.err.println("xenon: instance already exists: " + id + " (not overwritten)");
            return IMPORT_FAILED;
        }
        MindustryVersion template = new MindustryVersion();
        if (request.name() != null && !request.name().isBlank()) {
            template.setName(request.name().trim());
        }
        if (variant != null) {
            template.setVariant(variant);
        }
        try {
            MindustryVersion version = MindustryLaunchService.importLocalJar(repo, jar, id, template);
            boolean changed = false;
            String requestedName = request.name() == null ? null : request.name().trim();
            if (requestedName != null && !requestedName.isBlank()
                    && !requestedName.equals(version.getName())) {
                version.setName(requestedName);
                changed = true;
            }
            // `importLocalJar` sniffs a variant when the template says CUSTOM;
            // an explicit --variant must win over that sniff.
            if (variant != null && version.getVariant() != variant) {
                version.setVariant(variant);
                changed = true;
            }
            if (changed) {
                repo.save(version);
            }
            String finalId = version.getId();
            if (finalId == null || finalId.isBlank()) {
                finalId = id;
            }
            System.out.println(finalId);
            return OK;
        } catch (Exception e) {
            System.err.println("xenon: import failed: " + messageOf(e));
            return IMPORT_FAILED;
        }
    }

    /// Deletes one instance, moving its directory to the platform trash.
    ///
    /// @param args command arguments; `args[1]` is the instance reference
    /// @param yes  whether `--yes` was present
    /// @return [`#OK`], [`#USAGE`], [`#NOT_FOUND`], [`#LAUNCHER_RUNNING`] or [`#DELETE_FAILED`]
    private static int deleteVersion(String[] args, boolean yes) throws IOException {
        if (args.length < 2) {
            System.err.println("xenon: --delete-version needs an instance");
            return USAGE;
        }
        if (!yes) {
            return missingYes("--delete-version");
        }
        int running = rejectWhileLauncherRuns("deleting an instance");
        if (running != OK) {
            return running;
        }
        XenonGameRepository repo = repository();
        MindustryVersion version = find(repo, args[1]);
        if (version == null) {
            return notFound(args[1]);
        }
        String id = version.getId();
        if (id == null || id.isBlank()) {
            return notFound(args[1]);
        }
        try {
            if (!repo.delete(id)) {
                System.err.println("xenon: instance not found: " + args[1]);
                return NOT_FOUND;
            }
            System.out.println("deleted " + id);
            return OK;
        } catch (Exception e) {
            System.err.println("xenon: delete failed: " + messageOf(e));
            return DELETE_FAILED;
        }
    }

    /// Deletes one save backup, moving it to the platform trash.
    ///
    /// @param args command arguments; `args[1]` is the instance and the
    ///     optional `args[2]` selects a backup
    /// @param yes  whether `--yes` was present
    /// @return [`#OK`], [`#USAGE`], [`#NOT_FOUND`], [`#LAUNCHER_RUNNING`] or [`#DELETE_FAILED`]
    private static int deleteBackup(String[] args, boolean yes) throws IOException {
        if (args.length < 2) {
            System.err.println("xenon: --delete-backup needs an instance");
            return USAGE;
        }
        if (!yes) {
            return missingYes("--delete-backup");
        }
        int running = rejectWhileLauncherRuns("deleting a backup");
        if (running != OK) {
            return running;
        }
        XenonGameRepository repo = repository();
        MindustryVersion version = find(repo, args[1]);
        if (version == null) {
            return notFound(args[1]);
        }
        Path backupsDir = backupsDirectory(repo, version);
        Path backup = resolveBackup(backupsDir, args.length > 2 ? args[2] : null);
        if (backup == null) {
            System.err.println("xenon: backup not found or selector is ambiguous in " + backupsDir);
            return NOT_FOUND;
        }
        try {
            new SaveBackupService(backupsDir.getParent()).delete(backup);
            System.out.println("deleted " + backup.getFileName());
            return OK;
        } catch (Exception e) {
            System.err.println("xenon: delete failed: " + messageOf(e));
            return DELETE_FAILED;
        }
    }

    /// Restores one save backup over its live slot.
    ///
    /// The current live save is copied into `saves/backups/` before the
    /// overwrite, so a failed or unwanted restore never destroys the previous
    /// contents without a copy.
    ///
    /// @param args command arguments; `args[1]` is the instance and the
    ///     optional `args[2]` selects a backup
    /// @param yes  whether `--yes` was present
    /// @return [`#OK`], [`#USAGE`], [`#NOT_FOUND`], [`#LAUNCHER_RUNNING`] or [`#RESTORE_FAILED`]
    private static int restoreBackup(String[] args, boolean yes) throws IOException {
        if (args.length < 2) {
            System.err.println("xenon: --restore-backup needs an instance");
            return USAGE;
        }
        if (!yes) {
            return missingYes("--restore-backup");
        }
        int running = rejectWhileLauncherRuns("restoring a backup");
        if (running != OK) {
            return running;
        }
        XenonGameRepository repo = repository();
        MindustryVersion version = find(repo, args[1]);
        if (version == null) {
            return notFound(args[1]);
        }
        Path backupsDir = backupsDirectory(repo, version);
        Path backup = resolveBackup(backupsDir, args.length > 2 ? args[2] : null);
        if (backup == null) {
            System.err.println("xenon: backup not found or selector is ambiguous in " + backupsDir);
            return NOT_FOUND;
        }
        String slot = MindustryBackupNaming.slotName(backup);
        if (slot == null) {
            System.err.println("xenon: cannot derive a save slot from " + backup.getFileName());
            return RESTORE_FAILED;
        }
        Path savesDir = backupsDir.getParent();
        Path target = savesDir.resolve(slot + SaveBackupService.MSAV_EXT);
        SaveBackupService service = new SaveBackupService(savesDir);
        Path protection = null;
        try {
            if (Files.isRegularFile(target)) {
                protection = service.backup(SaveFileReader.readHeaderLenient(target));
            }
            service.restore(backup, target);
            System.out.println("restored " + backup.getFileName() + " -> " + target);
            if (protection != null) {
                System.out.println("previous save backed up to " + protection);
            }
            return OK;
        } catch (Exception e) {
            System.err.println("xenon: restore failed: " + messageOf(e));
            if (protection != null) {
                System.err.println("xenon: the previous save is still available at " + protection);
            }
            return RESTORE_FAILED;
        }
    }

    /// Compares installed builds with the upstream feeds.
    ///
    /// Without `--variant`, every variant that has at least one installed
    /// instance is checked once. A tier that cannot list a variant is reported
    /// as a readable line instead of an exception.
    ///
    /// @param args command arguments
    /// @return [`#OK`], [`#UPDATE_AVAILABLE`], [`#UPDATE_CHECK_FAILED`] or [`#USAGE`]
    private static int checkUpdates(String[] args) throws IOException {
        String requestedVariant = optionValue(args, "--variant");
        VersionVariant explicit = parseVariant(requestedVariant);
        if (requestedVariant != null && explicit == null) {
            System.err.println("xenon: unknown variant " + requestedVariant);
            return USAGE;
        }
        if (explicit == VersionVariant.CUSTOM) {
            System.out.println("CUSTOM\thas no upstream feed to check");
            return UPDATE_CHECK_FAILED;
        }
        initConfig();
        MindustryDownloadSource source = MindustryDownloadSource.current();
        XenonGameRepository repo = repository();
        List<VersionVariant> variants;
        if (explicit != null) {
            variants = List.of(explicit);
        } else {
            Set<VersionVariant> installed = new LinkedHashSet<>();
            for (MindustryVersion version : repo.all()) {
                VersionVariant variant = version.getVariant();
                if (variant != VersionVariant.CUSTOM) {
                    installed.add(variant);
                }
            }
            variants = List.copyOf(installed);
        }
        if (variants.isEmpty()) {
            System.out.println("xenon: no installed Mindustry variant to check");
            return OK;
        }

        GitHubReleaseClient client = new GitHubReleaseClient(Metadata.getCachesDirectory());
        int checked = 0;
        int failed = 0;
        boolean update = false;
        for (VersionVariant variant : variants) {
            if (!source.allowsVariant(variant)) {
                System.out.println(variant.name() + "\tunavailable: " + source
                        + " cannot list this variant");
                failed++;
                continue;
            }
            try {
                MindustryVersionList list = VersionListFactory.listFor(variant, client, source);
                List<MindustryRemoteVersion> remote = list.refresh();
                int latest = remote.stream()
                        .mapToInt(MindustryRemoteVersion::getBuild)
                        .max().orElse(0);
                int local = repo.all().stream()
                        .filter(version -> version.getVariant() == variant)
                        .mapToInt(MindustryVersion::getBuild)
                        .max().orElse(0);
                System.out.println(variant.name()
                        + "\tlatest build " + latest
                        + "\tlocal build " + (local > 0 ? Integer.toString(local) : "none"));
                checked++;
                if (latest > local) {
                    update = true;
                }
            } catch (Exception e) {
                System.out.println(variant.name() + "\tcheck failed: " + messageOf(e));
                failed++;
            }
        }
        if (checked == 0) {
            return UPDATE_CHECK_FAILED;
        }
        if (failed > 0) {
            System.out.println("xenon: " + failed + " variant check(s) failed");
        }
        return update ? UPDATE_AVAILABLE : OK;
    }

    /// Reports an unknown setting key.
    ///
    /// @param key the unknown key
    /// @return [`#USAGE`]
    private static int unknownKey(String key) {
        System.err.println("xenon: unknown setting key " + key);
        return USAGE;
    }

    /// Reports a missing instance reference.
    ///
    /// @param query the reference the user supplied
    /// @return [`#NOT_FOUND`]
    private static int notFound(String query) {
        System.err.println("xenon: instance not found: " + query);
        return NOT_FOUND;
    }

    /// Reports a destructive command that lacks `--yes`.
    ///
    /// @param command command name for the message
    /// @return [`#USAGE`]
    private static int missingYes(String command) {
        System.err.println("xenon: " + command + " needs --yes");
        return USAGE;
    }

    /// Refuses a mutating command while another launcher is alive.
    ///
    /// @param action short description used in the refusal message
    /// @return [`#OK`] when no launcher is running, otherwise [`#LAUNCHER_RUNNING`]
    private static int rejectWhileLauncherRuns(String action) {
        LauncherInstanceRegistry registry = new LauncherInstanceRegistry(
                Metadata.XENON_GLOBAL_DIRECTORY.resolve(".instances"));
        long self = ProcessHandle.current().pid();
        for (LauncherInstance instance : registry.findAll()) {
            if (instance.pid() == self) {
                // Defensive: older entry points registered the CLI process in
                // the same group before dispatching the command.
                continue;
            }
            if (registry.isAlive(instance)) {
                System.err.println("xenon: refusing " + action
                        + " while Xenon is running (pid " + instance.pid()
                        + "); close the launcher and retry");
                return LAUNCHER_RUNNING;
            }
        }
        return OK;
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

    /// Finds one instance by exact id, exact name or a normalized name.
    ///
    /// @param repo  repository to search
    /// @param query user supplied reference
    /// @return the matching instance, or `null`
    private static @Nullable MindustryVersion find(XenonGameRepository repo, String query) {
        MindustryVersion byId = repo.get(query).orElse(null);
        if (byId != null) {
            return byId;
        }
        return repo.all().stream()
                .filter(version -> MindustryInstanceName.matches(version, query))
                .findFirst().orElse(null);
    }

    /// Opens the repository selected by the current profile.
    private static XenonGameRepository repository() throws IOException {
        XenonGameRepository repo = new XenonGameRepository(versionsRoot());
        repo.refresh();
        return repo;
    }

    /// Resolves the `saves/backups/` directory of one instance.
    ///
    /// @param repo    repository that owns the instance
    /// @param version instance to inspect
    /// @return absolute backups directory
    private static Path backupsDirectory(XenonGameRepository repo, MindustryVersion version) {
        Path versionRoot = repo.getVersionRoot(version);
        Path dataDir = version.resolveDataDir(versionRoot);
        return new SaveBackupService(dataDir.resolve("saves")).getBackupsDir();
    }

    /// Lists backup files newest first, falling back to the modification time
    /// for names that carry no parsable stamp.
    ///
    /// @param backupsDir directory to scan
    /// @return backup files, sorted newest first
    private static List<Path> listBackupFiles(Path backupsDir) throws IOException {
        if (!Files.isDirectory(backupsDir)) {
            return List.of();
        }
        try (var stream = Files.list(backupsDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(SaveBackupService.MSAV_EXT))
                    .sorted(Comparator.comparingLong(XenonCli::backupSortKey).reversed())
                    .toList();
        }
    }

    /// Sort key of one backup: its encoded stamp, else its file modification time.
    ///
    /// @param backup backup file
    /// @return milliseconds since the epoch
    private static long backupSortKey(Path backup) {
        Instant stamp = MindustryBackupNaming.timestamp(backup);
        if (stamp != null) {
            return stamp.toEpochMilli();
        }
        try {
            return Files.getLastModifiedTime(backup).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    /// Resolves a backup selector (file name, timestamp fragment or latest).
    ///
    /// @param backupsDir `saves/backups/` directory
    /// @param selector   user supplied selector, or `null` for the newest backup
    /// @return the selected backup, or `null`
    private static @Nullable Path resolveBackup(Path backupsDir, @Nullable String selector)
            throws IOException {
        List<Path> backups = listBackupFiles(backupsDir);
        if (backups.isEmpty()) {
            return null;
        }
        if (selector == null || selector.isBlank()) {
            return backups.get(0);
        }
        String trimmed = selector.trim();
        for (Path backup : backups) {
            if (backup.getFileName().toString().equals(trimmed)) {
                return backup;
            }
        }
        try {
            Path fileName = Path.of(trimmed).getFileName();
            if (fileName != null) {
                for (Path backup : backups) {
                    if (backup.getFileName().equals(fileName)) {
                        return backup;
                    }
                }
            }
        } catch (InvalidPathException ignored) {
            // The selector is a plain fragment, not a path.
        }
        List<Path> matches = new ArrayList<>();
        for (Path backup : backups) {
            if (backup.getFileName().toString().contains(trimmed)) {
                matches.add(backup);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            System.err.println("xenon: backup selector is ambiguous: " + trimmed);
            for (Path match : matches) {
                System.err.println("  " + match.getFileName());
            }
        }
        return null;
    }

    /// Parses the variant name used by `--import` and `--check-updates`.
    ///
    /// @param raw option value, possibly `null`
    /// @return the matching variant, or `null` when absent/unknown
    private static @Nullable VersionVariant parseVariant(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return switch (key) {
            case "vanilla" -> VersionVariant.VANILLA;
            case "be", "bleedingedge" -> VersionVariant.BE;
            case "mindustryx", "x" -> VersionVariant.MINDUSTRY_X;
            case "cnarc" -> VersionVariant.CN_ARC;
            case "foo", "fooclient" -> VersionVariant.FOO;
            case "custom" -> VersionVariant.CUSTOM;
            default -> null;
        };
    }

    /// Parses the `--import` command line.
    ///
    /// @param args command arguments
    /// @return the parsed request, or `null` when required parts are missing
    private static @Nullable ImportRequest parseImport(String[] args) {
        String jar = null;
        String id = null;
        String name = null;
        String variant = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--as" -> {
                    if (++i >= args.length) return null;
                    id = args[i];
                }
                case "--name" -> {
                    if (++i >= args.length) return null;
                    name = args[i];
                }
                case "--variant" -> {
                    if (++i >= args.length) return null;
                    variant = args[i];
                }
                default -> {
                    if (args[i].startsWith("--") || jar != null) {
                        return null;
                    }
                    jar = args[i];
                }
            }
        }
        return jar == null ? null : new ImportRequest(jar, id, name, variant);
    }

    /// Returns the value following `--option` anywhere on the command line.
    ///
    /// @param args   command arguments
    /// @param option option name, including the leading `--`
    /// @return the option value, or `null`
    private static @Nullable String optionValue(String[] args, String option) {
        for (int i = 1; i + 1 < args.length; i++) {
            if (option.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    /// Derives an import id from a jar file name.
    ///
    /// @param jar jar path
    /// @return the file name without `.jar`, or `mindustry`
    private static String jarFileName(Path jar) {
        String name = jar.getFileName() == null ? "mindustry" : jar.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    /// Extracts a readable message from an exception.
    ///
    /// @param error failure
    /// @return the message, or the class name when the exception has none
    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.toString() : message;
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

    /// One parsed `--import` request.
    ///
    /// @param jar     source jar path as written by the user
    /// @param id      requested instance id, or `null` to derive a unique one
    /// @param name    optional display name
    /// @param variant optional variant name
    private record ImportRequest(String jar, @Nullable String id, @Nullable String name,
                                 @Nullable String variant) {
    }

    private XenonCli() {
    }
}
