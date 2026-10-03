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
package determination.xenon.mindustry.ui;

import determination.xenon.ui.Controllers;
import determination.xenon.ui.construct.MessageDialogPane;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static determination.xenon.util.i18n.I18n.i18n;

/// Handles `xenon://join?intent=...` deep links from MDTBBS.
///
/// The link is captured from launcher arguments before JavaFX starts and from
/// in-app hyperlinks afterwards. After the user confirms, the intent is
/// consumed through the netplay manager, which joins the MDTBBS session and
/// prepares the relay so the game can connect to the shown loopback address.
@NotNullByDefault
public final class MdtbbsJoinIntentHandler {
    /// Deep-link scheme and host handled by the launcher.
    public static final String JOIN_SCHEME = "xenon://join";
    /// Intent captured from the process arguments before the UI exists.
    private static final AtomicReference<String> PENDING = new AtomicReference<>();

    /// Captures a join link from process arguments.
    ///
    /// @param args launcher command-line arguments
    public static void captureArguments(String[] args) {
        if (args == null) return;
        for (String arg : args) {
            if (arg != null && arg.startsWith(JOIN_SCHEME)) PENDING.set(arg);
        }
    }

    /// Processes a previously captured link once the UI is ready.
    public static void processPending() {
        String uri = PENDING.getAndSet(null);
        if (uri != null) handle(uri);
    }

    /// Handles one join link.
    ///
    /// Asks the user before jumping into the session, then leaves consumption
    /// and session preparation to the netplay manager.
    ///
    /// @param uri deep link such as `xenon://join?intent=abc`
    public static void handle(String uri) {
        String intentId = parseIntentId(uri);
        if (intentId == null || intentId.isBlank()) return;
        if (!CommunityServices.community().isLoggedIn()) {
            Controllers.dialog(i18n("xenon.join.login"), i18n("xenon.join.title"),
                    MessageDialogPane.MessageType.WARNING);
            return;
        }
        Controllers.confirm(i18n("xenon.join.confirm"), i18n("xenon.join.title"),
                MessageDialogPane.MessageType.QUESTION,
                () -> {
                    Controllers.navigate(new MindustryNetplayPane());
                    CommunityServices.netplay().joinByIntent(intentId);
                },
                () -> {});
    }

    /// Extracts the `intent` query parameter from a join link.
    ///
    /// @param uri deep link
    /// @return the intent id, or `null` when absent
    static @Nullable String parseIntentId(String uri) {
        int query = uri.indexOf('?');
        if (query < 0) return null;
        for (String pair : uri.substring(query + 1).split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) continue;
            if ("intent".equals(pair.substring(0, equals))) {
                return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private MdtbbsJoinIntentHandler() {
    }
}
