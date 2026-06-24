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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /// Neon-style no-comma Hjson should fall through to the valid JSON descriptor that follows it.
    @Test
    public void fallsBackFromNoCommaHjsonToJsonDescriptor(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeArchive(modsDir.resolve("neon.zip"),
                "mod.hjson", """
                        name: neon
                        displayName: Neon / 氖
                        author: DeterMination-Wind
                        version: 1
                        description: Utility mod
                        """,
                "mod.json", """
                        {
                          "name": "neon",
                          "displayName": "Neon / 氖",
                          "author": "DeterMination-Wind",
                          "version": "1",
                          "description": "Utility mod"
                        }
                        """);

        List<MindustryLocalMod> mods = new MindustryModManager(modsDir).scan();

        assertEquals("Neon / 氖", find(mods, "neon.zip").getDisplayName());
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
