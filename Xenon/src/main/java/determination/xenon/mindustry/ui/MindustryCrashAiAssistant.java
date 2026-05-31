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
package determination.xenon.mindustry.ui;

import determination.xenon.Metadata;
import determination.xenon.mindustry.VersionVariant;
import determination.xenon.mindustry.crash.CrashReport;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.util.platform.OperatingSystem;
import javafx.application.Platform;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Launches an installed local coding assistant with Mindustry crash context.
@NotNullByDefault
final class MindustryCrashAiAssistant {
    /// Source archive for upstream Mindustry.
    private static final URI MINDUSTRY_SOURCE =
            URI.create("http://121.199.60.4/github/assets/source/Mindustry-master.zip");

    /// Source archive for MindustryX.
    private static final URI MINDUSTRY_X_SOURCE =
            URI.create("http://121.199.60.4/github/assets/source/MindustryX-main.zip");

    /// Package used when no supported assistant is installed.
    private static final String PI_NPM_PACKAGE = "@earendil-works/pi-coding-agent";

    /// Extracted directory name for upstream Mindustry source.
    private static final String MINDUSTRY_SOURCE_DIR = "Mindustry-master";

    /// Extracted directory name for MindustryX source.
    private static final String MINDUSTRY_X_SOURCE_DIR = "MindustryX-main";

    /// Timestamp format for generated prompt and script files.
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /// Shared HTTP client for source archive downloads.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /// Utility class.
    private MindustryCrashAiAssistant() {
    }

    /// Entry point used by crash-list rows.
    static void ask(CrashReport report, VersionVariant variant) {
        CliTool tool = findPreferredTool();
        if (tool != null) {
            confirmSourceDownload(report, variant, tool, false);
            return;
        }

        CliTool pi = findTool("pi");
        if (pi != null) {
            confirmSourceDownload(report, variant, pi, false);
            return;
        }

        Controllers.confirm(
                i18n("xenon.mindustry.crash.ai.install_pi.confirm", PI_NPM_PACKAGE),
                i18n("xenon.mindustry.crash.ai.title"),
                MessageDialogPane.MessageType.QUESTION,
                () -> confirmSourceDownload(report, variant, new CliTool("pi", Path.of("pi")), true),
                null);
    }

    /// Finds the first preferred assistant in the requested priority order.
    private static @Nullable CliTool findPreferredTool() {
        String[] names = {"opencode", "codex", "claude"};
        for (String name : names) {
            CliTool tool = findTool(name);
            if (tool != null) {
                return tool;
            }
        }
        return null;
    }

    /// Finds one command on PATH, preferring Windows .cmd shims when present.
    private static @Nullable CliTool findTool(String name) {
        List<String> command = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                ? List.of("where.exe", name)
                : List.of("which", name);
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String text;
            try (InputStream in = process.getInputStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (process.waitFor() != 0) {
                return null;
            }
            List<Path> paths = new ArrayList<>();
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) {
                    paths.add(Path.of(line.trim()));
                }
            }
            if (paths.isEmpty()) {
                return null;
            }
            for (Path path : paths) {
                if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cmd")) {
                    return new CliTool(name, path);
                }
            }
            return new CliTool(name, paths.get(0));
        } catch (IOException ex) {
            LOG.warning("Failed to locate AI CLI " + name, ex);
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /// Confirms source download only when the local source trees are not ready.
    private static void confirmSourceDownload(CrashReport report,
                                              VersionVariant variant,
                                              CliTool tool,
                                              boolean installPiFirst) {
        if (sourcesReady()) {
            prepareAndLaunch(report, variant, tool, installPiFirst);
            return;
        }
        Controllers.confirm(
                i18n("xenon.mindustry.crash.ai.download_source.confirm", sourceRoot()),
                i18n("xenon.mindustry.crash.ai.title"),
                MessageDialogPane.MessageType.QUESTION,
                () -> prepareAndLaunch(report, variant, tool, installPiFirst),
                null);
    }

    /// Prepares source code, prompt file, and runner script on a background thread.
    private static void prepareAndLaunch(CrashReport report,
                                         VersionVariant variant,
                                         CliTool tool,
                                         boolean installPiFirst) {
        Controllers.showToast(i18n("xenon.mindustry.crash.ai.preparing"));
        Schedulers.io().execute(() -> {
            try {
                SourceBundle sources = ensureSources();
                PromptFiles promptFiles = writePromptFiles(report, variant, sources);
                Path script = writeRunnerScript(tool, promptFiles, sources, installPiFirst);
                launchScript(script);
                Platform.runLater(() -> Controllers.showToast(
                        i18n("xenon.mindustry.crash.ai.launched", tool.name)));
            } catch (Exception ex) {
                LOG.warning("Failed to launch crash AI assistant", ex);
                Platform.runLater(() -> Controllers.dialog(
                        i18n("xenon.mindustry.crash.ai.failed", ex.getMessage()),
                        i18n("message.error"),
                        MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    /// Ensures both Mindustry source archives are available and extracted.
    private static SourceBundle ensureSources() throws IOException, InterruptedException {
        Path root = sourceRoot();
        Files.createDirectories(root);

        Path mindustry = root.resolve(MINDUSTRY_SOURCE_DIR);
        Path mindustryX = root.resolve(MINDUSTRY_X_SOURCE_DIR);
        ensureSource(MINDUSTRY_SOURCE, root, mindustry, MINDUSTRY_SOURCE_DIR + ".zip");
        ensureSource(MINDUSTRY_X_SOURCE, root, mindustryX, MINDUSTRY_X_SOURCE_DIR + ".zip");
        return new SourceBundle(root, mindustry, mindustryX);
    }

    /// Returns true when both source trees are already available locally.
    private static boolean sourcesReady() {
        Path root = sourceRoot();
        return Files.isDirectory(root.resolve(MINDUSTRY_SOURCE_DIR))
                && Files.isDirectory(root.resolve(MINDUSTRY_X_SOURCE_DIR));
    }

    /// Downloads and extracts one source archive when its target directory is missing.
    private static void ensureSource(URI uri, Path root, Path expectedDir, String archiveName)
            throws IOException, InterruptedException {
        if (Files.isDirectory(expectedDir)) {
            return;
        }
        Path archive = root.resolve(archiveName);
        Path partial = root.resolve(archiveName + ".part");
        download(uri, partial);
        Files.move(partial, archive, StandardCopyOption.REPLACE_EXISTING);
        unzip(archive, root);
        if (!Files.isDirectory(expectedDir)) {
            throw new IOException("Source archive did not create " + expectedDir);
        }
    }

    /// Streams one archive URL to a local file.
    private static void download(URI uri, Path target) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "Xenon-Launcher")
                .GET()
                .build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            try (InputStream ignored = response.body()) {
                ignored.transferTo(OutputStream.nullOutputStream());
            }
            throw new IOException("HTTP " + response.statusCode() + " downloading " + uri);
        }
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(target)) {
            in.transferTo(out);
        }
    }

    /// Extracts a zip archive under the given root, rejecting zip-slip paths.
    private static void unzip(Path archive, Path root) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path out = root.resolve(entry.getName()).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("Unsafe zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Path parent = out.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
                in.closeEntry();
            }
        }
    }

    /// Writes the full prompt, TUI input, and an in-memory crash copy when needed.
    private static PromptFiles writePromptFiles(CrashReport report,
                                                VersionVariant variant,
                                                SourceBundle sources) throws IOException {
        Path work = Metadata.XENON_GLOBAL_DIRECTORY.resolve("llm");
        Files.createDirectories(work);
        String stamp = FILE_STAMP.format(LocalDateTime.now());
        Path crashFile = report.getFile();
        if (crashFile == null) {
            crashFile = work.resolve("mindustry-crash-" + stamp + ".txt");
            Files.writeString(crashFile, report.getFullText(), StandardCharsets.UTF_8);
        }
        crashFile = crashFile.toAbsolutePath().normalize();

        Path promptFile = work.resolve("mindustry-crash-prompt-" + stamp + ".md");
        Path inputFile = work.resolve("mindustry-crash-tui-input-" + stamp + ".txt");
        Files.writeString(promptFile, buildPrompt(report, variant, crashFile, sources), StandardCharsets.UTF_8);
        Files.writeString(inputFile, buildTuiInput(promptFile, crashFile, sources), StandardCharsets.UTF_8);
        return new PromptFiles(promptFile, inputFile, crashFile);
    }

    /// Builds the user prompt passed to the external assistant.
    private static String buildPrompt(CrashReport report,
                                      VersionVariant variant,
                                      Path crashFile,
                                      SourceBundle sources) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze this Mindustry crash. Work read-only; do not edit or delete files.\n\n");
        prompt.append("Game: Mindustry\n");
        prompt.append("Variant: ").append(variant == null ? VersionVariant.CUSTOM : variant).append('\n');
        prompt.append("Crash category: ").append(report.getCategory()).append('\n');
        prompt.append("Crash summary: ").append(report.getSummary()).append('\n');
        prompt.append("Root class: ").append(report.getRootClass()).append("\n\n");
        prompt.append("Crash report path:\n");
        prompt.append(crashFile).append("\n\n");
        prompt.append("Source code paths:\n");
        prompt.append("- Mindustry: ").append(sources.mindustry).append('\n');
        prompt.append("- MindustryX: ").append(sources.mindustryX).append('\n');
        prompt.append("\nPlease inspect the crash file and source code, then provide:\n");
        prompt.append("1. the most likely root cause;\n");
        prompt.append("2. relevant classes/files and line hints;\n");
        prompt.append("3. a practical workaround for the player;\n");
        prompt.append("4. a concise fix direction for developers.\n");
        return prompt.toString();
    }

    /// Builds the compact text submitted to the interactive assistant TUI.
    private static String buildTuiInput(Path promptFile, Path crashFile, SourceBundle sources) {
        return "Analyze this Mindustry crash. Read the full prompt file first: "
                + promptFile.toAbsolutePath().normalize()
                + ". Crash report: "
                + crashFile.toAbsolutePath().normalize()
                + ". Source code: Mindustry="
                + sources.mindustry
                + "; MindustryX="
                + sources.mindustryX
                + ". Work read-only. Return the root cause, relevant files and line hints, "
                + "a player workaround, and a developer fix direction.";
    }

    /// Writes a PowerShell runner that invokes the chosen assistant TUI.
    private static Path writeRunnerScript(CliTool tool,
                                          PromptFiles promptFiles,
                                          SourceBundle sources,
                                          boolean installPiFirst) throws IOException {
        String stamp = FILE_STAMP.format(LocalDateTime.now());
        Path script = Metadata.XENON_GLOBAL_DIRECTORY.resolve("llm")
                .resolve("mindustry-crash-ai-" + stamp + ".ps1");
        String content = runnerScript(tool, promptFiles, sources, installPiFirst);
        Files.writeString(script, content, StandardCharsets.UTF_8);
        return script;
    }

    /// Creates the PowerShell script text used by the elevated Terminal process.
    private static String runnerScript(CliTool tool,
                                       PromptFiles promptFiles,
                                       SourceBundle sources,
                                       boolean installPiFirst) {
        StringBuilder script = new StringBuilder();
        script.append("$ErrorActionPreference = 'Continue'\r\n");
        script.append("[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\r\n");
        script.append("$OutputEncoding = [System.Text.Encoding]::UTF8\r\n");
        script.append("$env:PATH = \"$env:APPDATA\\npm;$env:PATH\"\r\n");
        script.append("Set-Location -LiteralPath 'C:\\.'\r\n");
        script.append("$promptFile = ").append(psQuote(promptFiles.promptFile.toString())).append("\r\n");
        script.append("$tuiInputFile = ").append(psQuote(promptFiles.inputFile.toString())).append("\r\n");
        script.append("$sourceRoot = ").append(psQuote(sources.root.toString())).append("\r\n");
        script.append("$crashDir = ").append(psQuote(crashContextDir(promptFiles.crashFile).toString())).append("\r\n");
        script.append("$promptDir = ").append(psQuote(crashContextDir(promptFiles.promptFile).toString())).append("\r\n");
        script.append("$toolName = ").append(psQuote(tool.name)).append("\r\n");
        script.append("$toolPath = ").append(psQuote(tool.executable.toString())).append("\r\n");
        script.append("$installPiFirst = $").append(installPiFirst ? "true" : "false").append("\r\n");
        script.append("\r\n");
        script.append("function Resolve-XenonTool([string] $Name, [string] $Fallback) {\r\n");
        script.append("    foreach ($candidate in @(\"$Name.cmd\", \"$Name.exe\", \"$Name.bat\", $Name)) {\r\n");
        script.append("        $command = Get-Command $candidate -ErrorAction SilentlyContinue | Select-Object -First 1\r\n");
        script.append("        if ($null -ne $command) {\r\n");
        script.append("            if ($command.Source) { return $command.Source }\r\n");
        script.append("            if ($command.Path) { return $command.Path }\r\n");
        script.append("        }\r\n");
        script.append("    }\r\n");
        script.append("    if ($Fallback -and (Test-Path -LiteralPath $Fallback)) { return $Fallback }\r\n");
        script.append("    if ($Fallback) { return $Fallback }\r\n");
        script.append("    return $null\r\n");
        script.append("}\r\n");
        script.append("\r\n");
        script.append("try {\r\n");
        script.append("    $toolPath = Resolve-XenonTool $toolName $toolPath\r\n");
        script.append("    Write-Host \"Full prompt file: $promptFile\"\r\n");
        script.append("    Write-Host \"TUI input file: $tuiInputFile\"\r\n");
        script.append("    Write-Host \"Tool path: $toolPath\"\r\n");
        script.append("    Write-Host \"\"\r\n");
        script.append("    if ($installPiFirst) {\r\n");
        script.append("        $npm = Resolve-XenonTool 'npm' ''\r\n");
        script.append("        if (-not $npm) { throw 'npm.cmd not found. Install Node.js first.' }\r\n");
        script.append("        & $npm install -g ").append(psQuote(PI_NPM_PACKAGE)).append("\r\n");
        script.append("        if ($LASTEXITCODE -ne 0) { throw \"Pi installation failed with exit code $LASTEXITCODE\" }\r\n");
        script.append("        $toolName = 'pi'\r\n");
        script.append("        $toolPath = Resolve-XenonTool $toolName 'pi'\r\n");
        script.append("    }\r\n");
        script.append("    if (-not $toolPath) { throw \"Unable to resolve tool: $toolName\" }\r\n");
        script.append("    $prompt = Get-Content -LiteralPath $tuiInputFile -Raw -Encoding UTF8\r\n");
        script.append("    if ([string]::IsNullOrWhiteSpace($prompt)) { throw \"Prompt file is empty: $tuiInputFile\" }\r\n");
        script.append("    Write-Host \"Starting $toolName in Windows Terminal from C:\\.\"\r\n");
        script.append("    Write-Host \"Submitting prompt through the TUI initial prompt argument.\"\r\n");
        script.append("    Write-Host \"\"\r\n");
        script.append("    Start-Sleep -Seconds 2\r\n");
        script.append("    switch ($toolName.ToLowerInvariant()) {\r\n");
        script.append("        'opencode' { & $toolPath --prompt $prompt }\r\n");
        script.append("        'codex' { & $toolPath -C 'C:\\.' -s read-only --add-dir $sourceRoot --add-dir $crashDir --add-dir $promptDir $prompt }\r\n");
        script.append("        'claude' { & $toolPath --permission-mode default --add-dir $sourceRoot --add-dir $crashDir --add-dir $promptDir $prompt }\r\n");
        script.append("        'pi' { & $toolPath --tools read,grep,find,ls --no-session $prompt }\r\n");
        script.append("        default { throw \"Unsupported tool: $toolName\" }\r\n");
        script.append("    }\r\n");
        script.append("    $exitCode = if ($LASTEXITCODE -is [int]) { $LASTEXITCODE } else { 0 }\r\n");
        script.append("    Write-Host \"\"\r\n");
        script.append("    Write-Host \"AI session ended with exit code: $exitCode\"\r\n");
        script.append("} catch {\r\n");
        script.append("    $exitCode = 1\r\n");
        script.append("    Write-Host \"\"\r\n");
        script.append("    Write-Host -ForegroundColor Red $_.Exception.Message\r\n");
        script.append("} finally {\r\n");
        script.append("    Write-Host \"Full prompt file remains available at: $promptFile\"\r\n");
        script.append("    Write-Host \"\"\r\n");
        script.append("    Read-Host 'Press Enter to close this terminal'\r\n");
        script.append("}\r\n");
        script.append("exit $exitCode\r\n");
        return script.toString();
    }

    /// Launches the generated script in an elevated Windows Terminal tab.
    private static void launchScript(Path script) throws IOException {
        if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS) {
            throw new IOException("Elevated AI launch is only implemented on Windows");
        }
        @Nullable String systemRoot = System.getenv("SystemRoot");
        List<String> command = new ArrayList<>();
        command.add(windowsTerminal(systemRoot));
        command.add("--window");
        command.add("new");
        command.add("new-tab");
        command.add("--elevate");
        command.add("--title");
        command.add("Xenon Mindustry Crash AI");
        command.add("-d");
        command.add("C:\\.");
        command.add("powershell.exe");
        command.add("-NoLogo");
        command.add("-NoExit");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(script.toAbsolutePath().normalize().toString());
        new ProcessBuilder(command)
                .directory(Path.of("C:\\.").toFile())
                .start();
    }

    /// Returns the Windows Terminal launcher path, falling back to the app execution alias.
    private static String windowsTerminal(@Nullable String systemRoot) {
        String root = systemRoot == null || systemRoot.isBlank() ? "C:\\Windows" : systemRoot;
        Path systemWt = Path.of(root, "System32", "wt.exe");
        if (Files.isRegularFile(systemWt)) {
            return systemWt.toString();
        }
        @Nullable String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            Path alias = Path.of(localAppData, "Microsoft", "WindowsApps", "wt.exe");
            if (Files.isRegularFile(alias)) {
                return alias.toString();
            }
        }
        return "wt.exe";
    }

    /// Returns the sibling AppData directory used for downloaded source trees.
    private static Path sourceRoot() {
        @Nullable Path parent = Metadata.XENON_GLOBAL_DIRECTORY.getParent();
        Path base = parent == null ? Metadata.XENON_GLOBAL_DIRECTORY : parent;
        return base.resolve("Xenon-llm-codebase").toAbsolutePath().normalize();
    }

    /// Returns a directory that an external assistant may read for crash context.
    private static Path crashContextDir(Path crashFile) {
        @Nullable Path parent = crashFile.getParent();
        if (parent != null) {
            return parent;
        }
        @Nullable Path root = crashFile.toAbsolutePath().normalize().getRoot();
        return root == null ? Path.of(".").toAbsolutePath().normalize() : root;
    }

    /// Quotes a string as a PowerShell single-quoted literal.
    private static String psQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /// A discovered command-line assistant.
    @NotNullByDefault
    private static final class CliTool {
        /// Short command name used for dispatch.
        final String name;

        /// Executable path or command name.
        final Path executable;

        /// Creates a discovered command descriptor.
        CliTool(String name, Path executable) {
            this.name = name;
            this.executable = executable;
        }
    }

    /// Extracted local source tree paths.
    @NotNullByDefault
    private static final class SourceBundle {
        /// Root directory holding all downloaded sources.
        final Path root;

        /// Extracted upstream Mindustry source directory.
        final Path mindustry;

        /// Extracted MindustryX source directory.
        final Path mindustryX;

        /// Creates a source bundle descriptor.
        SourceBundle(Path root, Path mindustry, Path mindustryX) {
            this.root = root.toAbsolutePath().normalize();
            this.mindustry = mindustry.toAbsolutePath().normalize();
            this.mindustryX = mindustryX.toAbsolutePath().normalize();
        }
    }

    /// Generated prompt, TUI input, and crash paths.
    @NotNullByDefault
    private static final class PromptFiles {
        /// Prompt file passed to the assistant.
        final Path promptFile;

        /// Compact prompt text submitted to the assistant TUI.
        final Path inputFile;

        /// Crash file path referenced by the prompt.
        final Path crashFile;

        /// Creates a generated file descriptor.
        PromptFiles(Path promptFile, Path inputFile, Path crashFile) {
            this.promptFile = promptFile.toAbsolutePath().normalize();
            this.inputFile = inputFile.toAbsolutePath().normalize();
            this.crashFile = crashFile.toAbsolutePath().normalize();
        }
    }
}
