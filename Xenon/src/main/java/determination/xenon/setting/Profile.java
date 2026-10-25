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
package determination.xenon.setting;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.property.*;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.util.ToStringBuilder;
import determination.xenon.util.javafx.ObservableHelper;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.Optional;

import static determination.xenon.ui.FXUtils.onInvalidating;

/// A game directory that hosts Mindustry instances under `versions/`.
///
/// Xenon keeps HMCL's profile model — a named directory plus a selected
/// instance id — because the sidebar, version list and install wizard all
/// navigate through it. Instances themselves live in
/// [determination.xenon.mindustry.XenonGameRepository], keyed by the profile's
/// `versions/` directory, so this class no longer owns a Minecraft repository.
@JsonAdapter(Profile.Serializer.class)
public final class Profile implements Observable {
    private final StringProperty name;
    private final ObjectProperty<Path> gameDir;
    private final StringProperty selectedVersion;
    private final BooleanProperty useRelativePath;

    public Profile(String name) {
        this(name, MindustryVersion.defaultMindustryDataDir());
    }

    public Profile(String name, Path initialGameDir) {
        this(name, initialGameDir, null, false);
    }

    public Profile(String name, Path initialGameDir, String selectedVersion, boolean useRelativePath) {
        this.name = new SimpleStringProperty(this, "name", name);
        this.gameDir = new SimpleObjectProperty<>(this, "gameDir", initialGameDir);
        this.selectedVersion = new SimpleStringProperty(this, "selectedVersion", selectedVersion);
        this.useRelativePath = new SimpleBooleanProperty(this, "useRelativePath", useRelativePath);

        addPropertyChangedListener(onInvalidating(this::invalidate));
    }

    public StringProperty selectedVersionProperty() {
        return selectedVersion;
    }

    public String getSelectedVersion() {
        return selectedVersion.get();
    }

    public void setSelectedVersion(String selectedVersion) {
        this.selectedVersion.set(selectedVersion);
    }

    public ObjectProperty<Path> gameDirProperty() {
        return gameDir;
    }

    public Path getGameDir() {
        return gameDir.get();
    }

    public void setGameDir(Path gameDir) {
        this.gameDir.set(gameDir);
    }

    public StringProperty nameProperty() {
        return name;
    }

    public String getName() {
        return name.get();
    }

    public void setName(String name) {
        this.name.set(name);
    }

    public BooleanProperty useRelativePathProperty() {
        return useRelativePath;
    }

    public boolean isUseRelativePath() {
        return useRelativePath.get();
    }

    public void setUseRelativePath(boolean useRelativePath) {
        this.useRelativePath.set(useRelativePath);
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("gameDir", getGameDir())
                .append("name", getName())
                .append("useRelativePath", isUseRelativePath())
                .toString();
    }

    private void addPropertyChangedListener(InvalidationListener listener) {
        name.addListener(listener);
        gameDir.addListener(listener);
        useRelativePath.addListener(listener);
        selectedVersion.addListener(listener);
    }

    private final ObservableHelper observableHelper = new ObservableHelper(this);

    @Override
    public void addListener(InvalidationListener listener) {
        observableHelper.addListener(listener);
    }

    @Override
    public void removeListener(InvalidationListener listener) {
        observableHelper.removeListener(listener);
    }

    private void invalidate() {
        Platform.runLater(observableHelper::invalidate);
    }

    public static final class Serializer implements JsonSerializer<Profile>, JsonDeserializer<Profile> {
        @Override
        public JsonElement serialize(Profile src, Type typeOfSrc, JsonSerializationContext context) {
            if (src == null)
                return JsonNull.INSTANCE;

            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("gameDir", src.getGameDir().toString());
            jsonObject.addProperty("useRelativePath", src.isUseRelativePath());
            jsonObject.addProperty("selectedVersion", src.getSelectedVersion());

            return jsonObject;
        }

        @Override
        public Profile deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (!(json instanceof JsonObject obj)) return null;
            String gameDir = Optional.ofNullable(obj.get("gameDir")).map(JsonElement::getAsString)
                    .orElseGet(() -> MindustryVersion.defaultMindustryDataDir().toString());

            // `selectedMinecraftVersion` is the key HMCL wrote; keep reading it
            // so an existing installation keeps its selected instance.
            String selectedVersion = Optional.ofNullable(obj.get("selectedVersion"))
                    .or(() -> Optional.ofNullable(obj.get("selectedMinecraftVersion")))
                    .map(JsonElement::getAsString)
                    .orElse(null);

            return new Profile("Default",
                    Path.of(gameDir),
                    selectedVersion,
                    Optional.ofNullable(obj.get("useRelativePath")).map(JsonElement::getAsBoolean).orElse(false));
        }
    }
}
