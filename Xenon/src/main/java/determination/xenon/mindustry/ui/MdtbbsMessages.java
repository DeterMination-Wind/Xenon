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

import determination.xenon.mindustry.community.MdtbbsApiException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;

import javax.net.ssl.SSLException;

import static determination.xenon.util.i18n.I18n.i18n;

/// User-facing text for MDTBBS failures.
///
/// Stable error codes are mapped to actionable hints, HTTP 5xx is called out as
/// a server-side problem so users do not blame the launcher, and connection
/// failures get a network hint instead of a raw stack message.
@NotNullByDefault
public final class MdtbbsMessages {
    /// Maximum depth when looking for the root cause.
    private static final int MAX_CAUSE_DEPTH = 4;

    /// Formats an MDTBBS failure for dialogs, toasts and notice labels.
    ///
    /// @param error failure raised by an MDTBBS client call
    /// @return a short localized message
    public static String describe(Throwable error) {
        Throwable cause = rootCause(error);
        if (cause instanceof MdtbbsApiException api) return describeApi(api);
        if (cause instanceof UnknownHostException || cause instanceof ConnectException
                || cause instanceof SocketException || cause instanceof HttpTimeoutException
                || cause instanceof SSLException) {
            return i18n("xenon.mdtbbs.error.network", messageOf(cause));
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.toString() : message;
    }

    /// Formats an API error envelope failure.
    private static String describeApi(MdtbbsApiException api) {
        String mapped = mapErrorCode(api);
        if (mapped != null) return mapped;
        int status = api.getStatusCode();
        if (status / 100 == 5) {
            return i18n("xenon.mdtbbs.error.server", status);
        }
        return switch (status) {
            case 401 -> i18n("xenon.mdtbbs.error.auth", status);
            case 403 -> i18n("xenon.mdtbbs.error.permission", status, messageOf(api));
            case 404 -> i18n("xenon.mdtbbs.error.not_found", status);
            case 429 -> i18n("xenon.mdtbbs.error.rate_limited");
            default -> i18n("xenon.mdtbbs.error.request", status, messageOf(api));
        };
    }

    /// Maps stable `error.code` values to hints, or returns `null`.
    private static @Nullable String mapErrorCode(MdtbbsApiException api) {
        String code = api.getErrorCode();
        if (code == null || code.isBlank()) return null;
        return switch (code) {
            case "PHONE_VERIFICATION_REQUIRED" -> i18n("xenon.community.error.phone");
            case "TERMS_ACCEPTANCE_REQUIRED" -> i18n("xenon.community.error.terms");
            case "FEATURE_DISABLED" -> i18n("xenon.community.error.disabled");
            case "USER_BANNED" -> i18n("xenon.community.error.banned");
            case "RATE_LIMITED" -> i18n("xenon.mdtbbs.error.rate_limited");
            case "AUTH_REQUIRED", "INVALID_TOKEN", "TOKEN_EXPIRED", "TOKEN_INVALID",
                 "INSUFFICIENT_SCOPE" -> i18n("xenon.mdtbbs.error.auth", api.getStatusCode());
            case "CLIENT_CAPABILITY_NOT_APPROVED" -> i18n("xenon.mdtbbs.error.capability");
            case "CSRF_TOKEN_INVALID" -> i18n("xenon.mdtbbs.error.csrf");
            default -> null;
        };
    }

    /// Unwraps nested IO causes so wrapped API failures are still recognized.
    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) break;
            if (cause instanceof MdtbbsApiException
                    || cause instanceof UnknownHostException
                    || cause instanceof ConnectException
                    || cause instanceof SocketException
                    || cause instanceof HttpTimeoutException
                    || cause instanceof SSLException) {
                return cause;
            }
            current = cause;
        }
        return error;
    }

    /// Returns a readable message, never blank.
    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private MdtbbsMessages() {
    }
}
