/*
 * Xenon Launcher
 * Copyright (C) 2021-2026  Xenon contributors
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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import determination.xenon.task.FileDownloadTask;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.util.gson.JsonUtils;
import determination.xenon.util.io.FileUtils;
import determination.xenon.util.platform.OperatingSystem;
import determination.xenon.util.platform.Platform;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static determination.xenon.util.logging.Logger.LOG;

/// On-disk JDK pool under `<root>/<platform>/`.
///
/// Managed runtimes are named `temurin-<major>` and recorded by a sibling
/// `<name>.json` manifest. Downloads come from the Adoptium API, which serves
/// Eclipse Temurin builds for every platform Xenon supports.
public final class HMCLJavaRepository implements JavaRepository {
    public static final String TEMURIN_PREFIX = "temurin-";

    private final Path root;

    public HMCLJavaRepository(Path root) {
        this.root = root;
    }

    public Path getPlatformRoot(Platform platform) {
        return root.resolve(platform.toString());
    }

    @Override
    public Path getJavaDir(Platform platform, String name) {
        return getPlatformRoot(platform).resolve(name);
    }

    public Path getJavaDir(Platform platform, int majorVersion) {
        return getJavaDir(platform, TEMURIN_PREFIX + majorVersion);
    }

    @Override
    public Path getManifestFile(Platform platform, String name) {
        return getPlatformRoot(platform).resolve(name + ".json");
    }

    public Path getManifestFile(Platform platform, int majorVersion) {
        return getManifestFile(platform, TEMURIN_PREFIX + majorVersion);
    }

    public boolean isInstalled(Platform platform, String name) {
        return Files.exists(getManifestFile(platform, name));
    }

    public boolean isInstalled(Platform platform, int majorVersion) {
        return isInstalled(platform, TEMURIN_PREFIX + majorVersion);
    }

    public @Nullable Path getJavaExecutable(Platform platform, String name) {
        Path javaDir = getJavaDir(platform, name);
        try {
            return JavaManager.getExecutable(javaDir).toRealPath();
        } catch (IOException ignored) {
            if (platform.getOperatingSystem() == OperatingSystem.MACOS) {
                try {
                    return JavaManager.getMacExecutable(javaDir).toRealPath();
                } catch (IOException ignored1) {
                }
            }
        }

        return null;
    }

    public @Nullable Path getJavaExecutable(Platform platform, int majorVersion) {
        return getJavaExecutable(platform, TEMURIN_PREFIX + majorVersion);
    }

    private static void getAllJava(List<Path> list, Platform platform, Path platformRoot) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(platformRoot)) {
            for (Path file : stream) {
                try {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".json") && Files.isRegularFile(file)) {
                        Path javaDir = file.resolveSibling(name.substring(0, name.length() - ".json".length()));
                        Path executable;
                        try {
                            executable = JavaManager.getExecutable(javaDir).toRealPath();
                        } catch (IOException e) {
                            if (platform.getOperatingSystem() == OperatingSystem.MACOS)
                                executable = JavaManager.getMacExecutable(javaDir).toRealPath();
                            else
                                throw e;
                        }

                        if (Files.isDirectory(javaDir)) {
                            list.add(executable);
                        }
                    }
                } catch (Throwable e) {
                    LOG.warning("Failed to parse " + file, e);
                }
            }

        } catch (IOException ignored) {
        }
    }

    @Override
    public Collection<Path> getAllJava(Platform platform) {
        Path platformRoot = getPlatformRoot(platform);
        if (!Files.isDirectory(platformRoot))
            return Collections.emptyList();

        ArrayList<Path> list = new ArrayList<>();

        getAllJava(list, platform, platformRoot);
        return list;
    }

    @Override
    public Task<JavaRuntime> getDownloadJavaTask(Platform platform, int majorVersion) {
        Path javaDir = getJavaDir(platform, majorVersion);
        Path tempDir = getPlatformRoot(platform).resolve(".tmp").resolve(javaDir.getFileName());

        return Task.supplyAsync(Schedulers.io(), () -> queryAdoptium(platform, majorVersion))
                .thenComposeAsync(asset -> {
                    Path archive = tempDir.resolve(asset.name());
                    FileDownloadTask download = new FileDownloadTask(asset.link(), archive,
                            new FileDownloadTask.IntegrityCheck("sha256", asset.checksum()));

                    Map<String, Object> update = new LinkedHashMap<>();
                    update.put("provider", "adoptium");
                    update.put("version", majorVersion);

                    return download.thenComposeAsync(() -> new JavaInstallTask(javaDir, update, archive));
                })
                .thenApplyAsync(result -> {
                    Path executable;
                    try {
                        executable = JavaManager.getExecutable(javaDir).toRealPath();
                    } catch (IOException e) {
                        if (platform.getOperatingSystem() == OperatingSystem.MACOS)
                            executable = JavaManager.getMacExecutable(javaDir).toRealPath();
                        else
                            throw e;
                    }

                    JavaInfo info = result.getInfo().getPlatform().equals(platform) && JavaManager.isCompatible(platform)
                            ? JavaInfoUtils.fromExecutable(executable)
                            : result.getInfo();

                    JavaManifest manifest = new JavaManifest(info, result.getUpdate(), result.getFiles());
                    JsonUtils.writeToJsonFile(getManifestFile(platform, majorVersion), manifest);
                    return JavaRuntime.of(executable, info, true);
                });
    }

    public Task<JavaRuntime> getInstallJavaTask(Platform platform, String name, Map<String, Object> update, Path archiveFile) {
        Path javaDir = getJavaDir(platform, name);
        return new JavaInstallTask(javaDir, update, archiveFile).thenApplyAsync(result -> {
            if (!result.getInfo().getPlatform().equals(platform))
                throw new IOException("Platform is mismatch: expected " + platform + " but got " + result.getInfo().getPlatform());

            Path executable = javaDir.resolve("bin").resolve(platform.getOperatingSystem().getJavaExecutable()).toRealPath();
            JsonUtils.writeToJsonFile(getManifestFile(platform, name), result);
            return JavaRuntime.of(executable, result.getInfo(), true);
        });
    }

    @Override
    public Task<Void> getUninstallJavaTask(Platform platform, String name) {
        return Task.runAsync(() -> {
            Files.deleteIfExists(getManifestFile(platform, name));
            FileUtils.deleteDirectory(getJavaDir(platform, name));
        });
    }

    @Override
    public Task<Void> getUninstallJavaTask(JavaRuntime java) {
        assert java.isManaged();

        Path binary = java.getBinary();
        Path platformRoot = getPlatformRoot(java.getPlatform());
        Path javaDir = binary;
        for (int i = 0; i < 3 && javaDir != null; i++) {
            javaDir = javaDir.getParent();
        }
        if (javaDir == null || !javaDir.startsWith(platformRoot) || javaDir.equals(platformRoot))
            return Task.completed(null);

        return getUninstallJavaTask(java.getPlatform(), javaDir.getFileName().toString());
    }

    private record AdoptiumAsset(String link, String checksum, String name) {
    }

    private static AdoptiumAsset queryAdoptium(Platform platform, int majorVersion) throws IOException, InterruptedException {
        String os = switch (platform.getOperatingSystem()) {
            case WINDOWS -> "windows";
            case LINUX -> "linux";
            case MACOS -> "mac";
            default -> throw new IOException("Adoptium has no build for " + platform);
        };

        String arch = switch (platform.getArchitecture()) {
            case X86 -> "x86";
            case X86_64 -> "x64";
            case ARM64 -> "aarch64";
            case ARM32 -> "arm";
            case RISCV64 -> "riscv64";
            default -> throw new IOException("Adoptium has no build for " + platform);
        };

        URI uri = URI.create(String.format(
                "https://api.adoptium.net/v3/assets/latest/%d/hotspot?architecture=%s&image_type=jdk&os=%s&vendor=eclipse&page=0&page_size=1",
                majorVersion, arch, os));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Adoptium API returned HTTP " + response.statusCode());
        }

        JsonElement body = JsonParser.parseString(response.body());
        if (!body.isJsonArray() || body.getAsJsonArray().isEmpty()) {
            throw new IOException("No Adoptium build for Java " + majorVersion + " on " + platform);
        }

        JsonObject binary = body.getAsJsonArray().get(0).getAsJsonObject()
                .getAsJsonObject("binary");
        JsonObject pkg = binary.getAsJsonObject("package");

        return new AdoptiumAsset(
                pkg.get("link").getAsString(),
                pkg.get("checksum").getAsString(),
                pkg.get("name").getAsString());
    }
}
