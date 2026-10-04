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

import determination.xenon.mindustry.download.GitHubAsset;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests release asset candidate selection for the mod installer.
@NotNullByDefault
public final class GitHubDirectInstallerTest {

    /// Jars are tried before zips so a release bundle zip never shadows the installable mod jar.
    @Test
    public void pickAssetsOrdersJarsBeforeZips() {
        List<GitHubAsset> assets = List.of(
                asset("Mod-v2.0.0.zip"),
                asset("Mod-v2.0.0.jar"),
                asset("ModSources.zip"),
                asset("Mod-v2.0.0-sources.jar"),
                asset("README.md"));

        List<String> picked = GitHubDirectInstaller.pickAssets(assets).stream()
                .map(GitHubAsset::getName)
                .toList();

        assertEquals(List.of("Mod-v2.0.0.jar", "Mod-v2.0.0.zip"), picked);
    }

    /// Releases that only publish a loadable zip still produce one candidate.
    @Test
    public void pickAssetsKeepsZipOnlyReleases() {
        List<GitHubAsset> assets = List.of(asset("Mod.zip"), asset("notes.txt"));

        List<String> picked = GitHubDirectInstaller.pickAssets(assets).stream()
                .map(GitHubAsset::getName)
                .toList();

        assertEquals(List.of("Mod.zip"), picked);
    }

    /// Null or archive-less asset lists yield no candidates instead of failing.
    @Test
    public void pickAssetsToleratesEmptyLists() {
        assertTrue(GitHubDirectInstaller.pickAssets(null).isEmpty());
        assertTrue(GitHubDirectInstaller.pickAssets(List.of()).isEmpty());
        assertTrue(GitHubDirectInstaller.pickAssets(List.of(asset("notes.txt"))).isEmpty());
    }

    /// Source-suffix filtering must not drop unrelated names such as ResourcePack.zip.
    @Test
    public void pickAssetsKeepsUnrelatedNames() {
        List<String> picked = GitHubDirectInstaller
                .pickAssets(List.of(asset("ResourcePack.zip"), asset("ModSources.zip")))
                .stream()
                .map(GitHubAsset::getName)
                .toList();

        assertEquals(List.of("ResourcePack.zip"), picked);
    }

    /// The single-asset helper used by other callers keeps preferring zips.
    @Test
    public void pickAssetKeepsZipPreference() {
        List<GitHubAsset> assets = List.of(asset("Mod.jar"), asset("Mod.zip"));

        assertEquals("Mod.zip", GitHubDirectInstaller.pickAsset(assets).getName());
    }

    private static GitHubAsset asset(String name) {
        return new GitHubAsset(name, 1L,
                "https://github.com/owner/repo/releases/download/v1/" + name,
                "application/octet-stream");
    }
}
