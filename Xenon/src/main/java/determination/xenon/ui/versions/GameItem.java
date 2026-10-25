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
import determination.xenon.game.TexturesLoader;
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.MindustryVersionDisplay;
import determination.xenon.mindustry.playtime.MindustryPlaytimeStore;
import determination.xenon.mindustry.playtime.PlaytimeFormat;
import determination.xenon.mindustry.playtime.PlaytimeSummary;
import determination.xenon.mindustry.ui.MindustryRoutes;
import determination.xenon.setting.Profile;
import determination.xenon.task.Schedulers;
import determination.xenon.util.i18n.I18n;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static determination.xenon.util.Lang.threadPool;
import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// One Mindustry instance as rendered by the version list, the version
/// switcher and the sidebar.
///
/// Properties are resolved lazily on first access. Playtime is read on a
/// background thread so binding a list cell never blocks the JavaFX thread.
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
        image.set(TexturesLoader.getDefaultSkinImage());

        MindustryVersion version = MindustryRoutes.get(profile, id).orElse(null);
        if (version == null) {
            // The entry can outlive its manifest (manual delete, external
            // rename), so never assume the instance still exists.
            title.set(id);
            subtitle.set(i18n("version.not_exist"));
            return;
        }

        title.set(version.getName());
        StringBuilder sub = new StringBuilder(version.getVariant().getDisplayName());
        String buildLabel = MindustryVersionDisplay.buildLabel(
                version.getVariant(), version.getBuild(), version.getBuildType(), version.getId(), version.getJarPath());
        if (!buildLabel.isEmpty()) {
            sub.append("  ·  ").append(buildLabel);
        }
        subtitle.set(sub.toString());
        tag.set("Mindustry");
        appendPlaytimeAsync(version, sub.toString());
    }

    /// Appends the recorded playtime line to `base` once the log has been read
    /// off the JavaFX thread.
    ///
    /// The log grows with every recorded session, so reading it synchronously
    /// while a list cell binds the subtitle would stall the UI thread.
    ///
    /// @param version the Mindustry instance whose log is read
    /// @param base subtitle text the playtime line is appended to
    private void appendPlaytimeAsync(MindustryVersion version, String base) {
        CompletableFuture.supplyAsync(() -> {
            try {
                Path versionRoot = MindustryImportFlow.repositoryForVersion(version).getVersionRoot(version);
                return new MindustryPlaytimeStore(versionRoot).readSummary();
            } catch (RuntimeException ex) {
                LOG.warning("Failed to read Mindustry playtime for " + id, ex);
                return PlaytimeSummary.EMPTY;
            }
        }, POOL_VERSION_RESOLVE).whenCompleteAsync((summary, ex) -> {
            if (ex == null && summary.lastLaunchEpochMillis() > 0) {
                subtitle.set(base + "  ·  " + i18n("xenon.mindustry.playtime.line",
                        I18n.formatDateTime(Instant.ofEpochMilli(summary.lastLaunchEpochMillis())),
                        PlaytimeFormat.duration(summary.totalActiveMillis())));
            }
        }, Schedulers.javafx());
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
