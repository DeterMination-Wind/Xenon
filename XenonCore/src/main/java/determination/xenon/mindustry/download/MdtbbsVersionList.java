/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package determination.xenon.mindustry.download;

import determination.xenon.mindustry.VersionVariant;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Version source backed by the public MDTbbs Mindustry/v8 directory. */
@NotNullByDefault
public final class MdtbbsVersionList extends MindustryVersionList {
    private static final String CATEGORY_URL = "https://file.mdtbbs.cn/category/Mindustry/v8";
    private static final String FILE_BASE = "https://d.file.mdtbbs.cn/d";
    private static final Pattern BUILD = Pattern.compile(
            "build-([0-9]+(?:\\.[0-9]+)*)-(stable|prerelease)");
    private static final Pattern SIZE = Pattern.compile(
            "([0-9]+(?:\\.[0-9]+)?)\\s*(B|KB|MB|GB)", Pattern.CASE_INSENSITIVE);

    private final String categoryUrl;
    private final String fileBase;
    private final String clientVersion;
    private final HttpClient http;

    public MdtbbsVersionList(GitHubReleaseClient fallbackClient) {
        this(fallbackClient, "@develop@", CATEGORY_URL, FILE_BASE, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(8))
                .proxy(ProxySelector.getDefault())
                .build());
    }

    /** Creates the MDTbbs source with the launcher version used for attribution. */
    public MdtbbsVersionList(GitHubReleaseClient fallbackClient, String clientVersion) {
        this(fallbackClient, clientVersion, CATEGORY_URL, FILE_BASE, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(8))
                .proxy(ProxySelector.getDefault())
                .build());
    }

    MdtbbsVersionList(GitHubReleaseClient fallbackClient, String categoryUrl,
                      String fileBase, HttpClient http) {
        this(fallbackClient, "@develop@", categoryUrl, fileBase, http);
    }

    MdtbbsVersionList(GitHubReleaseClient fallbackClient, String clientVersion,
                      String categoryUrl, String fileBase, HttpClient http) {
        super(VersionVariant.VANILLA, fallbackClient);
        this.categoryUrl = categoryUrl;
        this.fileBase = fileBase;
        this.clientVersion = clientVersion == null || clientVersion.isBlank()
                ? "@develop@" : clientVersion;
        this.http = http;
    }

    @Override
    public List<MindustryRemoteVersion> refresh() throws IOException {
        try {
            Document index = fetch(categoryUrl);
            List<MindustryRemoteVersion> result = new ArrayList<>();
            for (Element link : index.select("a[href]")) {
                String href = link.attr("href");
                Matcher matcher = BUILD.matcher(href);
                if (!matcher.find()) continue;

                String buildPage = href.startsWith("http")
                        ? href : originOf(categoryUrl) + href;
                MindustryRemoteVersion version = parseBuildPage(
                        matcher.group(1), matcher.group(2), buildPage);
                if (version != null) result.add(version);
            }
            result.sort(Comparator.comparing(MdtbbsVersionList::numericVersion)
                    .thenComparing(MindustryRemoteVersion::getTagName)
                    .reversed());
            if (!result.isEmpty()) return result;
            throw new IOException("MDTbbs returned no Mindustry builds");
        } catch (IOException | RuntimeException e) {
            Logger.LOG.warning("MDTbbs version feed failed (" + e.getMessage()
                    + "); falling back to GitHub");
            return new VanillaVersionList(client).refresh();
        }
    }

    private MindustryRemoteVersion parseBuildPage(String version, String channel,
                                                   String pageUrl) throws IOException {
        Document page = fetch(pageUrl);
        Map<String, MindustryRemoteVersion.Artifact> artifacts = new LinkedHashMap<>();
        for (Element link : page.select("a.term-file[href]")) {
            String href = link.attr("href");
            String name = decodeName(href);
            String platform = platformFor(name);
            if (platform == null) continue;

            String directUrl = fileBase + (href.startsWith("/") ? href : "/" + href)
                    + "?reques=" + URLEncoder.encode("Xenon " + clientVersion,
                    StandardCharsets.UTF_8);
            long size = parseSize(link.select(".size").text());
            artifacts.put(platform, new MindustryRemoteVersion.Artifact(
                    platform, directUrl, size, name, true));
        }
        if (artifacts.isEmpty()) return null;

        int build = parseBuildNumber(version);
        String tag = "v" + version;
        MindustryRemoteVersion.Artifact preferred = artifacts.get("windows");
        if (preferred == null) preferred = artifacts.values().iterator().next();
        return new MindustryRemoteVersion(build,
                "stable".equals(channel) ? "stable" : "be",
                VersionVariant.VANILLA,
                preferred.getDownloadUrl(), null, preferred.getSize(), tag,
                "MDTbbs desktop archive", artifacts);
    }

    private Document fetch(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Xenon-Launcher")
                .build();
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }
            return Jsoup.parse(response.body(), url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fetching " + url, e);
        }
    }

    private static String decodeName(String href) {
        int slash = href.lastIndexOf('/');
        String raw = slash >= 0 ? href.substring(slash + 1) : href;
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static String originOf(String url) {
        URI uri = URI.create(url);
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    private static String platformFor(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.equals("mindustry-windows-64-bit.zip")) return "windows";
        if (lower.equals("mindustry-linux-64-bit.zip")) return "linux";
        if (lower.equals("mindustry-macos.zip")) return "macos";
        return null;
    }

    private static long parseSize(String text) {
        Matcher matcher = SIZE.matcher(text == null ? "" : text);
        if (!matcher.find()) return 0;
        BigDecimal value = new BigDecimal(matcher.group(1));
        long multiplier = switch (matcher.group(2).toUpperCase(Locale.ROOT)) {
            case "GB" -> 1024L * 1024 * 1024;
            case "MB" -> 1024L * 1024;
            case "KB" -> 1024L;
            default -> 1L;
        };
        return value.multiply(BigDecimal.valueOf(multiplier)).longValue();
    }

    private static int parseBuildNumber(String version) {
        int dot = version.indexOf('.');
        String major = dot < 0 ? version : version.substring(0, dot);
        try {
            return Integer.parseInt(major);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static BigDecimal numericVersion(MindustryRemoteVersion version) {
        String tag = version.getTagName().replaceFirst("^[vV]", "");
        try {
            return new BigDecimal(tag);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
