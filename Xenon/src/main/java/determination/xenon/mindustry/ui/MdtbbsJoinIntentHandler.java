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

import determination.xenon.setting.Profiles;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.construct.MessageDialogPane;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static determination.xenon.util.i18n.I18n.i18n;

/// Handles `xenon://join`, `xenon://launch` and `xenon://open` deep links.
///
/// Join links are captured from launcher arguments before JavaFX starts and
/// from in-app hyperlinks afterwards. Launch links ask for confirmation and
/// start the instance; open links only navigate to its management page. After
/// confirmation, a join intent is consumed through the netplay manager, which
/// joins the MDTBBS session and prepares the relay for the game.
@NotNullByDefault
public final class MdtbbsJoinIntentHandler {
    /// Deep-link scheme and host handled by the launcher.
    public static final String JOIN_SCHEME = "xenon://join";
    /// Deep link that launches an instance after confirmation.
    public static final String LAUNCH_SCHEME = "xenon://launch";
    /// Deep link that only opens an instance page.
    public static final String OPEN_SCHEME = "xenon://open";
    /// Intent captured from the process arguments before the UI exists.
    private static final AtomicReference<String> PENDING = new AtomicReference<>();
    /// Launch link captured from the process arguments.
    private static final AtomicReference<String> PENDING_LAUNCH = new AtomicReference<>();
    /// Open link captured from the process arguments.
    private static final AtomicReference<String> PENDING_OPEN = new AtomicReference<>();

    /// Captures a deep link from process arguments.
    ///
    /// @param args launcher command-line arguments
    public static void captureArguments(String[] args) {
        if (args == null) return;
        for (String arg : args) {
            if (arg == null) continue;
            if (arg.startsWith(JOIN_SCHEME)) PENDING.set(arg);
            else if (arg.startsWith(LAUNCH_SCHEME)) PENDING_LAUNCH.set(arg);
            else if (arg.startsWith(OPEN_SCHEME)) PENDING_OPEN.set(arg);
        }
    }

    /// Processes previously captured links once the UI is ready.
    public static void processPending() {
        String uri = PENDING.getAndSet(null);
        if (uri != null) handle(uri);
        String launch = PENDING_LAUNCH.getAndSet(null);
        if (launch != null) handleLaunch(launch);
        String open = PENDING_OPEN.getAndSet(null);
        if (open != null) handleOpen(open);
    }

    /// Handles a launch link after confirmation.
    ///
    /// @param uri deep link such as `xenon://launch?instance=vanilla-160`
    public static void handleLaunch(String uri) {
        String id = parseParameter(uri, "instance");
        if (id == null || id.isBlank()) return;
        var version = MindustryRoutes.get(id).orElse(null);
        if (version == null) {
            Controllers.dialog(i18n("xenon.join.launch.missing", id),
                    i18n("xenon.join.launch.title"), MessageDialogPane.MessageType.WARNING);
            return;
        }
        Controllers.confirm(i18n("xenon.join.launch.confirm", version.getName()),
                i18n("xenon.join.launch.title"), MessageDialogPane.MessageType.QUESTION,
                () -> MindustryRoutes.launch(version), () -> {
                });
    }

    /// Handles an open link by navigating to the instance page.
    ///
    /// @param uri deep link such as `xenon://open?instance=vanilla-160`
    public static void handleOpen(String uri) {
        String id = parseParameter(uri, "instance");
        if (id == null || id.isBlank()) return;
        var version = MindustryRoutes.get(id).orElse(null);
        if (version == null) {
            Controllers.dialog(i18n("xenon.join.launch.missing", id),
                    i18n("xenon.join.launch.title"), MessageDialogPane.MessageType.WARNING);
            return;
        }
        Controllers.navigate(new MindustryVersionPage(Profiles.getSelectedProfile(), version));
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

    /// Extracts a query parameter from a deep link.
    ///
    /// @param uri   deep link
    /// @param name  parameter name
    /// @return the decoded value, or `null` when absent
    static @Nullable String parseParameter(@Nullable String uri, String name) {
        if (uri == null) return null;
        int query = uri.indexOf('?');
        if (query < 0) return null;
        for (String pair : uri.substring(query + 1).split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) continue;
            if (name.equals(pair.substring(0, equals))) {
                return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
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
