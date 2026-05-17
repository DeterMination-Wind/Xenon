/*
 * Xenon Launcher
 * Copyright (C) 2020-2026  Xenon contributors
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

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.*;
import javafx.beans.value.ObservableBooleanValue;
import determination.xenon.Metadata;
import determination.xenon.util.io.NetworkUtils;
import determination.xenon.util.versioning.VersionNumber;

import java.io.IOException;
import java.util.LinkedHashMap;

import static determination.xenon.setting.ConfigHolder.config;
import static determination.xenon.util.Lang.*;
import static determination.xenon.util.logging.Logger.LOG;

public final class UpdateChecker {
    private UpdateChecker() {
    }

    private static final ObjectProperty<RemoteVersion> latestVersion = new SimpleObjectProperty<>();
    private static final BooleanBinding outdated = Bindings.createBooleanBinding(
            () -> {
                RemoteVersion latest = latestVersion.get();
                if (latest == null || isDevelopmentVersion(Metadata.VERSION)) {
                    return false;
                } else if (latest.force()
                        || Metadata.isNightly()
                        || latest.channel() == UpdateChannel.NIGHTLY
                        || latest.channel() != UpdateChannel.getChannel()) {
                    return !latest.version().equals(Metadata.VERSION);
                } else {
                    return VersionNumber.compare(Metadata.VERSION, latest.version()) < 0;
                }
            },
            latestVersion);
    private static final ReadOnlyBooleanWrapper checkingUpdate = new ReadOnlyBooleanWrapper(false);

    public static void init() {
        requestCheckUpdate(UpdateChannel.getChannel(), config().isAcceptPreviewUpdate());
    }

    public static RemoteVersion getLatestVersion() {
        return latestVersion.get();
    }

    public static ReadOnlyObjectProperty<RemoteVersion> latestVersionProperty() {
        return latestVersion;
    }

    public static boolean isOutdated() {
        return outdated.get();
    }

    public static ObservableBooleanValue outdatedProperty() {
        return outdated;
    }

    public static boolean isCheckingUpdate() {
        return checkingUpdate.get();
    }

    public static ReadOnlyBooleanProperty checkingUpdateProperty() {
        return checkingUpdate.getReadOnlyProperty();
    }

    private static RemoteVersion checkUpdate(UpdateChannel channel, boolean preview) throws IOException {
        if (!IntegrityChecker.DISABLE_SELF_INTEGRITY_CHECK && !IntegrityChecker.isSelfVerified()) {
            throw new IOException("Self verification failed");
        }

        var query = new LinkedHashMap<String, String>();
        query.put("version", Metadata.VERSION);
        query.put("channel", preview ? channel.channelName + "-preview" : channel.channelName);

        String url = NetworkUtils.withQuery(Metadata.XENON_UPDATE_URL, query);
        return RemoteVersion.fetch(channel, preview, url);
    }

    private static boolean isDevelopmentVersion(String version) {
        return version.contains("@") || // eg. @develop@
                version.contains("SNAPSHOT"); // eg. 3.5.SNAPSHOT
    }

    public static void requestCheckUpdate(UpdateChannel channel, boolean preview) {
        Platform.runLater(() -> {
            if (isCheckingUpdate())
                return;
            checkingUpdate.set(true);

            thread(() -> {
                RemoteVersion result = null;
                try {
                    result = checkUpdate(channel, preview);
                    LOG.info("Latest version (" + channel + ", preview=" + preview + ") is " + result);
                } catch (Throwable e) {
                    LOG.warning("Failed to check for update", e);
                }

                RemoteVersion finalResult = result;
                Platform.runLater(() -> {
                    checkingUpdate.set(false);
                    if (finalResult != null) {
                        latestVersion.set(finalResult);
                    }
                });
            }, "Update Checker", true);
        });
    }
}
