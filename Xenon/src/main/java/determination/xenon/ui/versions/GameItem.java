/*
 * Xenon Launcher
 * Copyright (C) 2026-2026  Xenon contributors
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
package determination.xenon.ui.versions;

import javafx.beans.property.*;
import javafx.scene.image.Image;
import determination.xenon.download.LibraryAnalyzer;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.MindustryVersionDisplay;
import determination.xenon.mindustry.ui.MindustryRoutes;
import determination.xenon.mod.ModpackConfiguration;
import determination.xenon.setting.Profile;
import determination.xenon.task.Schedulers;
import determination.xenon.util.i18n.I18n;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static determination.xenon.download.LibraryAnalyzer.LibraryType.MINECRAFT;
import static determination.xenon.util.Lang.threadPool;
import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

public class GameItem {
    private static final ThreadPoolExecutor POOL_VERSION_RESOLVE = threadPool("VersionResolve", true, 1, 10, TimeUnit.SECONDS);

    protected final Profile profile;
    protected final String id;

    private boolean initialized = false;
    private StringProperty title;
    private StringProperty tag;
    private StringProperty subtitle;
    private ObjectProperty<Image> image;

    public GameItem(Profile profile, String id) {
        this.profile = profile;
        this.id = id;
    }

    public Profile getProfile() {
        return profile;
    }

    public String getId() {
        return id;
    }

    private void init() {
        if (initialized)
            return;

        initialized = true;
        title = new SimpleStringProperty();
        tag = new SimpleStringProperty();
        subtitle = new SimpleStringProperty();
        image = new SimpleObjectProperty<>();

        // Mindustry instances bypass the HMCL repository entirely — those
        // calls would crash on a Mindustry id since it isn't an HMCL Version.
        if (MindustryRoutes.isMindustry(id)) {
            MindustryVersion v = MindustryRoutes.get(id).orElse(null);
            title.set(v != null ? v.getName() : id);
            if (v != null) {
                StringBuilder sub = new StringBuilder();
                sub.append(v.getVariant().getDisplayName());
                String buildLabel = MindustryVersionDisplay.buildLabel(
                        v.getVariant(), v.getBuild(), v.getBuildType(), v.getId(), v.getJarPath());
                if (!buildLabel.isEmpty()) sub.append("  ·  ").append(buildLabel);
                subtitle.set(sub.toString());
                tag.set("Mindustry");
            }
            image.set(determination.xenon.game.TexturesLoader.getDefaultSkinImage());
            return;
        }

        record Result(@Nullable String gameVersion, @Nullable String tag) {
        }

        CompletableFuture.supplyAsync(() -> {
            // GameVersion.minecraftVersion() is a time-costing job (up to ~200 ms)
            Optional<String> gameVersion = profile.getRepository().getGameVersion(id);
            String modPackVersion = null;
            try {
                ModpackConfiguration<?> config = profile.getRepository().readModpackConfiguration(id);
                modPackVersion = config != null ? config.getVersion() : null;
            } catch (IOException e) {
                LOG.warning("Failed to read modpack configuration from " + id, e);
            }
            return new Result(gameVersion.orElse(null), modPackVersion);
        }, POOL_VERSION_RESOLVE).whenCompleteAsync((result, exception) -> {
            if (exception == null) {
                if (result.tag != null) {
                    tag.set(result.tag);
                }

                StringBuilder libraries = new StringBuilder(Objects.requireNonNullElse(result.gameVersion, i18n("message.unknown")));
                LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(profile.getRepository().getResolvedPreservingPatchesVersion(id), result.gameVersion);
                for (LibraryAnalyzer.LibraryMark mark : analyzer) {
                    String libraryId = mark.getLibraryId();
                    String libraryVersion = mark.getLibraryVersion();
                    if (libraryId.equals(MINECRAFT.getPatchId())) continue;
                    if (I18n.hasKey("install.installer." + libraryId)) {
                        libraries.append(", ").append(i18n("install.installer." + libraryId));
                        if (libraryVersion != null)
                            libraries.append(": ").append(libraryVersion.replaceAll("(?i)" + libraryId, ""));
                    }
                }

                subtitle.set(libraries.toString());
            } else {
                LOG.warning("Failed to read version info from " + id, exception);
            }
        }, Schedulers.javafx());

        title.set(id);
        image.set(profile.getRepository().getVersionIconImage(id));
    }

    public ReadOnlyStringProperty titleProperty() {
        init();
        return title;
    }

    public ReadOnlyStringProperty tagProperty() {
        init();
        return tag;
    }

    public ReadOnlyStringProperty subtitleProperty() {
        init();
        return subtitle;
    }

    public ReadOnlyObjectProperty<Image> imageProperty() {
        init();
        return image;
    }
}
