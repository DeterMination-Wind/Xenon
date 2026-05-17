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
package determination.xenon.mindustry.uuid;

import determination.xenon.mindustry.LaunchOptions;
import determination.xenon.util.logging.Logger;

import java.util.Objects;

/**
 * Bridge between a {@link UuidProfile} the user picked in the launcher
 * UI and a {@link LaunchOptions.Builder} about to spawn Mindustry.
 *
 * <p>Mindustry vanilla has no command-line switches for "start me as
 * UUID X with name Y" — the values live inside the engine's settings
 * file and are only persisted from inside the running game. The
 * {@code uuidManager}-style mod plugs this gap by reading two JVM
 * system properties on startup and pushing them into the engine's
 * settings before the network layer initialises:</p>
 *
 * <pre>
 *   -Dmindustry.player.uuid=&lt;22-char base64&gt;
 *   -Dmindustry.player.name=&lt;nickname&gt;
 * </pre>
 *
 * <p>That contract is what this class produces. With no such mod
 * installed the JVM still accepts the {@code -D} arguments — they end
 * up as ignored system properties — so the hook is safe to apply
 * unconditionally on every launch.</p>
 */
public final class MindustryPlayerLaunchHook {

    /** JVM system property the companion mod reads to set the player UUID. */
    public static final String PROP_UUID = "mindustry.player.uuid";

    /** JVM system property the companion mod reads to set the player nickname. */
    public static final String PROP_NAME = "mindustry.player.name";

    private MindustryPlayerLaunchHook() {
    }

    /**
     * Append {@code -Dmindustry.player.uuid=...} and
     * {@code -Dmindustry.player.name=...} to {@code builder}'s JVM
     * arguments.
     *
     * <p>Both fields are passed through unchanged: the launcher trusts
     * the manager to have stored a clean UUID, and the user's choice of
     * nickname (which can contain Mindustry colour markup like
     * {@code [accent]}) is forwarded verbatim. The nickname is fed to
     * {@code addJvmArgs} as a single token, so it survives spaces and
     * special characters without quoting tricks.</p>
     *
     * @param builder the launch builder being assembled by the caller
     * @param p       the profile to inject; must have non-{@code null}
     *                {@code uuid} and {@code nickname}
     */
    public static void applyTo(LaunchOptions.Builder builder, UuidProfile p) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(p, "profile");
        Objects.requireNonNull(p.uuid, "profile.uuid");
        Objects.requireNonNull(p.nickname, "profile.nickname");

        builder.addJvmArgs(
                "-D" + PROP_UUID + "=" + p.uuid,
                "-D" + PROP_NAME + "=" + p.nickname
        );

        Logger.LOG.info("Injected Mindustry player profile " + p.uuid + " (" + p.nickname + ")");
    }
}
