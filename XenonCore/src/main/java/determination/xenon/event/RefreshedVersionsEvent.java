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
package determination.xenon.event;

/**
 * This event gets fired when all the versions in .minecraft folder are loaded.
 * <br>
 * This event is fired on the {@link determination.xenon.event.EventBus#EVENT_BUS}
 *
 * @author huangyuhui
 */
public final class RefreshedVersionsEvent extends Event {

    /**
     * Constructor.
     *
     * @param source {@link determination.xenon.game.GameRepository}
     */
    public RefreshedVersionsEvent(Object source) {
        super(source);
    }

}
