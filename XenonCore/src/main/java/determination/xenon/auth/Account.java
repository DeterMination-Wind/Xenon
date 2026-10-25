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
package determination.xenon.auth;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import determination.xenon.util.ToStringBuilder;
import determination.xenon.util.javafx.ObservableHelper;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/// A local player identity.
///
/// Xenon only keeps offline identities: the username becomes the Mindustry
/// nickname and the UUID is encoded into the `-Dmindustry.player.uuid` value at
/// launch. Microsoft, Mojang and authlib-injector accounts belonged to the
/// removed Minecraft launcher and are not part of this model.
public abstract class Account implements Observable {

    /// @return the display name, used as the Mindustry nickname
    public abstract String getUsername();

    /// @return the character name shown in the account list
    public String getCharacter() {
        return getUsername();
    }

    /// @return the identity UUID
    public abstract UUID getUUID();

    /// @return the stable identifier stored in `config.json`
    public abstract String getIdentifier();

    /// @return the serialised form written to `accounts.json` / `config.json`
    public abstract Map<Object, Object> toStorage();

    private final BooleanProperty portable = new SimpleBooleanProperty(false);

    public BooleanProperty portableProperty() {
        return portable;
    }

    public boolean isPortable() {
        return portable.get();
    }

    public void setPortable(boolean value) {
        this.portable.set(value);
    }

    private final ObservableHelper helper = new ObservableHelper(this);

    @Override
    public void addListener(InvalidationListener listener) {
        helper.addListener(listener);
    }

    @Override
    public void removeListener(InvalidationListener listener) {
        helper.removeListener(listener);
    }

    /// Called when the account has changed. Safe to call from any thread.
    protected void invalidate() {
        Platform.runLater(helper::invalidate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(portable);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof Account))
            return false;

        Account another = (Account) obj;
        return isPortable() == another.isPortable();
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("username", getUsername())
                .append("uuid", getUUID())
                .append("portable", isPortable())
                .toString();
    }
}
