/*
 * Xenon Launcher
 * Copyright (C) 2021-2026  Xenon contributors
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
package determination.xenon.setting;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import determination.xenon.mindustry.download.MindustryDownloadSource;
import determination.xenon.util.gson.ObservableSetting;
import org.jetbrains.annotations.Nullable;

import java.util.*;

@JsonAdapter(GlobalConfig.Adapter.class)
public final class GlobalConfig extends ObservableSetting {

    @Nullable
    public static GlobalConfig fromJson(String json) throws JsonParseException {
        return Config.CONFIG_GSON.fromJson(json, GlobalConfig.class);
    }

    public GlobalConfig() {
        register();
    }

    public String toJson() {
        return Config.CONFIG_GSON.toJson(this);
    }

    @SerializedName("agreementVersion")
    private final IntegerProperty agreementVersion = new SimpleIntegerProperty();

    public IntegerProperty agreementVersionProperty() {
        return agreementVersion;
    }

    public int getAgreementVersion() {
        return agreementVersion.get();
    }

    public void setAgreementVersion(int agreementVersion) {
        this.agreementVersion.set(agreementVersion);
    }

    @SerializedName("terracottaAgreementVersion")
    private final IntegerProperty terracottaAgreementVersion = new SimpleIntegerProperty();

    public IntegerProperty terracottaAgreementVersionProperty() {
        return terracottaAgreementVersion;
    }

    public int getTerracottaAgreementVersion() {
        return terracottaAgreementVersion.get();
    }

    public void setTerracottaAgreementVersion(int terracottaAgreementVersion) {
        this.terracottaAgreementVersion.set(terracottaAgreementVersion);
    }

    @SerializedName("platformPromptVersion")
    private final IntegerProperty platformPromptVersion = new SimpleIntegerProperty();

    public IntegerProperty platformPromptVersionProperty() {
        return platformPromptVersion;
    }

    public int getPlatformPromptVersion() {
        return platformPromptVersion.get();
    }

    public void setPlatformPromptVersion(int platformPromptVersion) {
        this.platformPromptVersion.set(platformPromptVersion);
    }

    @SerializedName("logRetention")
    private final IntegerProperty logRetention = new SimpleIntegerProperty(20);

    public IntegerProperty logRetentionProperty() {
        return logRetention;
    }

    public int getLogRetention() {
        return logRetention.get();
    }

    public void setLogRetention(int logRetention) {
        this.logRetention.set(logRetention);
    }

    @SerializedName("enableOfflineAccount")
    private final BooleanProperty enableOfflineAccount = new SimpleBooleanProperty(false);

    public BooleanProperty enableOfflineAccountProperty() {
        return enableOfflineAccount;
    }

    public boolean isEnableOfflineAccount() {
        return enableOfflineAccount.get();
    }

    public void setEnableOfflineAccount(boolean value) {
        enableOfflineAccount.set(value);
    }

    @SerializedName("analyticsEnabled")
    private final BooleanProperty analyticsEnabled = new SimpleBooleanProperty(true);

    public BooleanProperty analyticsEnabledProperty() {
        return analyticsEnabled;
    }

    public boolean isAnalyticsEnabled() {
        return analyticsEnabled.get();
    }

    public void setAnalyticsEnabled(boolean value) {
        analyticsEnabled.set(value);
    }

    @SerializedName("analyticsInstallId")
    private final StringProperty analyticsInstallId = new SimpleStringProperty();

    public StringProperty analyticsInstallIdProperty() {
        return analyticsInstallId;
    }

    public String getAnalyticsInstallId() {
        return analyticsInstallId.get();
    }

    public void setAnalyticsInstallId(String value) {
        analyticsInstallId.set(value);
    }

    @SerializedName("analyticsFirstLaunchSent")
    private final BooleanProperty analyticsFirstLaunchSent = new SimpleBooleanProperty(false);

    public BooleanProperty analyticsFirstLaunchSentProperty() {
        return analyticsFirstLaunchSent;
    }

    public boolean isAnalyticsFirstLaunchSent() {
        return analyticsFirstLaunchSent.get();
    }

    public void setAnalyticsFirstLaunchSent(boolean value) {
        analyticsFirstLaunchSent.set(value);
    }

    @SerializedName("duplicateInstanceCopiesSettings")
    private final BooleanProperty duplicateInstanceCopiesSettings = new SimpleBooleanProperty(true);

    /// Whether a duplicated Mindustry instance keeps the source `settings.bin`.
    ///
    /// Configurations written before this option existed default to `true`,
    /// matching the previous behaviour of a full instance copy.
    public BooleanProperty duplicateInstanceCopiesSettingsProperty() {
        return duplicateInstanceCopiesSettings;
    }

    /// Returns whether duplicated instances keep `settings.bin`.
    public boolean isDuplicateInstanceCopiesSettings() {
        return duplicateInstanceCopiesSettings.get();
    }

    /// Sets whether duplicated instances keep `settings.bin`.
    public void setDuplicateInstanceCopiesSettings(boolean value) {
        duplicateInstanceCopiesSettings.set(value);
    }

    @SerializedName("fontAntiAliasing")
    private final StringProperty fontAntiAliasing = new SimpleStringProperty();

    public StringProperty fontAntiAliasingProperty() {
        return fontAntiAliasing;
    }

    public String getFontAntiAliasing() {
        return fontAntiAliasing.get();
    }

    public void setFontAntiAliasing(String value) {
        this.fontAntiAliasing.set(value);
    }

    @SerializedName("userJava")
    private final ObservableSet<String> userJava = FXCollections.observableSet(new LinkedHashSet<>());

    public ObservableSet<String> getUserJava() {
        return userJava;
    }

    @SerializedName("mindustryDownloadSpeedKbps")
    private final IntegerProperty mindustryDownloadSpeedKbps = new SimpleIntegerProperty(0);

    /// Global speed limit applied to the whole Mindustry download chain.
    ///
    /// The value is in KiB/s; `0` means unlimited, which is the default and
    /// takes a lock-free fast path inside `DownloadThrottle`. Mainland
    /// configurations written before this option existed keep the unlimited
    /// behaviour because the field is absent from their JSON.
    public IntegerProperty mindustryDownloadSpeedKbpsProperty() {
        return mindustryDownloadSpeedKbps;
    }

    /// Returns the configured Mindustry download speed limit.
    ///
    /// @return KiB/s, or `0` for unlimited
    public int getMindustryDownloadSpeedKbps() {
        return Math.max(0, mindustryDownloadSpeedKbps.get());
    }

    /// Sets the Mindustry download speed limit.
    ///
    /// @param kbps KiB/s, or `0` for unlimited; negative values become unlimited
    public void setMindustryDownloadSpeedKbps(int kbps) {
        mindustryDownloadSpeedKbps.set(Math.max(0, kbps));
    }

    @SerializedName("mindustryDownloadSource")
    private final ObjectProperty<MindustryDownloadSource> mindustryDownloadSource =
            new SimpleObjectProperty<>(MindustryDownloadSource.defaultForLocale());

    /// Source tier used to list and download Mindustry builds.
    ///
    /// The initialiser is locale aware, so a configuration that never stored
    /// this field keeps the historical behaviour: mainland users prefer the
    /// domestic source, everyone else prefers the official origin.
    public ObjectProperty<MindustryDownloadSource> mindustryDownloadSourceProperty() {
        return mindustryDownloadSource;
    }

    /// Returns the configured Mindustry download-source tier.
    ///
    /// @return the tier, never `null`
    public MindustryDownloadSource getMindustryDownloadSource() {
        MindustryDownloadSource source = mindustryDownloadSource.get();
        return source == null ? MindustryDownloadSource.defaultForLocale() : source;
    }

    /// Sets the Mindustry download-source tier.
    ///
    /// @param source tier to use, or `null` to restore the locale default
    public void setMindustryDownloadSource(@Nullable MindustryDownloadSource source) {
        mindustryDownloadSource.set(source == null
                ? MindustryDownloadSource.defaultForLocale() : source);
    }

    @SerializedName("disabledJava")
    private final ObservableSet<String> disabledJava = FXCollections.observableSet(new LinkedHashSet<>());

    public ObservableSet<String> getDisabledJava() {
        return disabledJava;
    }

    static final class Adapter extends ObservableSetting.Adapter<GlobalConfig> {
        @Override
        protected GlobalConfig createInstance() {
            return new GlobalConfig();
        }
    }
}
