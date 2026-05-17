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
package determination.xenon.mindustry.crash;

import java.util.Objects;

/**
 * One parsed stack-trace line from a Mindustry crash report.
 *
 * <p>Frames are categorised so the UI can highlight third-party / mod
 * frames against engine and JDK noise.</p>
 */
public final class StackFrame {

    /** Coarse origin of a stack frame. */
    public enum Source {
        /** JDK, Mindustry engine, arc, libGDX, etc. */
        JVM,
        /** Third-party / mod frame (default for unknown packages). */
        MOD,
        /** Native methods, LWJGL bridges, etc. */
        NATIVE,
        /** Could not classify (e.g. malformed line). */
        UNKNOWN
    }

    private final String className;
    private final String methodName;
    private final String fileName;
    private final int lineNumber;
    private final Source source;

    public StackFrame(String className,
                      String methodName,
                      String fileName,
                      int lineNumber,
                      Source source) {
        this.className = className;
        this.methodName = methodName;
        this.fileName = fileName;
        this.lineNumber = lineNumber;
        this.source = source == null ? Source.UNKNOWN : source;
    }

    public String getClassName() {
        return className;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getFileName() {
        return fileName;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public Source getSource() {
        return source;
    }

    /** True if the frame is most likely a mod / user-code frame. */
    public boolean highlight() {
        return source == Source.MOD;
    }

    /**
     * Decide a frame's {@link Source} from its class name and the raw
     * stack-trace line tail (which may contain {@code Native Method} or
     * LWJGL hints). Both arguments may be {@code null}.
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>{@code arc.}, {@code mindustry.}, {@code com.badlogic.gdx.}
     *       &rarr; {@link Source#JVM} (engine, grouped with JDK noise)</li>
     *   <li>{@code java.}, {@code javax.}, {@code jdk.}, {@code sun.},
     *       {@code com.sun.} &rarr; {@link Source#JVM}</li>
     *   <li>tail contains {@code Native Method} or class/tail mentions
     *       {@code LWJGL} / {@code org.lwjgl} &rarr; {@link Source#NATIVE}</li>
     *   <li>everything else &rarr; {@link Source#MOD}</li>
     *   <li>{@code null} class &rarr; {@link Source#UNKNOWN}</li>
     * </ul>
     */
    public static Source classify(String className, String tail) {
        String cls = className == null ? "" : className;
        String t = tail == null ? "" : tail;
        if (cls.isEmpty()) return Source.UNKNOWN;

        // Native classifier wins over JVM grouping: a frame in
        // org.lwjgl.* is "native" first, even though it's also a library.
        if (t.contains("Native Method")
                || cls.contains("LWJGL") || cls.startsWith("org.lwjgl.")
                || t.contains("LWJGL") || t.contains("org.lwjgl")) {
            return Source.NATIVE;
        }
        if (cls.startsWith("arc.")
                || cls.startsWith("mindustry.")
                || cls.startsWith("com.badlogic.gdx.")) {
            return Source.JVM;
        }
        if (cls.startsWith("java.")
                || cls.startsWith("javax.")
                || cls.startsWith("jdk.")
                || cls.startsWith("sun.")
                || cls.startsWith("com.sun.")) {
            return Source.JVM;
        }
        return Source.MOD;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(className == null ? "?" : className);
        sb.append('.');
        sb.append(methodName == null ? "?" : methodName);
        sb.append('(');
        if (fileName != null) {
            sb.append(fileName);
            if (lineNumber > 0) sb.append(':').append(lineNumber);
        } else if (lineNumber == -2) {
            sb.append("Native Method");
        } else {
            sb.append("Unknown Source");
        }
        sb.append(')');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StackFrame that)) return false;
        return lineNumber == that.lineNumber
                && Objects.equals(className, that.className)
                && Objects.equals(methodName, that.methodName)
                && Objects.equals(fileName, that.fileName)
                && source == that.source;
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, methodName, fileName, lineNumber, source);
    }
}
