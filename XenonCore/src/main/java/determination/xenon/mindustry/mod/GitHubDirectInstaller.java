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
package determination.xenon.mindustry.mod;

import determination.xenon.mindustry.download.GitHubAsset;
import determination.xenon.mindustry.download.GitHubRelease;
import determination.xenon.mindustry.download.GitHubReleaseClient;
import determination.xenon.mindustry.download.ProgressCallback;
import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One-shot installer that resolves a GitHub repository's latest release,
 * picks the most likely Mindustry mod artifact from its asset list, and
 * hands the file off to a {@link MindustryModManager} for placement in
 * the user's {@code mods/} folder.
 *
 * <p>Asset selection is intentionally simple: the first {@code .zip} or
 * {@code .jar} that is <em>not</em> a source bundle (heuristic
 * {@code -source} / {@code -sources}) wins. {@code .zip} is preferred
 * when both are present because Mindustry mods historically ship as
 * zips while plugins ship as jars; if a repo offers both they're
 * usually interchangeable.</p>
 *
 * <p>The installer is stateless beyond the references it holds, so a
 * single instance can be reused across installs and shared between
 * threads.</p>
 */
public final class GitHubDirectInstaller {

    private final GitHubReleaseClient client;
    private final MindustryModManager target;

    public GitHubDirectInstaller(GitHubReleaseClient client, MindustryModManager target) {
        this.client = Objects.requireNonNull(client, "client");
        this.target = Objects.requireNonNull(target, "target");
    }

    /**
     * Resolve {@code ownerRepo}'s latest release, download the matching
     * archive into the system temporary directory, and install it into
     * the configured {@link MindustryModManager}.
     *
     * @param ownerRepo {@code owner/repo}, e.g. {@code Anuken/ExampleJavaMod}
     * @param cb        optional progress callback forwarded to the
     *                  underlying download; may be {@code null}
     * @throws IOException if the release lookup, asset selection, download,
     *     or install step fails. The exception message identifies which
     *     step went wrong so the UI can surface it.
     */
    public void installLatest(String ownerRepo, ProgressCallback cb) throws IOException {
        if (ownerRepo == null || ownerRepo.isBlank()) {
            throw new IOException("Empty owner/repo");
        }
        if (!ownerRepo.contains("/")) {
            throw new IOException("Expected owner/repo, got: " + ownerRepo);
        }

        GitHubRelease release;
        try {
            release = client.getLatestRelease(ownerRepo);
        } catch (IOException e) {
            throw new IOException("Failed to query latest release for " + ownerRepo + ": " + e.getMessage(), e);
        }
        if (release == null) {
            throw new IOException("No published releases for " + ownerRepo);
        }

        GitHubAsset asset = pickAsset(release.getAssets());
        if (asset == null) {
            throw new IOException("No installable .zip/.jar asset in "
                    + ownerRepo + " release " + release.getTagName());
        }

        Path tmp = stagingFile(ownerRepo, release.getTagName(), asset.getName());
        Path parent = tmp.getParent();
        if (parent != null) Files.createDirectories(parent);

        Logger.LOG.info("Downloading " + asset.getName() + " from " + ownerRepo
                + " (tag " + release.getTagName() + ") to " + tmp);
        try {
            client.downloadAsset(asset, tmp, cb);
        } catch (IOException e) {
            safeDelete(tmp);
            throw new IOException("Failed to download " + asset.getName()
                    + " from " + ownerRepo + ": " + e.getMessage(), e);
        }

        try {
            target.install(tmp);
        } catch (IOException e) {
            throw new IOException("Failed to install " + asset.getName()
                    + " into " + target.getModsDir() + ": " + e.getMessage(), e);
        } finally {
            safeDelete(tmp);
        }
        Logger.LOG.info("Installed " + asset.getName() + " from " + ownerRepo
                + " into " + target.getModsDir());
    }

    /**
     * Choose the asset most likely to be a runnable Mindustry mod from
     * {@code assets}. Returns {@code null} if no candidate qualifies.
     *
     * <p>Visible for callers that want to inspect the selection without
     * triggering a download (e.g. confirmation UI).</p>
     */
    public static GitHubAsset pickAsset(List<GitHubAsset> assets) {
        if (assets == null || assets.isEmpty()) return null;
        GitHubAsset zip = null;
        GitHubAsset jar = null;
        for (GitHubAsset a : assets) {
            if (a == null || a.getName() == null) continue;
            String name = a.getName().toLowerCase(Locale.ROOT);
            if (name.contains("-source") || name.contains("-sources")) continue;
            if (zip == null && name.endsWith(".zip")) {
                zip = a;
            } else if (jar == null && name.endsWith(".jar")) {
                jar = a;
            }
        }
        // Prefer .zip — Mindustry mods are zips by convention; jars are
        // usually plugins or library duplicates.
        if (zip != null) return zip;
        return jar;
    }

    private static Path stagingFile(String ownerRepo, String tag, String assetName) {
        String tmpDir = System.getProperty("java.io.tmpdir", ".");
        String[] parts = ownerRepo.split("/", 2);
        String owner = sanitize(parts[0]);
        String repo = parts.length > 1 ? sanitize(parts[1]) : "repo";
        String safeTag = sanitize(tag == null ? "latest" : tag);
        String ext = extensionOf(assetName);
        String name = "xenon-mod-" + owner + "-" + repo + "-" + safeTag + ext;
        return Paths.get(tmpDir, name);
    }

    private static String extensionOf(String assetName) {
        if (assetName == null) return ".zip";
        String lower = assetName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar")) return ".jar";
        if (lower.endsWith(".zip")) return ".zip";
        return ".zip";
    }

    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) return "x";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.length() == 0 ? "x" : out.toString();
    }

    private static void safeDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            Logger.LOG.warning("Failed to delete staging file " + file + ": " + e.getMessage());
        }
    }
}
