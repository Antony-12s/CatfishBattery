package com.catfish.battery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

public class BatteryWebPagesTest {

    @Test
    public void embeddedPagesFillTheirWindowWithoutTheLegacyZoomAndPackCap() {
        for (String name : new String[]{"status", "charging", "history", "settings"}) {
            String page = BatteryWebPages.embedded(name, true);
            assertFalse(name + " retains the fixed pack width", page.contains("max-width:1290px"));
            assertFalse(name + " retains body zoom", page.contains("style='zoom:1.4'"));
            assertTrue(name + " needs a window-height layout", page.contains("html,body{height:100%"));
        }
    }

    @Test
    public void embeddedStatusRetainsTheOriginalThreeDimensionalPackAndFlows() {
        String page = BatteryWebPages.embedded("status", true);
        assertTrue(page.contains("viewBox='0 0 1180 470'"));
        assertTrue(page.contains("function boxFaces("));
        assertTrue(page.contains("const FP={hv:[[52,110]"));
        assertTrue(page.contains("localStorage.getItem('cfPackView')"));
        assertTrue(page.contains("packTabSchematic"));
        assertTrue(page.contains("packTabCells"));
        assertTrue("pack must fit by width AND height", page.contains("function fitPackBox()"));
    }

    @Test
    public void everyElementTheStatusScriptTouchesIsOnThePage() {
        // One missing element throws in drawBattery and freezes everything drawn after it.
        String page = BatteryWebPages.status();
        Matcher ref = Pattern.compile("(?:\\$|num|txt|attr)\\('([A-Za-z0-9_]+)'").matcher(page);
        Set<String> missing = new TreeSet<>();
        while (ref.find()) {
            if (!page.contains("id='" + ref.group(1) + "'")) missing.add(ref.group(1));
        }
        assertTrue("script touches elements the page lacks: " + missing, missing.isEmpty());
    }

    @Test
    public void everyElementTheChargingScriptTouchesIsOnThePage() {
        // histText skips a missing element without a word, so a renamed id would just leave a tile blank.
        String page = BatteryWebPages.charging(), script = page.substring(page.indexOf("let historyRange="));
        Matcher ref = Pattern.compile("(?:getElementById|histText|histCostText)\\('([A-Za-z0-9_]+)'").matcher(script);
        Set<String> missing = new TreeSet<>();
        int seen = 0;
        while (ref.find()) {
            seen++;
            if (!page.contains("id='" + ref.group(1) + "'")) missing.add(ref.group(1));
        }
        assertTrue("found only " + seen + " references", seen > 10);
        assertTrue("script touches elements the page lacks: " + missing, missing.isEmpty());
    }

    @Test
    public void everyElementTheHistoryScriptTouchesIsOnThePage() {
        String page = BatteryWebPages.history(), script = page.substring(page.indexOf("let sessData=null"));
        Matcher ref = Pattern.compile("S\\$\\('([A-Za-z0-9_]+)'\\)").matcher(script);
        Set<String> missing = new TreeSet<>();
        int seen = 0;
        while (ref.find()) {
            seen++;
            if (!page.contains("id='" + ref.group(1) + "'")) missing.add(ref.group(1));
        }
        assertTrue("found only " + seen + " references", seen >= 6);
        assertTrue("script touches elements the page lacks: " + missing, missing.isEmpty());
    }

    @Test
    public void everyElementTheSettingsScriptTouchesIsOnThePage() {
        String page = BatteryWebPages.settings(), script = page.substring(page.indexOf("const Q$="));
        Matcher ref = Pattern.compile("Q\\$\\('([A-Za-z0-9_]+)'\\)|\\['(set[A-Za-z]+)'").matcher(script);
        Set<String> missing = new TreeSet<>();
        int seen = 0;
        while (ref.find()) {
            String id = ref.group(1) != null ? ref.group(1) : ref.group(2);
            seen++;
            if (!page.contains("id='" + id + "'")) missing.add(id);
        }
        assertTrue("found only " + seen + " references", seen >= 10);
        assertTrue("script touches elements the page lacks: " + missing, missing.isEmpty());
    }

    @Test
    public void noPageKeepsItsOwnPriceBoxAnyMore() {
        // prices live in Settings only; a leftover box would price differently from every other tab
        for (String page : new String[]{BatteryWebPages.status(), BatteryWebPages.charging(), BatteryWebPages.history()}) {
            assertFalse(page.contains("id='kwhPrice'"));
            assertFalse(page.contains("kwhPrice"));
        }
    }

    @Test
    public void cellsViewListsTheSchematicsFourModules() {
        // the pack cards and the schematic's module table must carve cells 1-118 the same way, and every sensor tile needs a name
        String page = BatteryWebPages.status();
        assertTrue(page.contains("[[62,0,1,30],[216,0,90,29],[62,94,31,30],[216,94,61,29]]"));
        assertTrue(page.contains("const PACKS=[['P1',1,30],['P4',90,29],['P2',31,30],['P3',61,29],];"));
        TreeSet<Integer> cells = new TreeSet<>();
        for (String[] m : BatteryWebPages.MODULES) {
            assertTrue(page.contains("id='packCells" + m[0] + "'") && page.contains("id='packHead" + m[0] + "'"));
            int first = Integer.parseInt(m[1]), count = Integer.parseInt(m[2]);
            for (int c = first; c < first + count; c++) assertTrue("cell " + c + " listed twice", cells.add(c));
        }
        assertTrue(cells.size() == 118 && cells.first() == 1 && cells.last() == 118);
        Matcher names = Pattern.compile("SENSOR_NAME=\\{([^}]*)\\}").matcher(page);
        assertTrue(names.find());
        assertTrue(names.group(1).split(",").length == 12);
        assertFalse(page.contains("Cell #"));
    }

    @Test
    public void historyRowsAndSettingsPanesKeepTheirControls() {
        // History: one row component with a charger chip, grey when idle; Settings: a single Save bar and the two default
        // selects the script still reads, now hidden behind the badges on the charger rows
        String history = BatteryWebPages.history(), settings = BatteryWebPages.settings();
        assertTrue(history.contains("class=\"hrow") && history.contains("class=\"chip") && history.contains("closest('.hrow')"));
        assertTrue(history.contains("class='liveBadge idle'") && !history.contains("sessGrid"));
        assertTrue(settings.indexOf("onclick='saveSettings()'") == settings.lastIndexOf("onclick='saveSettings()'"));
        assertTrue(settings.contains("id='setAc' class='hiddenSel'") && settings.contains("id='setDc' class='hiddenSel'"));
        assertTrue(settings.contains("class=\"defBtn ac\"") && settings.contains("function markDefaults()"));
        assertTrue(settings.contains("'.prefs .setNum'") && !settings.contains("setGrid"));
    }

    @Test
    public void chargingPageSpeaksTheHistoryLanguage() {
        // 24-hour dates built by hand (the car's locale gave 12-hour ones), compact recent-charge rows, a grey idle badge
        String page = BatteryWebPages.charging();
        assertFalse(page.contains("toLocaleTimeString") || page.contains("toLocaleDateString"));
        assertTrue(page.contains("class=\"crow\"") && page.contains("sessions.slice(live?-1:-3)") && page.contains("class='liveBadge idle'"));
        assertTrue(page.contains("class='kwin'") && page.contains("chartKw'+(sign<0?' in':'')") && page.contains("RANGE_LABEL[historyRange]"));
        assertFalse(page.contains("toFixed(2)+' kW'") || page.contains("samples')"));
    }

    @Test
    public void statusPackSortsItsSensorsAfterBuildingTheCallouts() {
        // showTip reads packState.temps[sensor - 1]; the callouts are built out of number order, so sort after the last one
        String page = BatteryWebPages.status();
        assertTrue(page.contains("tag(12,"));
        assertTrue(page.indexOf("sensors.sort((a,b)=>a.n-b.n)") > page.indexOf("tag(12,"));
    }

    @Test
    public void statusPageIsThePackCardWithTheSocDrawnInsideIt() {
        // the Battery and Health cards are gone; the SOC readouts live inside the pack SVG so num() still finds them
        String page = BatteryWebPages.status();
        assertFalse(page.contains("heroCard"));
        assertFalse(page.contains("healthCard"));
        int svg = page.indexOf("<svg id='packSvg'"), end = page.indexOf("</svg>", svg);
        assertTrue(svg > 0 && end > svg);
        for (String id : new String[]{"id='batterySoc'", "id='batterySocRaw'"}) {
            int at = page.indexOf(id);
            assertTrue(id + " is not inside the pack svg", at > svg && at < end);
        }
    }

    @Test
    public void flowDotsRideCssKeyframesNotSmil() {
        // SMIL animateMotion repaints the SVG every frame on the head unit; the dots must stay HTML on CSS transform keyframes
        String page = BatteryWebPages.status();
        assertFalse(page.contains("animateMotion"));
        assertTrue(page.contains("'@keyframes '+name+'{'"));
        assertTrue(page.contains("transform:translate("));
        assertTrue(page.contains("id='flowMovers'"));
    }

    @Test
    public void energyFlowLivesOnTheStatusPageAndItsTabIsGone() {
        String page = BatteryWebPages.status();
        assertFalse(page.contains("/batt-flow"));
        for (String id : new String[]{"flowPath_hv", "flowPath_chg", "loadMotorBar", "dcdcState", "chgNode", "totUsed"}) {
            assertTrue(id + " missing", page.contains("id='" + id + "'"));
        }
        assertFalse(BatteryWebPages.charging().contains("/batt-flow"));
    }
    @Test
    public void thePagesCarryNoWebConsoleChrome() {
        // the pages live only inside the app since 2026-10-13: no console top bar, nav, title suffix or CSV download
        for (String page : new String[]{BatteryWebPages.status(), BatteryWebPages.charging(), BatteryWebPages.history(), BatteryWebPages.settings()}) {
            for (String chrome : new String[]{"Web Console", "class='topbar'", "mainNav", "/batt-log.csv", "/logout", "ambientGlow"}) {
                assertFalse(chrome, page.contains(chrome));
            }
        }
    }

    @Test
    public void statusHeaderShowsDataFreshnessAndNeverFakesLiveReadings() {
        String page = BatteryWebPages.status();
        // a 'last updated' relative-time element sits in the header next to the stream-state badge
        assertTrue(page.contains("id='batteryUpdated'"));
        assertTrue(page.contains("id='batteryStreamBadge'"));
        // freshness is driven from a timestamp in the data JSON and resolves to a clear connection state
        assertTrue(page.contains("data.lastUpdatedMs"));
        assertTrue(page.contains("function drawFreshness("));
        for (String label : new String[]{"'Live'", "'Stale'", "'Reconnecting'"})
            assertTrue("missing freshness state " + label, page.contains(label));
        // a transport-up but frozen stream is marked stale, not shown as current
        assertTrue(page.contains("isStale"));
        // no-data shows -- (num()), which is distinct from a real 0 that still formats to a number
        assertTrue(page.contains("el.textContent='--'"));
    }

    @Test
    public void statusMarksExtremeCellsWithAShapeNotColourAlone() {
        String page = BatteryWebPages.status();
        // ▲ / ▼ markers ride the schematic's highest/lowest cell, and the legend documents the shapes
        assertTrue(page.contains("'cellMark'"));
        assertTrue(page.contains("maxMark") && page.contains("minMark"));
        assertTrue(page.contains("▼ lowest · ▲ highest"));
        // a tapped cell/sensor also gets an outline highlight at its position
        assertTrue(page.contains("packSel") && page.contains("function highlight("));
    }

    @Test
    public void emptyHistoryExplainsDataAppearsAfterTheFirstCharge() {
        String page = BatteryWebPages.history();
        assertTrue(page.contains("emptyState"));
        assertTrue(page.contains("No charges logged yet"));
        assertTrue(page.contains("After the first charge"));
        // the bare single-paragraph placeholder is gone
        assertFalse(page.contains("No charge recorded yet. One shows up here"));
    }
}
