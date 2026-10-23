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
package determination.xenon.mindustry.community;

import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.GameInfo;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.ModInfo;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.SaveInfo;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.UploadMetadata;
import determination.xenon.mindustry.mod.MindustryLocalMod;
import determination.xenon.mindustry.mod.MindustryModManager;
import determination.xenon.mindustry.save.SaveFileReader;
import determination.xenon.mindustry.save.SaveSummary;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/// Builds the optional metadata attached to a cloud-save upload.
///
/// Save headers are read best-effort: a save that cannot be parsed still
/// uploads, only without map/wave/playtime. Enabled Mods contribute their
/// stable id, display name, version and archive SHA-256, capped at the 100
/// entries the server accepts.
@NotNullByDefault
public final class MindustryCloudSaveMetadata {

    /// Maximum Mods accepted by the upload endpoint.
    public static final int MAX_MODS = 100;

    /// Collects metadata for one save file.
    ///
    /// @param saveFile local `.msav` file
    /// @param version  instance whose game version is reported
    /// @param dataDir  instance data directory holding `mods/`
    /// @param deviceId opaque device id, or empty
    /// @return metadata ready for an upload
    public static UploadMetadata collect(Path saveFile, MindustryVersion version, Path dataDir,
                                         String deviceId) {
        return collect(saveFile, version.getId() == null ? "" : version.getId(),
                version.getBuild(), dataDir, deviceId);
    }

    /// Collects metadata with explicit game version values.
    ///
    /// @param saveFile   local `.msav` file
    /// @param gameVersion game version such as `v157`, or empty
    /// @param build      game build number, or 0
    /// @param dataDir    instance data directory holding `mods/`
    /// @param deviceId   opaque device id, or empty
    /// @return metadata ready for an upload
    public static UploadMetadata collect(Path saveFile, String gameVersion, int build,
                                         Path dataDir, String deviceId) {
        SaveInfo save = readSaveInfo(saveFile);
        List<ModInfo> mods = readMods(dataDir);
        return new UploadMetadata(new GameInfo(gameVersion, build), save, mods, deviceId);
    }

    /// Reads map, wave and playtime from the save header.
    ///
    /// @param saveFile local `.msav` file
    /// @return the display metadata, or `null` when the header cannot be read
    static @Nullable SaveInfo readSaveInfo(Path saveFile) {
        try {
            SaveSummary summary = SaveFileReader.readHeaderLenient(saveFile);
            return new SaveInfo(summary.mapName(), summary.wave(), summary.playtime());
        } catch (IOException | RuntimeException e) {
            Logger.LOG.info("Cloud save metadata unavailable for "
                    + saveFile.getFileName() + ": " + e.getMessage());
            return null;
        }
    }

    /// Reads the enabled Mods of one instance.
    ///
    /// @param dataDir instance data directory
    /// @return at most {#MAX_MODS} entries, in scan order
    static List<ModInfo> readMods(Path dataDir) {
        Path modsDir = dataDir.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            return List.of();
        }
        List<ModInfo> result = new ArrayList<>();
        try {
            MindustryModManager manager = new MindustryModManager(modsDir, dataDir);
            for (MindustryLocalMod mod : manager.scanAll()) {
                if (!mod.isEnabled() || mod.getInternalName() == null
                        || mod.getInternalName().isBlank()) {
                    continue;
                }
                String hash = mod.getFile() == null ? "" : sha256(mod.getFile());
                result.add(new ModInfo(mod.getInternalName(), mod.getDisplayName(),
                        mod.getVersion(), hash));
                if (result.size() >= MAX_MODS) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            Logger.LOG.info("Cloud save Mod metadata unavailable: " + e.getMessage());
        }
        return List.copyOf(result);
    }

    /// Computes the lowercase hex SHA-256 of a file.
    ///
    /// @param file file to hash
    /// @return the digest, or empty when the file cannot be read
    static String sha256(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            Logger.LOG.info("Cannot hash Mod archive " + file.getFileName() + ": " + e.getMessage());
            return "";
        }
    }

    private MindustryCloudSaveMetadata() {
    }
}
