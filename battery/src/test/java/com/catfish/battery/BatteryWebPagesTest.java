package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
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
        assertTrue(page.contains("viewBox='-100 0 1390 470'"));
        assertTrue(page.contains("function boxFaces("));
        assertTrue(page.contains("const FP={hv:[[-61,110]"));
        assertTrue(page.contains("localStorage.getItem('cfPackView')"));
        assertTrue(page.contains("packTabSchematic"));
        assertTrue(page.contains("packTabCells"));
        assertTrue("pack must fit by width AND height", page.contains("function fitPackBox()"));
    }

    // Pack hull in pack-local SVG units, shadow ellipse included (measured in Chrome: polygons x 219.8..840.2,
    // y 50.4..448.6; shadow x 210.8..866.8). The side panels must stay fully left or right of it.
    private static final double PACK_LEFT = 210.8, PACK_RIGHT = 866.8, MARGIN = 8;

    private static List<double[]> sysNodes(String page) {
        Matcher m = Pattern.compile("<rect class='sysNode' x='(-?[0-9.]+)' y='(-?[0-9.]+)' width='([0-9.]+)' height='([0-9.]+)'").matcher(page);
        List<double[]> out = new ArrayList<>();
        while (m.find()) out.add(new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))});
        return out;
    }

    private static double[][] flow(String page, String key) {
        Matcher m = Pattern.compile(key + ":\\[(\\[-?[0-9]+,-?[0-9]+\\](?:,\\[-?[0-9]+,-?[0-9]+\\])*)\\]").matcher(page);
        assertTrue("flow path " + key, m.find());
        Matcher p = Pattern.compile("\\[(-?[0-9]+),(-?[0-9]+)\\]").matcher(m.group(1));
        List<double[]> pts = new ArrayList<>();
        while (p.find()) pts.add(new double[]{Integer.parseInt(p.group(1)), Integer.parseInt(p.group(2))});
        return pts.toArray(new double[0][]);
    }

    @Test
    public void sidePanelsFitTheWiderViewBoxAndStayBesideThePack() {
        String page = BatteryWebPages.status();
        double x0 = BatteryWebPages.VIEW_X, x1 = x0 + BatteryWebPages.VIEW_W, h = BatteryWebPages.VIEW_H;
        assertEquals("-100 0 1390 470", BatteryWebPages.VIEW_BOX);
        List<double[]> nodes = sysNodes(page);
        assertEquals("Loads, DC-DC, 12 V battery and charger", 4, nodes.size());
        for (double[] r : nodes) {
            String at = java.util.Arrays.toString(r);
            assertTrue(at + " left of viewBox", r[0] >= x0 + MARGIN);
            assertTrue(at + " right of viewBox", r[0] + r[2] <= x1 - MARGIN);
            assertTrue(at + " above viewBox", r[1] >= MARGIN);
            assertTrue(at + " below viewBox", r[1] + r[3] <= h - MARGIN);
            assertTrue(at + " overlaps the pack", r[0] + r[2] <= PACK_LEFT || r[0] >= PACK_RIGHT);
        }
        // every static label/value of the layer sits inside the canvas and outside the pack hull
        Matcher t = Pattern.compile("<text (?:id='[A-Za-z]+' )?class='(?:sys|soc)[A-Za-z]*' x='(-?[0-9.]+)' y='([0-9.]+)'").matcher(page);
        int seen = 0;
        while (t.find()) {
            seen++;
            double x = Double.parseDouble(t.group(1)), y = Double.parseDouble(t.group(2));
            assertTrue("text at " + x + "," + y, x > x0 && x < x1 && y > 0 && y < h && (x < PACK_LEFT || x > PACK_RIGHT));
        }
        assertTrue("found only " + seen + " panel texts", seen >= 20);
        // readable on the car: titles 20, values 18, labels 17 SVG units
        assertTrue(page.contains(".sysTitle{fill:#eef3fb;font:700 20px"));
        assertTrue(page.contains(".sysVal{fill:#eef3fb;font:700 18px"));
        assertTrue(page.contains(".sysLab{fill:#9aa7bd;font:600 17px"));
        assertTrue("BMS legend labels", page.contains("'font-size':17,'font-weight':600"));
        assertTrue("BMS legend values", page.contains("'font-size':18,'font-weight':800,'text-anchor':'end'"));
        String embedded = BatteryWebPages.embedded("status", true);
        assertTrue("fitPackBox uses the viewBox ratio", embedded.contains("(wrap.clientHeight-2)*1390/470"));
        assertFalse(embedded.contains("1180/470"));
        assertTrue("movers map the shifted viewBox origin", page.contains("VX=-100,VW=1390,VH=470") && page.contains("(p[0]-VX)*sc"));
    }

    @Test
    public void packGeometryAndSensorAnchorsKeepTheirOriginalPackLocalCoordinates() {
        // The canvas grows outward (viewBox origin -100) instead of moving the pack, so the applied pack offset is 0:
        // these are the 1.3 constants that place every cell, sensor anchor and the pack ends of the flows.
        String page = BatteryWebPages.status();
        String[] unchanged = {
            "KX=1.1,KY=.62,KZ=1.25,CXp=180,CYp=90",
            "OXp=(1060-(Math.min(...xs)+Math.max(...xs)))/2;OYp=(470-(Math.min(...ys)+Math.max(...ys)))/2",
            "drawBox(-6,-6,0,372,192,12,",
            "[[62,0,1,30],[216,0,90,29],[62,94,31,30],[216,94,61,29]]",
            "const TERM={9:[66,'#f2616b','#0a0f16'],10:[86,'#1a1e26','#5a6478'],5:[126,'#f0792a','#0a0f16'],6:[148,'#2f8fe0','#0a0f16']};",
            "const AT={11:iso(27,20,54),7:iso(70,87,45),8:iso(54,92,45)}",
            "[[1,135,43,-4,-40],[4,289,43,6,-40],[2,135,137,-4,40],[3,289,137,34,22]]",
            "const aa=iso(208,90,30);tag(12,aa[0],aa[1],'air',aa[0]+30,aa[1]-28);",
            "let py=42,ch=224;",
            "tube(87,'#f2616b');tube(92,'#2a3142');",
            "<circle id='flowJunction' class='sysDot' cx='865' cy='341'",
        };
        for (String s : unchanged) assertTrue("pack geometry changed: " + s, page.contains(s));
        assertFalse("pack must not be moved by a transform", page.contains("id='packSvg' transform") || page.contains("<g transform"));
        // pack-side flow geometry: HV still drops under the pack at y 462 and rises at x 865; DC-DC branches at (865,341)
        double[][] hv = flow(page, "hv"), dc = flow(page, "dc");
        assertEquals(462, hv[2][1], 0);
        assertEquals(462, hv[3][1], 0);
        assertEquals(865, hv[3][0], 0);
        assertEquals(865, hv[4][0], 0);
        assertEquals(865, dc[0][0], 0);
        assertEquals(341, dc[0][1], 0);
        // the BMS rows keep their y positions, so HV still leaves the B+ row and the charger feeds the DC CHARGE rows
        assertEquals(110, hv[0][1], 0);
        assertEquals(201, flow(page, "chg")[3][1], 0);
    }

    @Test
    public void everyFlowStillConnectsThePackToItsMovedNode() {
        String page = BatteryWebPages.status();
        List<double[]> n = sysNodes(page);
        double[] loads = n.get(0), dcdc = n.get(1), lv = n.get(2), chg = n.get(3);
        double[][] hv = flow(page, "hv"), dc = flow(page, "dc"), low = flow(page, "lv"), ch = flow(page, "chg");
        double[] hvEnd = hv[hv.length - 1], dcEnd = dc[dc.length - 1];
        assertEquals("HV enters Loads", loads[0], hvEnd[0], 0);
        assertTrue(hvEnd[1] > loads[1] && hvEnd[1] < loads[1] + loads[3]);
        assertEquals("DC-DC branch enters DC-DC", dcdc[0], dcEnd[0], 0);
        assertTrue(dcEnd[1] > dcdc[1] && dcEnd[1] < dcdc[1] + dcdc[3]);
        assertEquals("12 V loop leaves DC-DC", dcdc[0] + dcdc[2], low[0][0], 0);
        assertTrue(low[0][1] > dcdc[1] && low[0][1] < dcdc[1] + dcdc[3]);
        assertEquals("12 V loop enters the 12 V battery", lv[0] + lv[2], low[low.length - 1][0], 0);
        assertTrue(low[low.length - 1][1] > lv[1] && low[low.length - 1][1] < lv[1] + lv[3]);
        assertEquals("charger leaves its node", chg[0], ch[0][0], 0);
        assertTrue(ch[0][1] > chg[1] && ch[0][1] < chg[1] + chg[3]);
        // HV starts at the B+ dot (cx -56, r 5); the charger ends just left of the DC CHARGE dots
        assertTrue(page.contains("pack.append(svgEl('circle',{cx:-56,cy:y,r:5,"));
        assertEquals(-61, hv[0][0], 0);
        assertEquals(-64, ch[ch.length - 1][0], 0);
        for (double[][] f : new double[][][]{hv, dc, low, ch})
            for (double[] p : f)
                assertTrue("flow point " + p[0] + "," + p[1] + " outside viewBox", p[0] > BatteryWebPages.VIEW_X && p[0] < BatteryWebPages.VIEW_X + BatteryWebPages.VIEW_W && p[1] > 0 && p[1] < BatteryWebPages.VIEW_H);
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
