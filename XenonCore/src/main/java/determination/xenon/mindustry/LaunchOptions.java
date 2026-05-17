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
package determination.xenon.mindustry;

import determination.xenon.util.platform.OperatingSystem;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inputs to {@link XenonLauncher}: everything Xenon needs to know in
 * order to spawn a Mindustry client process.
 *
 * <p>Deliberately small — there are no Minecraft libraries, asset
 * indexes or auth payloads here, because Mindustry has none.</p>
 */
public final class LaunchOptions {
    private static final Pattern TOKEN = Pattern.compile("\\S+");

    private final Path javaExecutable;
    private final Path jar;
    private final Path workingDirectory;
    private final Path dataDir;
    private final List<String> jvmArgs;
    private final List<String> gameArgs;
    private final long maxHeapMb;
    private final long minHeapMb;

    private LaunchOptions(Builder b) {
        this.javaExecutable = Objects.requireNonNull(b.javaExecutable, "javaExecutable");
        this.jar = Objects.requireNonNull(b.jar, "jar");
        this.workingDirectory = b.workingDirectory != null ? b.workingDirectory : jar.getParent();
        this.dataDir = Objects.requireNonNull(b.dataDir, "dataDir");
        this.jvmArgs = Collections.unmodifiableList(new ArrayList<>(b.jvmArgs));
        this.gameArgs = Collections.unmodifiableList(new ArrayList<>(b.gameArgs));
        this.maxHeapMb = b.maxHeapMb;
        this.minHeapMb = b.minHeapMb;
    }

    public Path getJavaExecutable() { return javaExecutable; }
    public Path getJar() { return jar; }
    public Path getWorkingDirectory() { return workingDirectory; }
    public Path getDataDir() { return dataDir; }
    public List<String> getJvmArgs() { return jvmArgs; }
    public List<String> getGameArgs() { return gameArgs; }
    public long getMaxHeapMb() { return maxHeapMb; }
    public long getMinHeapMb() { return minHeapMb; }

    /** Build the final command line that {@link XenonLauncher} will spawn. */
    public List<String> buildCommandLine() {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable.toString());

        if (minHeapMb > 0) cmd.add("-Xms" + minHeapMb + "m");
        if (maxHeapMb > 0) cmd.add("-Xmx" + maxHeapMb + "m");

        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dmindustry.data.dir=" + dataDir.toAbsolutePath());

        // macOS demands the AWT/JavaFX/LWJGL main thread be the first thread.
        if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS
                && jvmArgs.stream().noneMatch(a -> a.startsWith("-XstartOnFirstThread"))) {
            cmd.add("-XstartOnFirstThread");
        }

        cmd.addAll(jvmArgs);
        cmd.add("-jar");
        cmd.add(jar.toAbsolutePath().toString());
        cmd.addAll(gameArgs);
        return cmd;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Tokenise a free-form "JVM args" string from settings into a list. */
    public static List<String> tokenize(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        Matcher m = TOKEN.matcher(raw);
        List<String> out = new ArrayList<>();
        while (m.find()) out.add(m.group());
        return out;
    }

    public static final class Builder {
        private Path javaExecutable;
        private Path jar;
        private Path workingDirectory;
        private Path dataDir;
        private final List<String> jvmArgs = new ArrayList<>();
        private final List<String> gameArgs = new ArrayList<>();
        private long maxHeapMb = 1024;
        private long minHeapMb = 0;

        public Builder javaExecutable(Path p) { this.javaExecutable = p; return this; }
        public Builder jar(Path p) { this.jar = p; return this; }
        public Builder workingDirectory(Path p) { this.workingDirectory = p; return this; }
        public Builder dataDir(Path p) { this.dataDir = p; return this; }
        public Builder jvmArgs(List<String> args) { this.jvmArgs.clear(); this.jvmArgs.addAll(args); return this; }
        public Builder addJvmArgs(String... args) { this.jvmArgs.addAll(Arrays.asList(args)); return this; }
        public Builder gameArgs(List<String> args) { this.gameArgs.clear(); this.gameArgs.addAll(args); return this; }
        public Builder addGameArgs(String... args) { this.gameArgs.addAll(Arrays.asList(args)); return this; }
        public Builder maxHeapMb(long m) { this.maxHeapMb = m; return this; }
        public Builder minHeapMb(long m) { this.minHeapMb = m; return this; }

        public LaunchOptions build() { return new LaunchOptions(this); }
    }
}
