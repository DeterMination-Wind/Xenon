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
package determination.xenon.mindustry.save;

import java.io.IOException;

/**
 * Business-level exception for {@code .msav} parsing failures.
 *
 * <p>A {@code MsavException} (and its subclasses) is always thrown with a
 * descriptive message that pinpoints the offending file and the specific
 * structural problem encountered, so callers do not have to dig through
 * a generic {@link IOException} stack trace to understand what went wrong.</p>
 *
 * <p>Three concrete subclasses cover the well-defined failure modes:</p>
 * <ul>
 *   <li>{@link InvalidMagic} — the leading 4 bytes are not {@code "MSAV"}.</li>
 *   <li>{@link UnsupportedVersion} — magic is correct but the version int
 *       is not on the supported list.</li>
 *   <li>{@link Truncated} — the file ended before the parser had read every
 *       structural field it needed.</li>
 * </ul>
 *
 * <p>All other failures (zlib errors, I/O errors etc.) bubble up as plain
 * {@code MsavException} instances with the underlying {@code Throwable}
 * preserved as cause.</p>
 */
public class MsavException extends IOException {

    public MsavException(String message) {
        super(message);
    }

    public MsavException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Wrong magic at file offset 0. The file is almost certainly not a Mindustry save. */
    public static final class InvalidMagic extends MsavException {
        public InvalidMagic(String message) {
            super(message);
        }

        public InvalidMagic(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Magic is correct, but the {@code version} integer is not in {@link SaveFileReader#SUPPORTED_VERSIONS}. */
    public static final class UnsupportedVersion extends MsavException {
        private final int version;

        public UnsupportedVersion(int version, String message) {
            super(message);
            this.version = version;
        }

        public int getVersion() {
            return version;
        }
    }

    /** Stream ended before the parser finished reading a required structural field. */
    public static final class Truncated extends MsavException {
        public Truncated(String message) {
            super(message);
        }

        public Truncated(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
