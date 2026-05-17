/*
 * Xenon Launcher
 * Copyright (C) 2025-2026  Xenon contributors
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
package determination.xenon.java;

import com.google.gson.annotations.SerializedName;
import determination.xenon.util.gson.JsonSerializable;
import determination.xenon.util.gson.JsonUtils;
import determination.xenon.util.io.JarUtils;
import determination.xenon.util.platform.Architecture;
import determination.xenon.util.platform.OperatingSystem;
import determination.xenon.util.platform.Platform;
import determination.xenon.util.platform.SystemUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;

/**
 * @author Glavo
 * @see <a href="https://github.com/Glavo/java-info">Glavo/java-info</a>
 */
public final class JavaInfoUtils {

    private JavaInfoUtils() {
    }

    public static @NotNull JavaInfo fromExecutable(Path executable) throws IOException {
        assert executable.isAbsolute();

        Path thisPath = JarUtils.thisJarPath();
        if (thisPath == null) {
            throw new IOException("Failed to find current HMCL location");
        }

        try {
            Result result = JsonUtils.GSON.fromJson(SystemUtils.run(
                    executable.toString(),
                    "-classpath",
                    thisPath.toString(),
                    org.glavo.info.Main.class.getName()
            ), Result.class);

            if (result == null) {
                throw new IOException("Failed to get Java info from " + executable);
            }

            if (result.javaVersion == null) {
                throw new IOException("Failed to get Java version from " + executable);
            }

            Architecture architecture = Architecture.parseArchName(result.osArch);
            Platform platform = Platform.getPlatform(OperatingSystem.CURRENT_OS,
                    architecture != Architecture.UNKNOWN
                            ? architecture
                            : Architecture.SYSTEM_ARCH);

            return new JavaInfo(platform, result.javaVersion, result.javaVendor);
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException(e);
        }
    }

    @JsonSerializable
    private record Result(@SerializedName("os.name") String osName, @SerializedName("os.arch") String osArch,
                          @SerializedName("java.version") String javaVersion,
                          @SerializedName("java.vendor") String javaVendor) {
    }
}
