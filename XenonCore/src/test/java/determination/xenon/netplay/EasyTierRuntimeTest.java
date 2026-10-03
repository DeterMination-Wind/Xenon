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
import determination.xenon.util.platform.Architecture;
import determination.xenon.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests EasyTier peer parsing, asset selection and room-code helpers.
@NotNullByDefault
public final class EasyTierRuntimeTest {

    /// The `easytier-cli peer` table parses including the local row.
    @Test
    public void parsesPeerTable() {
        String output = """
                | ipv4         | hostname | cost  | lat_ms | loss_rate | rx_bytes | tx_bytes | tunnel_proto | nat_type | id        | version |
                | ------------ | -------- | ----- | ------ | --------- | -------- | -------- | ------------ | -------- | --------- | ------- |
                | 10.126.126.1 | abc-1    | Local | *      | *         | *        | *        | udp          | FullCone | 439804259 | 2.6.2   |
                | 10.126.126.2 | abc-2    | p2p   | 3.452  | 0         | 17.33 kB | 20.42 kB | udp          | FullCone | 390879727 | 2.6.2   |
                |              | Public_a | p2p   | 27.796 | 0         | 50.01 kB | 67.46 kB | tcp          | Unknown  | 377164245 | 2.6.2   |
                """;
        List<EasyTierRuntime.Peer> peers = EasyTierRuntime.parsePeers(output);
        assertEquals(3, peers.size());
        assertEquals("10.126.126.1", peers.get(0).ipv4());
        assertTrue(peers.get(0).local());
        assertEquals("abc-2", peers.get(1).hostname());
        assertFalse(peers.get(1).local());
        assertTrue(peers.get(2).ipv4().isBlank());
    }

    /// Empty or unrelated CLI output yields no peers.
    @Test
    public void emptyOutputHasNoPeers() {
        assertTrue(EasyTierRuntime.parsePeers(null).isEmpty());
        assertTrue(EasyTierRuntime.parsePeers("no table here").isEmpty());
    }

    /// Asset selection maps OS/architecture onto the published zip names.
    @Test
    public void assetSelectionMatchesPlatforms() {
        List<GitHubAsset> assets = List.of(
                new GitHubAsset("easytier-windows-x86_64-v9.zip", 1, "u", "zip"),
                new GitHubAsset("easytier-linux-x86_64-v9.zip", 1, "u", "zip"),
                new GitHubAsset("easytier-linux-aarch64-v9.zip", 1, "u", "zip"),
                new GitHubAsset("easytier-macos-aarch64-v9.zip", 1, "u", "zip"));
        assertEquals("easytier-windows-x86_64-v9.zip",
                EasyTierRuntime.pickAssetName(OperatingSystem.WINDOWS, Architecture.X86_64, assets));
        assertEquals("easytier-linux-x86_64-v9.zip",
                EasyTierRuntime.pickAssetName(OperatingSystem.LINUX, Architecture.X86_64, assets));
        assertEquals("easytier-linux-aarch64-v9.zip",
                EasyTierRuntime.pickAssetName(OperatingSystem.LINUX, Architecture.ARM64, assets));
        assertEquals("easytier-macos-aarch64-v9.zip",
                EasyTierRuntime.pickAssetName(OperatingSystem.MACOS, Architecture.ARM64, assets));
        assertNull(EasyTierRuntime.pickAssetName(OperatingSystem.WINDOWS, Architecture.ARM64, assets));
    }

    /// Room codes must be exactly six digits.
    @Test
    public void roomCodeValidation() {
        assertTrue(EasyTierRuntime.isValidRoomCode("000000"));
        assertTrue(EasyTierRuntime.isValidRoomCode("483920"));
        assertFalse(EasyTierRuntime.isValidRoomCode("12345"));
        assertFalse(EasyTierRuntime.isValidRoomCode("1234567"));
        assertFalse(EasyTierRuntime.isValidRoomCode("abcdef"));
        assertFalse(EasyTierRuntime.isValidRoomCode(null));
    }

    /// The derived secret is stable for one code and differs across codes.
    @Test
    public void derivedSecretIsDeterministic() {
        String first = EasyTierRuntime.deriveSecret("483920");
        assertEquals(first, EasyTierRuntime.deriveSecret("483920"));
        assertFalse(first.equals(EasyTierRuntime.deriveSecret("483921")));
        assertNotNull(first);
        assertEquals(32, first.length());
    }

    /// Executables nested inside the release folder are still found.
    @Test
    public void binaryLookupSearchesNestedArchiveLayout(@TempDir Path tempDir) throws IOException {
        Path nested = tempDir.resolve("v2.6.4/easytier-windows-x86_64");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("easytier-core.exe"), "binary");
        Files.writeString(nested.resolve("easytier-cli.exe"), "binary");

        assertNotNull(EasyTierRuntime.binaryIn(tempDir, "easytier-core.exe"));
        assertNotNull(EasyTierRuntime.binaryIn(tempDir, "easytier-cli.exe"));
        assertNull(EasyTierRuntime.binaryIn(tempDir, "missing.exe"));
        assertNull(EasyTierRuntime.binaryIn(tempDir.resolve("absent"), "easytier-core.exe"));
    }
}
