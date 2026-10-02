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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.construct.MessageDialogPane;
import javafx.application.Platform;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Handles `xenon://join?intent=...` deep links from MDTBBS.
///
/// The link is captured from launcher arguments before JavaFX starts and from
/// in-app hyperlinks afterwards, then consumed as a session Join Intent. The
/// current release only performs the signalling step; entering the game still
/// requires joining the server inside Mindustry.
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
    /// @param uri deep link such as `xenon://join?intent=abc`
    public static void handle(String uri) {
        String intentId = parseIntentId(uri);
        if (intentId == null || intentId.isBlank()) return;
        if (!CommunityServices.community().isLoggedIn()) {
            Controllers.dialog(i18n("xenon.join.login"), i18n("xenon.join.title"),
                    MessageDialogPane.MessageType.WARNING);
            return;
        }
        Schedulers.io().execute(() -> {
            try {
                JsonObject data = CommunityServices.social().consumeJoinIntent(intentId);
                String sessionId = sessionIdOf(data);
                Platform.runLater(() -> Controllers.dialog(
                        i18n("xenon.join.consumed", sessionId),
                        i18n("xenon.join.title"), MessageDialogPane.MessageType.INFO));
            } catch (IOException e) {
                LOG.warning("Failed to consume MDTBBS join intent " + intentId, e);
                String message = MdtbbsMessages.describe(e);
                Platform.runLater(() -> Controllers.dialog(
                        i18n("xenon.join.failed", message),
                        i18n("xenon.join.title"), MessageDialogPane.MessageType.ERROR));
            }
        });
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

    /// Reads the session id from a consume response.
    private static String sessionIdOf(JsonObject data) {
        JsonObject session = data.get("session") instanceof JsonObject object ? object : null;
        JsonElement direct = data.get("session_id");
        if (direct != null && !direct.isJsonNull()) return direct.getAsString();
        if (session != null) {
            JsonElement id = session.get("id");
            if (id != null && !id.isJsonNull()) return id.getAsString();
        }
        return "?";
    }

    private MdtbbsJoinIntentHandler() {
    }
}
