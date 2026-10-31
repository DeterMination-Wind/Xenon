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
package determination.xenon.upgrade;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the update endpoint parsing and bootstrap footer detection.
@NotNullByDefault
public final class RemoteVersionTest {

    /// GitHub releases expose both the portable jar and the Windows executable.
    @Test
    public void githubReleaseKeepsBothAssets() throws IOException {
        JsonObject payload = JsonParser.parseString("""
                {
                  "tag_name": "v2.0.0",
                  "prerelease": false,
                  "body": "notes",
                  "assets": [
                    {"name": "Xenon-2.0.0.exe", "browser_download_url": "https://example.com/Xenon-2.0.0.exe"},
                    {"name": "Xenon-2.0.0.jar", "browser_download_url": "https://example.com/Xenon-2.0.0.jar"}
                  ]
                }
                """).getAsJsonObject();

        RemoteVersion version = RemoteVersion.parse(UpdateChannel.STABLE, false, payload);

        assertEquals("2.0.0", version.version());
        assertEquals("https://example.com/Xenon-2.0.0.jar", version.assetUrl(RemoteVersion.Type.JAR));
        assertEquals("https://example.com/Xenon-2.0.0.exe", version.assetUrl(RemoteVersion.Type.EXE));
        assertEquals("notes", version.body());
    }

    /// A release with only one artifact still parses, and the missing one stays null.
    @Test
    public void githubReleaseToleratesSingleAsset() throws IOException {
        JsonObject payload = JsonParser.parseString("""
                {
                  "tag_name": "v2.0.0",
                  "assets": [
                    {"name": "Xenon-2.0.0.exe", "browser_download_url": "https://example.com/Xenon-2.0.0.exe"}
                  ]
                }
                """).getAsJsonObject();

        RemoteVersion version = RemoteVersion.parse(UpdateChannel.STABLE, false, payload);

        assertNull(version.assetUrl(RemoteVersion.Type.JAR));
        assertEquals("https://example.com/Xenon-2.0.0.exe", version.assetUrl(RemoteVersion.Type.EXE));
    }

    /// The custom endpoint schema only offers a jar and its SHA-1.
    @Test
    public void customEndpointKeepsIntegrityCheck() throws IOException {
        JsonObject payload = JsonParser.parseString("""
                {
                  "version": "2.0.0",
                  "jar": "https://example.com/Xenon-2.0.0.jar",
                  "jarsha1": "0123456789abcdef",
                  "force": true
                }
                """).getAsJsonObject();

        RemoteVersion version = RemoteVersion.parse(UpdateChannel.STABLE, false, payload);

        assertEquals("https://example.com/Xenon-2.0.0.jar", version.assetUrl(RemoteVersion.Type.JAR));
        assertNull(version.assetUrl(RemoteVersion.Type.EXE));
        assertTrue(version.force());
        assertEquals("SHA-1", version.integrityCheck().algorithm());
    }

    /// Releases without a launcher artifact are rejected instead of yielding a null URL.
    @Test
    public void releaseWithoutArtifactsIsRejected() {
        JsonObject payload = JsonParser.parseString("""
                {
                  "tag_name": "v2.0.0",
                  "assets": [
                    {"name": "notes.txt", "browser_download_url": "https://example.com/notes.txt"}
                  ]
                }
                """).getAsJsonObject();

        assertThrows(IOException.class, () -> RemoteVersion.parse(UpdateChannel.STABLE, false, payload));
    }

    /// A build with a java executable keeps using the portable jar.
    @Test
    public void jarIsPreferredWhenJavaIsAvailable() {
        RemoteVersion version = release("https://example.com/Xenon.jar", "https://example.com/Xenon.exe");

        assertEquals(RemoteVersion.Type.JAR, UpdateHandler.selectArtifact(version, true, true));
        assertEquals(RemoteVersion.Type.JAR, UpdateHandler.selectArtifact(version, true, false));
    }

    /// The packaged Windows build cannot start a jar and must use the exe.
    @Test
    public void executableIsUsedWhenNoJavaIsAvailable() {
        RemoteVersion version = release("https://example.com/Xenon.jar", "https://example.com/Xenon.exe");

        assertEquals(RemoteVersion.Type.EXE, UpdateHandler.selectArtifact(version, false, true));
        assertNull(UpdateHandler.selectArtifact(version, false, false));
    }

    /// A release without the artifact this build prefers uses the alternative,
    /// or falls back to a manual update when neither can run.
    @Test
    public void missingArtifactUsesAvailableAlternative() {
        assertNull(UpdateHandler.selectArtifact(release("https://example.com/Xenon.jar", null), false, true));
        assertEquals(RemoteVersion.Type.EXE,
                UpdateHandler.selectArtifact(release(null, "https://example.com/Xenon.exe"), true, true));
    }

    /// Builds a release record for artifact-selection tests.
    private static RemoteVersion release(@Nullable String jarUrl,
                                         @Nullable String executableUrl) {
        return new RemoteVersion(UpdateChannel.STABLE, "2.0.0", jarUrl, executableUrl,
                null, null, false, false);
    }

    /// The bootstrap footer hash is read from the last bytes of the executable.
    @Test
    public void bootstrapFooterHashIsRead(@TempDir Path directory) throws IOException {
        byte[] hash = hashOf(0);

        Path executable = directory.resolve("Xenon.exe");
        writeBootstrap(executable, hash);

        assertEquals(HexFormat.of().formatHex(hash), UpdateHandler.readPayloadHash(executable));
    }

    /// The bundle payload stamp selects the exact executable even when several
    /// Xenon builds share the directory.
    @Test
    public void bootstrapExecutableIsMatchedByPayloadHash(@TempDir Path directory) throws IOException {
        Path bundle = Files.createDirectory(directory.resolve("Xenon.bundle"));
        Files.writeString(bundle.resolve(".payload.sha256"), HexFormat.of().formatHex(hashOf(7)));

        Path current = directory.resolve("Xenon-1.14.0.exe");
        writeBootstrap(current, hashOf(7));
        writeBootstrap(directory.resolve("Xenon-1.17.0.exe"), hashOf(3));

        assertEquals(current.toAbsolutePath().normalize(),
                UpdateHandler.findBootstrapExecutableIn(bundle));
    }

    /// A missing stamp or a foreign directory cannot identify a bootstrap.
    @Test
    public void bootstrapLookupFailsClosed(@TempDir Path directory) throws IOException {
        Path bundle = Files.createDirectory(directory.resolve("Xenon.bundle"));
        writeBootstrap(directory.resolve("Xenon.exe"), hashOf(1));

        assertNull(UpdateHandler.findBootstrapExecutableIn(bundle));
        assertNull(UpdateHandler.findBootstrapExecutableIn(directory.resolve("other")));
    }

    /// Replacing the outer executable swaps its bytes and leaves no staging file.
    @Test
    public void executableReplacementOverwritesBootstrap(@TempDir Path directory) throws IOException {
        byte[] payload = {1, 2, 3};
        Path downloaded = directory.resolve("download.exe");
        Files.write(downloaded, payload);
        Path target = directory.resolve("Xenon-1.14.0.exe");
        Files.write(target, new byte[]{9});

        assertEquals(target, UpdateHandler.replaceExecutable(downloaded, target));
        assertArrayEquals(payload, Files.readAllBytes(target));
        assertFalse(Files.exists(directory.resolve("Xenon-1.14.0.exe.new")));
    }

    /// When the outer executable is unavailable the download keeps a visible
    /// versioned name in the launcher directory.
    @Test
    public void executableFallbackUsesVersionedName(@TempDir Path directory) throws IOException {
        byte[] payload = {4, 5};
        Path downloaded = directory.resolve("download.exe");
        Files.write(downloaded, payload);
        RemoteVersion version = release("https://example.com/Xenon.jar", "https://example.com/Xenon.exe");

        Path installed = UpdateHandler.installExecutableFallback(downloaded, version, directory);

        assertEquals(directory.resolve("Xenon-2.0.0.exe"), installed);
        assertArrayEquals(payload, Files.readAllBytes(installed));
    }

    /// The packaged layout points from `app/` back to the outer bundle.
    @Test
    public void bootstrapBundleIsFoundFromAppDirectory(@TempDir Path directory) throws IOException {
        Path bundle = Files.createDirectories(directory.resolve("Xenon.bundle").resolve("Xenon").resolve("app"));

        assertEquals(directory.resolve("Xenon.bundle"),
                UpdateHandler.bootstrapBundleOf(bundle));
        assertNull(UpdateHandler.bootstrapBundleOf(directory.resolve("install").resolve("app")));
    }

    /// Regular executables and truncated files do not expose a bootstrap hash.
    @Test
    public void nonBootstrapFilesHaveNoHash(@TempDir Path directory) throws IOException {
        Path executable = directory.resolve("other.exe");
        Files.write(executable, new byte[16 + 8 + 32]);

        assertNull(UpdateHandler.readPayloadHash(executable));
        assertNull(UpdateHandler.readPayloadHash(directory.resolve("missing.exe")));
    }

    /// Builds a deterministic 32-byte hash for the given seed.
    private static byte[] hashOf(int seed) {
        byte[] hash = new byte[32];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) (seed + i);
        }
        return hash;
    }

    /// Writes an executable with a valid bootstrap footer.
    private static void writeBootstrap(Path executable, byte[] hash) throws IOException {
        ByteBuffer footer = ByteBuffer.allocate(16 + 8 + 32);
        footer.put("XENONPAYLOADV1!!".getBytes(StandardCharsets.US_ASCII));
        footer.putLong(1234L);
        footer.put(hash);
        Files.write(executable, footer.array());
    }
}
