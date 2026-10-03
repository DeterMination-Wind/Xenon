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

import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.VersionVariant;
import determination.xenon.util.i18n.I18n;
import determination.xenon.util.i18n.SupportedLocale;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the structured instance compatibility evaluator.
@NotNullByDefault
public final class MindustryCompatibilityTest {

    /// Pins the English bundle so message assertions are locale independent.
    @BeforeAll
    public static void useEnglishLocale() {
        I18n.setLocale(SupportedLocale.getLocale(Locale.ENGLISH));
    }

    /// Restores the process default locale after this class.
    @AfterAll
    public static void restoreLocale() {
        I18n.setLocale(SupportedLocale.DEFAULT);
    }

    /// A missing instance jar is the only finding of an otherwise empty instance.
    @Test
    public void reportsMissingInstanceJar(@TempDir Path root) throws IOException {
        MindustryVersion version = instance(root, 146, false);
        List<MindustryCompatibility.Issue> issues = evaluate(version, root);
        assertEquals(1, issues.size());
        assertEquals(MindustryCompatibility.Severity.ERROR, issues.get(0).severity());
        assertTrue(issues.get(0).message().contains("test-instance.jar"));
    }

    /// An unknown build reports an INFO finding and skips version checks.
    @Test
    public void unknownBuildSkipsVersionChecks(@TempDir Path root) throws IOException {
        MindustryVersion version = instance(root, 0, true);
        writeMod(modsDir(root), "future.jar", "future", "Future Mod", 2000);
        List<MindustryCompatibility.Issue> issues = evaluate(version, root);
        assertEquals(1, issues.size());
        assertEquals(MindustryCompatibility.Severity.INFO, issues.get(0).severity());
    }

    /// A mod requiring a newer game build is reported as a warning.
    @Test
    public void reportsModRequiringNewerBuild(@TempDir Path root) throws IOException {
        MindustryVersion version = instance(root, 146, true);
        writeMod(modsDir(root), "future.jar", "future", "Future Mod", 200);
        List<String> warnings = messages(evaluate(version, root), MindustryCompatibility.Severity.WARNING);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("Future Mod"));
        assertTrue(warnings.get(0).contains("200"));
    }

    /// Disabled archives never load, so their own requirements are ignored.
    @Test
    public void disabledModDoesNotReportRequirements(@TempDir Path root) throws IOException {
        MindustryVersion version = instance(root, 146, true);
        writeMod(modsDir(root), "future.jar.disabled", "future", "Future Mod", 200, "missing-lib");
        assertTrue(evaluate(version, root).isEmpty());
    }

    /// A duplicate loser is reported while its own requirements are ignored.
    @Test
    public void duplicateLoserIsReportedButItsRequirementsAreIgnored(@TempDir Path root) throws IOException {
        MindustryVersion version = instance(root, 146, true);
        writeMod(modsDir(root), "a-older.jar", "dupe", "Old Mod", 999);
        writeMod(modsDir(root), "b-newer.jar", "dupe", "New Mod", 100);
        // Pin the load order instead of relying on write timestamps.
        Files.setLastModifiedTime(modsDir(root).resolve("a-older.jar"),
                FileTime.from(Instant.now().minusSeconds(10)));
        Files.setLastModifiedTime(modsDir(root).resolve("b-newer.jar"), FileTime.from(Instant.now()));
        List<String> warnings = messages(evaluate(version, root), MindustryCompatibility.Severity.WARNING);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("Old Mod"));
        assertTrue(warnings.get(0).contains("b-newer.jar"));
    }

    /// A missing dependency of a loadable mod is reported.
    @Test
    public void reportsMissingDependency(@TempDir Path root) throws IOException {
        MindustryVersion version = instance(root, 146, true);
        writeMod(modsDir(root), "dep.jar", "depmod", "Dep Mod", 100, "missing-lib");
        List<String> warnings = messages(evaluate(version, root), MindustryCompatibility.Severity.WARNING);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("Dep Mod"));
        assertTrue(warnings.get(0).contains("missing-lib"));
    }

    /// A dependency that exists but is disabled is reported as disabled.
    @Test
    public void reportsDisabledDependency(@TempDir Path root) throws IOException {
        MindustryVersion version = instance(root, 146, true);
        writeMod(modsDir(root), "dep.jar", "depmod", "Dep Mod", 100, "lib-mod");
        writeMod(modsDir(root), "lib.jar.disabled", "lib-mod", "Lib Mod", 100);
        List<String> warnings = messages(evaluate(version, root), MindustryCompatibility.Severity.WARNING);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("lib-mod"));
    }

    /// A disabled mod does not report its own missing dependencies.
    @Test
    public void disabledModDoesNotReportDependencies(@TempDir Path root) throws IOException {
        MindustryVersion version = instance(root, 146, true);
        writeMod(modsDir(root), "dep.jar.disabled", "depmod", "Dep Mod", 100, "missing-lib");
        assertTrue(evaluate(version, root).isEmpty());
    }

    /// A broken archive is reported instead of failing the evaluation.
    @Test
    public void reportsUnparsableArchive(@TempDir Path root) throws IOException {
        MindustryVersion version = instance(root, 146, true);
        Files.createDirectories(modsDir(root));
        Files.writeString(modsDir(root).resolve("broken.jar"), "not a zip");
        List<String> warnings = messages(evaluate(version, root), MindustryCompatibility.Severity.WARNING);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("broken.jar"));
    }

    /// A broken disabled archive is not reported because it never loads.
    @Test
    public void ignoresUnparsableDisabledArchive(@TempDir Path root) throws IOException {
        MindustryVersion version = instance(root, 146, true);
        Files.createDirectories(modsDir(root));
        Files.writeString(modsDir(root).resolve("broken.jar.disabled"), "not a zip");
        assertTrue(evaluate(version, root).isEmpty());
    }

    /// Findings are sorted from ERROR to WARNING to INFO.
    @Test
    public void issuesAreSortedBySeverity(@TempDir Path root) throws IOException {
        MindustryVersion version = instance(root, 0, false);
        Files.createDirectories(modsDir(root));
        Files.writeString(modsDir(root).resolve("broken.jar"), "not a zip");
        assertEquals(
                List.of(MindustryCompatibility.Severity.ERROR,
                        MindustryCompatibility.Severity.WARNING,
                        MindustryCompatibility.Severity.INFO),
                evaluate(version, root).stream().map(MindustryCompatibility.Issue::severity).toList());
    }

    /// Newly introduced findings are kept while known ones are dropped.
    @Test
    public void newlyIntroducedFiltersKnownIssues() {
        MindustryCompatibility.Issue known =
                new MindustryCompatibility.Issue(MindustryCompatibility.Severity.ERROR, "known");
        MindustryCompatibility.Issue added =
                new MindustryCompatibility.Issue(MindustryCompatibility.Severity.WARNING, "added");
        assertEquals(List.of(added),
                MindustryCompatibility.newlyIntroduced(List.of(known), List.of(known, added)));
        assertEquals(List.of(known),
                MindustryCompatibility.newlyIntroduced(List.of(), List.of(known)));
    }

    /// Evaluates one instance against its temporary roots.
    private static List<MindustryCompatibility.Issue> evaluate(MindustryVersion version, Path root) {
        return MindustryCompatibility.forInstance(version, root, dataDir(root));
    }

    /// Mindustry data directory inside `root`.
    private static Path dataDir(Path root) {
        return root.resolve("data");
    }

    /// Mods directory inside `root`.
    private static Path modsDir(Path root) {
        return dataDir(root).resolve("mods");
    }

    /// Creates a Mindustry instance descriptor, optionally with its jar file.
    private static MindustryVersion instance(Path root, int build, boolean withJar) throws IOException {
        MindustryVersion version = new MindustryVersion();
        version.setId("test-instance");
        version.setName("Test Instance");
        version.setVariant(VersionVariant.VANILLA);
        version.setBuild(build);
        if (withJar) {
            Files.writeString(root.resolve("test-instance.jar"), "");
        }
        return version;
    }

    /// Writes one mod archive with the given descriptor fields.
    private static void writeMod(Path modsDir, String fileName, String name, String displayName,
                                 int minGameVersion, String... dependencies) throws IOException {
        Files.createDirectories(modsDir);
        StringBuilder descriptor = new StringBuilder()
                .append("name: ").append(name).append('\n')
                .append("displayName: ").append(displayName).append('\n')
                .append("minGameVersion: ").append(minGameVersion).append('\n');
        if (dependencies.length > 0) {
            descriptor.append("dependencies: [");
            for (int i = 0; i < dependencies.length; i++) {
                if (i > 0) {
                    descriptor.append(", ");
                }
                descriptor.append('"').append(dependencies[i]).append('"');
            }
            descriptor.append("]\n");
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(modsDir.resolve(fileName)))) {
            zip.putNextEntry(new ZipEntry("mod.hjson"));
            zip.write(descriptor.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    /// Returns the messages of all findings with the given severity.
    private static List<String> messages(List<MindustryCompatibility.Issue> issues,
                                         MindustryCompatibility.Severity severity) {
        return issues.stream()
                .filter(issue -> issue.severity() == severity)
                .map(MindustryCompatibility.Issue::message)
                .toList();
    }
}
