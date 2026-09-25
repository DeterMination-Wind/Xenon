/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
 */
package determination.xenon.mindustry.download;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public final class MdtbbsFileMirrorTest {
    private static final String MANIFEST = """
            {"games":[{"id":"mindustry","releases":[{"tag":"v160.5","source_repository":"Anuken/Mindustry","assets":[
              {"file_name":"Mindustry.jar","download_url":"/d/Mindustry/Main/Stable/v160.5/desktop/Mindustry.jar"},
              {"file_name":"Mindustry.jar","download_url":"/d/Mindustry/v8/build-160.5-stable/Mindustry.jar"},
              {"file_name":"server-release.jar","download_url":"/d/Mindustry/v8/build-160.5-stable/server-release.jar"}
            ]}]}]}
            """;

    @Test
    public void prefersFileStationCopyOfAGitHubReleaseAsset() {
        Map<String, String> index = MdtbbsFileMirror.indexFrom(MANIFEST,
                "https://file.mdtbbs.cn/api/v1/mindustry/manifest.json");
        assertEquals("https://file.mdtbbs.cn/d/Mindustry/v8/build-160.5-stable/Mindustry.jar",
                index.get("anuken/mindustry\nv160.5\nmindustry.jar"));
        assertEquals("https://file.mdtbbs.cn/d/Mindustry/v8/build-160.5-stable/server-release.jar",
                index.get("anuken/mindustry\nv160.5\nserver-release.jar"));
        assertNull(index.get("anuken/mindustry\nv1\nmissing.jar"));
    }
}
