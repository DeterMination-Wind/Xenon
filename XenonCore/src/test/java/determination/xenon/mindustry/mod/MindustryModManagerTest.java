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
package determination.xenon.mindustry.mod;

import determination.xenon.mindustry.uuid.MindustrySettingsBin;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests Mindustry local mod scanning behavior.
@NotNullByDefault
public final class MindustryModManagerTest {

    /// Marks older enabled mods as ignored when another enabled mod wins the same internal name.
    @Test
    public void marksOlderDuplicateInternalNameAsIgnored(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        Path older = writeMod(modsDir.resolve("duplicate.zip"), "Duplicate Mod", "Older copy");
        Path newer = writeMod(modsDir.resolve("duplicate1.zip"), "Duplicate Mod", "Newer copy");
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000L));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000L));

        List<MindustryLocalMod> mods = new MindustryModManager(modsDir).scan();

        MindustryLocalMod olderMod = find(mods, "duplicate.zip");
        MindustryLocalMod newerMod = find(mods, "duplicate1.zip");
        assertEquals("duplicate-mod", olderMod.getInternalName());
        assertTrue(olderMod.isIgnoredByDuplicate());
        assertEquals("duplicate1.zip", olderMod.getIgnoredByFileName());
        assertFalse(newerMod.isIgnoredByDuplicate());
    }

    /// Disabled duplicate archives are not treated as load-order winners.
    @Test
    public void ignoresDisabledArchivesWhenChoosingDuplicateWinner(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        Path enabled = writeMod(modsDir.resolve("duplicate.zip"), "Duplicate Mod", "Enabled copy");
        Path disabled = writeMod(modsDir.resolve("duplicate1.zip.disabled"), "Duplicate Mod", "Disabled copy");
        Files.setLastModifiedTime(enabled, FileTime.fromMillis(1_000L));
        Files.setLastModifiedTime(disabled, FileTime.fromMillis(2_000L));

        List<MindustryLocalMod> mods = new MindustryModManager(modsDir).scan();

        assertFalse(find(mods, "duplicate.zip").isIgnoredByDuplicate());
        assertFalse(find(mods, "duplicate1.zip.disabled").isIgnoredByDuplicate());
    }

    /// A malformed earlier descriptor should not hide a later valid descriptor from the scan result.
    @Test
    public void fallsBackToLaterDescriptorWhenFirstDescriptorIsMalformed(@TempDir Path tempDir)
            throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeArchive(modsDir.resolve("fallback.zip"),
                "mod.hjson", "{ name: broken",
                "mod.json", """
                        {
                          "name": "fallback-mod",
                          "displayName": "Fallback Mod",
                          "author": "Xenon",
                          "version": "1"
                        }
                        """);

        List<MindustryLocalMod> mods = new MindustryModManager(modsDir).scan();

        assertEquals(1, mods.size());
        assertEquals("Fallback Mod", find(mods, "fallback.zip").getDisplayName());
    }

    /// Neon-style comma-less Hjson should parse directly without requiring a JSON fallback.
    @Test
    public void parsesCommaLessHjsonDescriptor(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeArchive(modsDir.resolve("neon.zip"),
                "mod.hjson", """
                        name: neon
                        displayName: Neon / 氖
                        author: DeterMination-Wind
                        version: 1
                        description: Utility mod
                        """);

        List<MindustryLocalMod> mods = new MindustryModManager(modsDir).scan();

        assertEquals("Neon / 氖", find(mods, "neon.zip").getDisplayName());
    }

    /// Full Hjson support includes comments, comma-less fields, and triple-quoted strings.
    @Test
    public void parsesTripleQuotedHjsonDescription(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeArchive(modsDir.resolve("mod-tools.zip"),
                "mod.hjson", """
                        # Mindustry mod metadata
                        name: mod-tools
                        description: '''
                        A mod that provides tools for modding.

                        Version 1.5.6
                        '''
                        author: I hope...
                        version: 1.5.6
                        minGameVersion: "154"
                        main: modtools.ModTools
                        """);

        MindustryLocalMod mod = find(new MindustryModManager(modsDir).scan(), "mod-tools.zip");

        assertEquals("mod-tools", mod.getName());
        assertTrue(mod.getDescription().contains("Version 1.5.6"));
        assertEquals(154, mod.getMinGameVersion());
    }

    /// Arc accepts raw line breaks inside double-quoted metadata strings.
    @Test
    public void parsesArcStyleMultilineDoubleQuotedDescription(@TempDir Path tempDir)
            throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeArchive(modsDir.resolve("hydrogen.zip"),
                "mod.hjson", """
                        displayName: Hydrogen
                        name: hydrogen
                        version: v3.0.2
                        minGameVersion: 154
                        java: true
                        description: "
                        Client-side QoL mod.

                        Supports multiplayer games.
                        "
                        """);

        MindustryLocalMod mod = find(new MindustryModManager(modsDir).scan(), "hydrogen.zip");

        assertEquals("Hydrogen", mod.getDisplayName());
        assertTrue(mod.getDescription().contains("Supports multiplayer games."));
    }

    /// GitHub-style archives may wrap the complete mod in one top-level directory.
    @Test
    public void parsesDescriptorInsideSingleWrapperDirectory(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeArchive(modsDir.resolve("wrapped.zip"),
                "wrapped-mod/mod.hjson", """
                        name: wrapped
                        displayName: Wrapped Mod
                        version: 1
                        """,
                "wrapped-mod/scripts/main.js", "print('loaded')");

        List<MindustryLocalMod> mods = new MindustryModManager(modsDir).scan();

        assertEquals("Wrapped Mod", find(mods, "wrapped.zip").getDisplayName());
    }

    /// A nested library descriptor is not the archive's main mod descriptor.
    @Test
    public void ignoresDescriptorInArbitraryNestedDirectory(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeArchive(modsDir.resolve("not-a-mod.zip"),
                "libs/mod.hjson", "name: nested-library",
                "README.md", "Not a Mindustry mod archive");

        List<MindustryLocalMod> mods = new MindustryModManager(modsDir).scan();

        assertTrue(mods.isEmpty());
    }

    /// Mindustry's settings.bin disabled flag disables a normal archive.
    @Test
    public void gameSideDisabledSettingMakesArchiveDisabled(@TempDir Path tempDir)
            throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeMod(modsDir.resolve("alpha.zip"), "alpha", "Alpha");
        MindustrySettingsBin.putValuesOrThrow(tempDir,
                Map.of("mod-alpha-enabled", Boolean.FALSE));

        List<MindustryLocalMod> mods = new MindustryModManager(modsDir).scan();

        assertFalse(find(mods, "alpha.zip").isEnabled());
    }

    /// A .disabled archive remains disabled even when settings.bin says the mod is enabled.
    @Test
    public void disabledArchiveWinsOverGameSideEnabledSetting(@TempDir Path tempDir)
            throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeMod(modsDir.resolve("alpha.zip.disabled"), "alpha", "Alpha");
        MindustrySettingsBin.putValuesOrThrow(tempDir,
                Map.of("mod-alpha-enabled", Boolean.TRUE));

        List<MindustryLocalMod> mods = new MindustryModManager(modsDir).scan();

        assertFalse(find(mods, "alpha.zip.disabled").isEnabled());
    }

    /// Launcher toggles keep both the archive suffix and settings.bin in sync.
    @Test
    public void enableDisableOperationsUpdateArchiveAndSettings(@TempDir Path tempDir)
            throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeMod(modsDir.resolve("alpha.zip"), "alpha", "Alpha");
        MindustryModManager manager = new MindustryModManager(modsDir);

        manager.disable(find(manager.scan(), "alpha.zip"));

        assertTrue(Files.isRegularFile(modsDir.resolve("alpha.zip.disabled")));
        assertFalse(MindustrySettingsBin.getBool(tempDir, "mod-alpha-enabled", true));

        manager.enable(find(manager.scan(), "alpha.zip.disabled"));

        assertTrue(Files.isRegularFile(modsDir.resolve("alpha.zip")));
        assertTrue(MindustrySettingsBin.getBool(tempDir, "mod-alpha-enabled", false));
    }

    /// Enabled mods sort alphabetically before the disabled alphabetical group.
    @Test
    public void sortsEnabledGroupBeforeDisabledGroup(@TempDir Path tempDir)
            throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeMod(modsDir.resolve("zulu.zip"), "zulu", "zulu");
        writeMod(modsDir.resolve("alpha.zip"), "alpha", "Alpha");
        writeMod(modsDir.resolve("beta.zip"), "beta", "Beta");
        writeMod(modsDir.resolve("delta.zip.disabled"), "delta", "delta");
        MindustrySettingsBin.putValuesOrThrow(tempDir,
                Map.of("mod-beta-enabled", Boolean.FALSE));

        List<MindustryLocalMod> mods = new MindustryModManager(modsDir).scan();

        assertEquals(List.of("Alpha", "zulu", "Beta", "delta"),
                mods.stream().map(MindustryLocalMod::displayName).toList());
    }

    /// Installing under the release asset name replaces the previous copy of the same mod.
    @Test
    public void installUsesPreferredFileNameAndReplacesSameMod(@TempDir Path tempDir)
            throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeMod(modsDir.resolve("logic-sugar-v1.0.0.jar"), "logic-sugar", "Logic Sugar");
        Path staged = writeMod(tempDir.resolve("xenon-mod-owner_repo-123456.jar"),
                "logic-sugar", "Logic Sugar");

        MindustryModManager.InstallResult result =
                new MindustryModManager(modsDir).install(staged, "LogicSugar-v5.7.0.jar");

        assertEquals("LogicSugar-v5.7.0.jar",
                result.installedFile().getFileName().toString());
        assertEquals(List.of("logic-sugar-v1.0.0.jar"),
                result.replacedFiles().stream()
                        .map(file -> file.getFileName().toString())
                        .toList());
        List<MindustryLocalMod> mods = new MindustryModManager(modsDir).scan();
        assertEquals(1, mods.size());
        assertEquals("LogicSugar-v5.7.0.jar",
                mods.get(0).getFile().getFileName().toString());
    }

    /// Installing a different mod that shares the file name keeps the existing archive.
    @Test
    public void installKeepsDifferentModWithSameFileName(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeMod(modsDir.resolve("mod.jar"), "alpha", "Alpha");
        Path staged = writeMod(tempDir.resolve("staging.jar"), "beta", "Beta");

        MindustryModManager.InstallResult result =
                new MindustryModManager(modsDir).install(staged, "mod.jar");

        assertEquals("mod-2.jar", result.installedFile().getFileName().toString());
        assertTrue(result.replacedFiles().isEmpty());
        assertEquals(2, new MindustryModManager(modsDir).scan().size());
    }

    /// scan() hides broken archives, while scanAll() lists them after the readable mods.
    @Test
    public void scanAllKeepsUnreadableArchives(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeMod(modsDir.resolve("alpha.zip"), "alpha", "Alpha");
        writeArchive(modsDir.resolve("release-bundle.zip"), "alpha.jar", "not a mod");

        assertTrue(new MindustryModManager(modsDir).scan().stream()
                .noneMatch(MindustryLocalMod::isUnparsable));

        List<MindustryLocalMod> all = new MindustryModManager(modsDir).scanAll();

        assertEquals(2, all.size());
        assertEquals("Alpha", all.get(0).displayName());
        MindustryLocalMod broken = all.get(1);
        assertEquals("release-bundle.zip", broken.getFile().getFileName().toString());
        assertEquals("release-bundle.zip", broken.displayName());
        assertTrue(broken.isUnparsable());
        assertTrue(broken.getParseError().contains("No mod.json"));
    }

    /// Archives without a descriptor are rejected instead of landing in mods/ as unloadable files.
    @Test
    public void installRejectsArchiveWithoutDescriptor(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        Path staged = writeArchive(tempDir.resolve("bundle.zip"), "Mod.jar", "not a descriptor");

        assertThrows(MindustryModParseException.class,
                () -> new MindustryModManager(modsDir).install(staged, "bundle.zip"));
        assertTrue(new MindustryModManager(modsDir).scanAll().isEmpty());
    }

    /// A descriptor inside a single wrapper directory stays installable, matching the game's lookup.
    @Test
    public void installAcceptsWrapperDirectoryDescriptor(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        Path staged = writeArchive(tempDir.resolve("staged.zip"),
                "wrapped/mod.json", """
                        {
                          "name": "wrapped",
                          "displayName": "Wrapped",
                          "version": "1"
                        }
                        """);

        MindustryModManager.InstallResult result =
                new MindustryModManager(modsDir).install(staged, "Wrapped-v1.zip");

        assertEquals("Wrapped-v1.zip", result.installedFile().getFileName().toString());
        assertEquals(1, new MindustryModManager(modsDir).scan().size());
    }

    /// containsDescriptor mirrors the game's descriptor lookup used for install decisions.
    @Test
    public void containsDescriptorMirrorsGameLookup(@TempDir Path tempDir) throws IOException {
        Path root = writeMod(tempDir.resolve("root.zip"), "root", "Root");
        Path wrapped = writeArchive(tempDir.resolve("wrapped.zip"),
                "wrapped/mod.hjson", "name: wrapped");
        Path bundle = writeArchive(tempDir.resolve("bundle.zip"), "Mod.jar", "not a descriptor");

        assertTrue(MindustryModParser.containsDescriptor(root));
        assertTrue(MindustryModParser.containsDescriptor(wrapped));
        assertFalse(MindustryModParser.containsDescriptor(bundle));
    }

    /// A batch toggle renames every archive and writes settings.bin exactly once.
    @Test
    public void batchSetEnabledCoalescesSettingsWrite(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeMod(modsDir.resolve("alpha.zip"), "alpha", "Alpha");
        writeMod(modsDir.resolve("beta.zip"), "beta", "Beta");
        List<Integer> batchSizes = new ArrayList<>();
        MindustryModManager manager = new MindustryModManager(modsDir, tempDir, (dataDir, values) -> {
            batchSizes.add(values.size());
            MindustrySettingsBin.putValuesOrThrow(dataDir, values);
        });

        MindustryModManager.BatchResult result = manager.setEnabled(manager.scan(), false);

        assertEquals(List.of(2), batchSizes);
        assertEquals(2, result.applied().size());
        assertTrue(result.failures().isEmpty());
        assertTrue(Files.isRegularFile(modsDir.resolve("alpha.zip.disabled")));
        assertTrue(Files.isRegularFile(modsDir.resolve("beta.zip.disabled")));
        assertFalse(MindustrySettingsBin.getBool(tempDir, "mod-alpha-enabled", true));
        assertFalse(MindustrySettingsBin.getBool(tempDir, "mod-beta-enabled", true));
    }

    /// One failing rename does not prevent the rest of the batch from being applied.
    @Test
    public void batchSetEnabledContinuesAfterSingleFailure(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeMod(modsDir.resolve("alpha.zip"), "alpha", "Alpha");
        writeMod(modsDir.resolve("beta.zip"), "beta", "Beta");
        MindustryModManager manager = new MindustryModManager(modsDir);
        List<MindustryLocalMod> mods = manager.scan();
        Files.delete(alphaFile(mods));

        MindustryModManager.BatchResult result = manager.setEnabled(mods, false);

        assertEquals(1, result.applied().size());
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().containsKey("alpha.zip"));
        assertTrue(Files.isRegularFile(modsDir.resolve("beta.zip.disabled")));
        assertFalse(Files.exists(modsDir.resolve("alpha.zip.disabled")));
    }

    /// Batch deletion removes every requested archive.
    @Test
    public void batchDeleteAllRemovesEveryArchive(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeMod(modsDir.resolve("alpha.zip"), "alpha", "Alpha");
        writeMod(modsDir.resolve("beta.zip"), "beta", "Beta");
        MindustryModManager manager = new MindustryModManager(modsDir);

        MindustryModManager.BatchResult result = manager.deleteAll(manager.scan());

        assertEquals(2, result.applied().size());
        assertTrue(result.failures().isEmpty());
        assertFalse(Files.exists(modsDir.resolve("alpha.zip")));
        assertFalse(Files.exists(modsDir.resolve("beta.zip")));
    }

    /// A wrapper zip holding one mod jar installs the inner jar under its own name.
    @Test
    public void installUnwrapsSingleNestedJar(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        Path modJar = writeMod(tempDir.resolve("inner.jar"), "inner", "Inner");
        Path bundle = writeBinaryArchive(tempDir.resolve("bundle.zip"),
                "Inner.jar", Files.readAllBytes(modJar));

        MindustryModManager.InstallResult result =
                new MindustryModManager(modsDir).install(bundle, "Bundle-v1.zip");

        assertEquals("Inner.jar", result.installedFile().getFileName().toString());
        assertTrue(result.replacedFiles().isEmpty());
        assertEquals(1, new MindustryModManager(modsDir).scan().size());
    }

    /// Nested zip chains are unwrapped down to the innermost archive.
    @Test
    public void installUnwrapsNestedZipChain(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        Path modJar = writeMod(tempDir.resolve("core.jar"), "core", "Core");
        Path middle = writeBinaryArchive(tempDir.resolve("middle.zip"),
                "core.jar", Files.readAllBytes(modJar));
        Path outer = writeBinaryArchive(tempDir.resolve("outer.zip"),
                "middle.zip", Files.readAllBytes(middle));

        MindustryModManager.InstallResult result =
                new MindustryModManager(modsDir).install(outer, "Outer-v2.zip");

        assertEquals("core.jar", result.installedFile().getFileName().toString());
        assertEquals(1, new MindustryModManager(modsDir).scan().size());
    }

    /// A wrapper containing several archives is rejected instead of picking one arbitrarily.
    @Test
    public void installRejectsWrapperWithSeveralArchives(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        Path first = writeMod(tempDir.resolve("a.jar"), "a", "A");
        Path second = writeMod(tempDir.resolve("b.jar"), "b", "B");
        Path bundle = writeBinaryArchive(tempDir.resolve("two.zip"),
                "a.jar", Files.readAllBytes(first),
                "b.jar", Files.readAllBytes(second));

        assertThrows(MindustryModParseException.class,
                () -> new MindustryModManager(modsDir).install(bundle, "two.zip"));
        assertTrue(new MindustryModManager(modsDir).scanAll().isEmpty());
    }

    /// Wrapper chains deeper than the limit are rejected to bound recursive extraction.
    @Test
    public void installRejectsTooDeeplyNestedArchive(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        Path modJar = writeMod(tempDir.resolve("deep.jar"), "deep", "Deep");
        Path level1 = writeBinaryArchive(tempDir.resolve("level1.zip"),
                "deep.jar", Files.readAllBytes(modJar));
        Path level2 = writeBinaryArchive(tempDir.resolve("level2.zip"),
                "level1.zip", Files.readAllBytes(level1));
        Path level3 = writeBinaryArchive(tempDir.resolve("level3.zip"),
                "level2.zip", Files.readAllBytes(level2));
        Path level4 = writeBinaryArchive(tempDir.resolve("level4.zip"),
                "level3.zip", Files.readAllBytes(level3));

        assertThrows(MindustryModParseException.class,
                () -> new MindustryModManager(modsDir).install(level4, "level4.zip"));
        assertTrue(new MindustryModManager(modsDir).scanAll().isEmpty());
    }

    /// A single nested non-archive does not qualify as a wrapper.
    @Test
    public void installRejectsSingleNestedNonArchive(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        Path bundle = writeBinaryArchive(tempDir.resolve("readme-bundle.zip"),
                "readme.txt", "hello".getBytes(StandardCharsets.UTF_8));

        assertThrows(MindustryModParseException.class,
                () -> new MindustryModManager(modsDir).install(bundle, "readme-bundle.zip"));
    }

    /// A settings write failure leaves every archive untouched.
    @Test
    public void settingsWriteFailureLeavesArchivesUntouched(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeMod(modsDir.resolve("alpha.zip"), "alpha", "Alpha");
        writeMod(modsDir.resolve("beta.zip"), "beta", "Beta");
        MindustryModManager manager = new MindustryModManager(modsDir, tempDir, (dataDir, values) -> {
            throw new IOException("settings locked");
        });

        MindustryModManager.BatchResult result = manager.setEnabled(manager.scan(), false);

        assertTrue(result.applied().isEmpty());
        assertEquals(2, result.failures().size());
        assertTrue(Files.isRegularFile(modsDir.resolve("alpha.zip")));
        assertTrue(Files.isRegularFile(modsDir.resolve("beta.zip")));
        assertFalse(Files.exists(modsDir.resolve("alpha.zip.disabled")));
        assertFalse(Files.exists(tempDir.resolve(MindustrySettingsBin.FILE_NAME)));
    }

    /// A rename failure restores the mod's previous settings flag.
    @Test
    public void renameFailureRestoresPreviousSettingsFlag(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeMod(modsDir.resolve("alpha.zip"), "alpha", "Alpha");
        MindustryModManager manager = new MindustryModManager(modsDir);
        List<MindustryLocalMod> mods = manager.scan();
        Files.delete(alphaFile(mods));

        MindustryModManager.BatchResult result = manager.setEnabled(mods, false);

        assertEquals(1, result.failures().size());
        // The failed rename must not leave the game-side flag flipped.
        assertTrue(MindustrySettingsBin.getBool(tempDir, "mod-alpha-enabled", true));
        assertFalse(Files.exists(modsDir.resolve("alpha.zip.disabled")));
    }

    /// The settings key follows the descriptor the game itself reads.
    @Test
    public void settingsKeyFollowsTheGameDescriptor(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeArchive(modsDir.resolve("dual.zip"),
                "mod.json", """
                        {
                          "name": "alpha",
                          "displayName": "Alpha"
                        }
                        """,
                "mod.hjson", """
                        name: display-only
                        displayName: Display Only
                        """);
        MindustryModManager manager = new MindustryModManager(modsDir);

        MindustryLocalMod mod = find(manager.scan(), "dual.zip");

        assertEquals("alpha", mod.getInternalName());
        manager.disable(mod);
        assertFalse(MindustrySettingsBin.getBool(tempDir, "mod-alpha-enabled", true));
        assertTrue(MindustrySettingsBin.getBool(tempDir, "mod-display-only-enabled", true));
    }

    /// A mod the game would skip (malformed game descriptor) gets no settings key.
    @Test
    public void gameSkippedModUsesSuffixOnly(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeArchive(modsDir.resolve("broken-game-descriptor.zip"),
                "mod.json", "{ name: broken",
                "mod.hjson", """
                        name: fallback
                        displayName: Fallback
                        """);
        MindustryModManager manager = new MindustryModManager(modsDir);

        MindustryLocalMod mod = find(manager.scan(), "broken-game-descriptor.zip");
        assertEquals("", mod.getInternalName());

        manager.disable(mod);

        assertTrue(Files.isRegularFile(modsDir.resolve("broken-game-descriptor.zip.disabled")));
        // No key may be written for a name the game would never load.
        assertTrue(MindustrySettingsBin.getBool(tempDir, "mod-fallback-enabled", true));
    }

    private static Path alphaFile(List<MindustryLocalMod> mods) {
        return mods.stream()
                .filter(mod -> mod.getFile().getFileName().toString().equals("alpha.zip"))
                .findFirst()
                .orElseThrow()
                .getFile();
    }

    private static Path writeBinaryArchive(Path archive, String name, byte[] content) throws IOException {
        Files.createDirectories(archive.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }
        return archive;
    }

    private static Path writeBinaryArchive(Path archive,
                                           String firstName, byte[] firstContent,
                                           String secondName, byte[] secondContent) throws IOException {
        Files.createDirectories(archive.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(firstName));
            zip.write(firstContent);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(secondName));
            zip.write(secondContent);
            zip.closeEntry();
        }
        return archive;
    }

    private static Path writeMod(Path archive, String internalName, String displayName) throws IOException {
        String descriptor = """
                {
                  "name": "%s",
                  "displayName": "%s",
                  "author": "Xenon",
                  "version": "1"
                }
                """.formatted(internalName, displayName);
        return writeArchive(archive, "mod.json", descriptor);
    }

    private static Path writeArchive(Path archive, String... entryPairs) throws IOException {
        Files.createDirectories(archive.getParent());
        if (entryPairs.length % 2 != 0) {
            throw new IllegalArgumentException("Expected name/content pairs");
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (int i = 0; i < entryPairs.length; i += 2) {
                zip.putNextEntry(new ZipEntry(entryPairs[i]));
                zip.write(entryPairs[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive;
    }

    private static MindustryLocalMod find(List<MindustryLocalMod> mods, String fileName) {
        return mods.stream()
                .filter(mod -> mod.getFile().getFileName().toString().equals(fileName))
                .findFirst()
                .orElseThrow();
    }
}
