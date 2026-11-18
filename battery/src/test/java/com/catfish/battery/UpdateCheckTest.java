package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Update card: GitHub releases JSON in, versions compared, release notes as plain changelog lines. */
public class UpdateCheckTest {
    static final String RELEASES = "["
            + "{\"tag_name\":\"v1.6\",\"name\":\"Catfish Battery 1.6\",\"published_at\":\"2026-11-20T09:00:00Z\","
            + "\"html_url\":\"https://github.com/Antony-12s/CatfishBattery/releases/tag/v1.6\",\"draft\":false,\"prerelease\":false,"
            + "\"body\":\"## Catfish Battery 1.6\\r\\n\\r\\n- **Settings**: Region card\\r\\n  - nested `detail`\\r\\n\\r\\nVerified: 200/200\"},"
            + "{\"tag_name\":\"v1.6-rc1\",\"name\":\"rc\",\"published_at\":\"2026-11-19T09:00:00Z\",\"html_url\":\"https://x\",\"prerelease\":true,\"body\":\"x\"},"
            + "{\"tag_name\":\"v1.5\",\"name\":\"Catfish Battery 1.5\",\"published_at\":\"2026-11-18T11:50:00Z\","
            + "\"html_url\":\"https://github.com/Antony-12s/CatfishBattery/releases/tag/v1.5\",\"body\":\"- Native pages\"},"
            + "{\"tag_name\":\"oops\",\"published_at\":\"2026-11-01T00:00:00Z\",\"html_url\":\"javascript:alert(1)\",\"body\":\"bad\"}"
            + "]";

    @Test
    public void parseKeepsPublishedReleasesNewestFirst() {
        List<UpdateCheck.Release> list = UpdateCheck.parse(RELEASES);
        assertEquals(2, list.size()); // prerelease and the release without a version or https link are dropped
        assertEquals("1.6", list.get(0).version);
        assertEquals("20 Nov 2026", list.get(0).date);
        assertEquals("https://github.com/Antony-12s/CatfishBattery/releases/tag/v1.6", list.get(0).url);
        assertEquals("1.5", list.get(1).version);
        assertTrue(UpdateCheck.parse("{}").isEmpty());
        assertTrue(UpdateCheck.parse("not json").isEmpty());
    }

    @Test
    public void notesBecomePlainBulletLines() {
        UpdateCheck.Release r = UpdateCheck.parse(RELEASES).get(0);
        assertEquals(List.of("• Settings: Region card", "   – nested detail", "Verified: 200/200"), r.notes);
    }

    @Test
    public void versionsCompareByNumberNotText() {
        assertTrue(UpdateCheck.newer("1.10", "1.9"));
        assertTrue(UpdateCheck.newer("1.6", "1.5"));
        assertTrue(UpdateCheck.newer("2", "1.9.9"));
        assertTrue(!UpdateCheck.newer("1.5", "1.5"));
        assertTrue(!UpdateCheck.newer("1.5", "1.5.0"));
        assertTrue(!UpdateCheck.newer("1.4", "1.5"));
    }

    @Test
    public void summaryForTheCard() {
        List<UpdateCheck.Release> list = UpdateCheck.parse(RELEASES);
        assertEquals("1.6 available · you have 1.5", UpdateCheck.summary(list, "1.5"));
        assertEquals("Up to date · 1.6", UpdateCheck.summary(list, "1.6"));
        assertEquals("Not checked yet · you have 1.5", UpdateCheck.summary(null, "1.5"));
        assertNull(UpdateCheck.latest(List.of()));
    }

    @Test
    public void realGithubResponseParses() throws Exception {
        String json = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/test/resources/github/releases.json")),
                java.nio.charset.StandardCharsets.UTF_8); // saved from the GitHub API on 2026-11-18
        List<UpdateCheck.Release> list = UpdateCheck.parse(json);
        assertEquals(List.of("1.5", "1.4", "1.3", "1.2"), list.stream().map(r -> r.version).collect(java.util.stream.Collectors.toList()));
        assertEquals("18 Nov 2026", list.get(0).date);
        assertTrue(list.get(0).notes.get(0), list.get(0).notes.get(0).startsWith("Every page is now native"));
        assertTrue(list.get(0).notes.contains("• Status"));
        assertTrue(list.get(0).notes.stream().noneMatch(l -> l.endsWith("…"))); // no 1.5 line is cut
        assertTrue(list.get(0).notes.contains("      – top: the HV PACK card with SOC, SOH, a V × A = kW line and an SOC sparkline;"));
        assertEquals("Up to date · 1.5", UpdateCheck.summary(list, "1.5"));
    }
}
