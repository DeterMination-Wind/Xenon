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
package determination.xenon.mindustry.scriptagent;

import determination.xenon.mindustry.server.ServerInstance;
import determination.xenon.mindustry.server.ServerInstanceManager;
import determination.xenon.mindustry.server.ServerProcess;
import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Discover and toggle ScriptAgent {@code .kts} modules sitting under
 * {@code <data>/config/scripts/}, plus issue ScriptAgent console commands
 * ({@code sa scan}, {@code sa load}, {@code sa hotReload}) against a
 * running {@link ServerProcess}.
 *
 * <p>The enable/disable flow is intentionally simple: it only toggles a
 * leading {@code "// "} prefix on whatever line carries the
 * {@code @Module} annotation. It does not understand Kotlin syntax and
 * will not touch lines that already look like comments for any other
 * reason. That is good enough for ScriptAgent's convention of a single
 * {@code @Module} annotation near the top of each script.</p>
 */
public final class ScriptAgentModuleManager {

    private static final String SCRIPTS_REL = "config/scripts";
    private static final String KTS_SUFFIX = ".kts";
    /** Matches {@code @Module} (optionally with whitespace and trailing args). */
    private static final Pattern MODULE_ANNOTATION = Pattern.compile("@Module\\b");

    private final ServerInstance instance;
    private final ServerInstanceManager manager;

    public ScriptAgentModuleManager(ServerInstance inst, ServerInstanceManager mgr) {
        this.instance = Objects.requireNonNull(inst, "inst");
        this.manager = Objects.requireNonNull(mgr, "mgr");
    }

    /**
     * Recursively scan {@code <data>/config/scripts} for {@code .kts}
     * files and return one {@link ScriptAgentModule} per file. Returns
     * an empty list if the scripts directory does not exist yet.
     */
    public List<ScriptAgentModule> list() throws IOException {
        Path scriptsDir = scriptsDir();
        if (!Files.isDirectory(scriptsDir)) {
            return Collections.emptyList();
        }
        List<ScriptAgentModule> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(scriptsDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(KTS_SUFFIX))
                    .sorted()
                    .forEach(p -> {
                        try {
                            out.add(read(p));
                        } catch (IOException ex) {
                            Logger.LOG.warning("Failed to read ScriptAgent module " + p + ": " + ex);
                        }
                    });
        }
        return out;
    }

    /**
     * Re-enable a module by stripping a leading {@code "// "} from any
     * line whose content (after the prefix) contains the
     * {@code @Module} annotation. No-op if the file is already enabled.
     */
    public void enable(ScriptAgentModule m) throws IOException {
        Objects.requireNonNull(m, "m");
        Path file = m.getFile();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.stripLeading();
            if (!trimmed.startsWith("//")) continue;
            String afterSlashes = trimmed.substring(2).stripLeading();
            if (MODULE_ANNOTATION.matcher(afterSlashes).find()) {
                String indent = line.substring(0, line.length() - trimmed.length());
                lines.set(i, indent + afterSlashes);
                changed = true;
            }
        }
        if (changed) {
            Files.write(file, lines, StandardCharsets.UTF_8);
            Logger.LOG.info("Enabled ScriptAgent module " + m.getModuleName());
        }
    }

    /**
     * Disable a module by prepending {@code "// "} to whatever line
     * carries an un-commented {@code @Module}. No-op if the file is
     * already disabled (or never had the annotation in the first place).
     */
    public void disable(ScriptAgentModule m) throws IOException {
        Objects.requireNonNull(m, "m");
        Path file = m.getFile();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("//")) continue; // already commented
            if (!MODULE_ANNOTATION.matcher(trimmed).find()) continue;
            String indent = line.substring(0, line.length() - trimmed.length());
            lines.set(i, indent + "// " + trimmed);
            changed = true;
        }
        if (changed) {
            Files.write(file, lines, StandardCharsets.UTF_8);
            Logger.LOG.info("Disabled ScriptAgent module " + m.getModuleName());
        }
    }

    /**
     * Tell a running server to hot-reload a single module via
     * {@code sa hotReload <name>}.
     */
    public void reloadHot(ServerProcess proc, String moduleName) throws IOException {
        Objects.requireNonNull(proc, "proc");
        Objects.requireNonNull(moduleName, "moduleName");
        proc.sendCommand("sa hotReload " + moduleName);
    }

    /** Trigger a full scan of the scripts directory ({@code sa scan}). */
    public void scan(ServerProcess proc) throws IOException {
        Objects.requireNonNull(proc, "proc");
        proc.sendCommand("sa scan");
    }

    /** Load a single module ({@code sa load <name>}). */
    public void load(ServerProcess proc, String moduleName) throws IOException {
        Objects.requireNonNull(proc, "proc");
        Objects.requireNonNull(moduleName, "moduleName");
        proc.sendCommand("sa load " + moduleName);
    }

    /** Resolved scripts directory ({@code <dataDir>/config/scripts}). */
    public Path scriptsDir() {
        Path serverRoot = manager.getServerRoot(instance.getId());
        return instance.resolveDataDir(serverRoot).resolve(SCRIPTS_REL);
    }

    // ---------- internals ----------

    private static ScriptAgentModule read(Path file) throws IOException {
        String fileName = file.getFileName().toString();
        String moduleName = fileName.endsWith(KTS_SUFFIX)
                ? fileName.substring(0, fileName.length() - KTS_SUFFIX.length())
                : fileName;
        boolean enabled = false;
        StringBuilder description = new StringBuilder();
        boolean inLeadingComments = true;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String raw : lines) {
            String trimmed = raw.strip();
            if (inLeadingComments) {
                if (trimmed.isEmpty()) {
                    // blank lines are tolerated within the leading comment block
                    continue;
                }
                if (trimmed.startsWith("//")) {
                    String text = trimmed.substring(2).strip();
                    if (!text.isEmpty()) {
                        if (description.length() > 0) description.append('\n');
                        description.append(text);
                    }
                    continue;
                }
                inLeadingComments = false;
            }
            if (!trimmed.startsWith("//") && MODULE_ANNOTATION.matcher(trimmed).find()) {
                enabled = true;
            }
        }
        return new ScriptAgentModule(file, moduleName, enabled, description.toString());
    }
}
