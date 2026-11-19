package com.catfish.battery;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static com.catfish.battery.SchematicGeometry.*;
import static com.catfish.battery.SchematicReadings.shade;

/** Static artwork, readings and touch targets for the native Status schematic. */
final class PackSchematicView extends View {
    private static final Typeface NORMAL = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    private static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private static final int NIGHT_ACCENT = 0xff4cc9f0;
    private static final Typeface MONO = Typeface.create("monospace", Typeface.BOLD);
    private static final DashPathEffect DASH = new DashPathEffect(new float[]{4, 5}, 0);
    private static final String[] LOAD_NAMES = {"Motor", "Cabin climate", "Battery thermal", "Other"};
    private static final String[] FLOW_KEYS = {"hv", "dc", "lv", "chg"};

    private final Shader nightPanel = new RadialGradient(0,0,1,
            new int[]{0xff10202e,0xff08121b,0xff050c13},new float[]{0,.6f,1},Shader.TileMode.CLAMP);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<DrawableBox> solids = new ArrayList<>();
    private final DrawableBox base = new DrawableBox(box(0,-6,-6,0,372,192,12));
    private final DrawableBox[] cells = new DrawableBox[118];
    private final List<DrawableSensor> sensors = new ArrayList<>();
    private final Path[] flows = new Path[FLOW_KEYS.length];
    private final float[][] flowPaths = new float[FLOW_KEYS.length][];
    private final Path sparkLine = new Path(), sparkArea = new Path(), swipeClip = new Path();
    private float sparkEndX, sparkEndY;
    private SocTrend trend;
    // glass pack (2026-11-19): soft floor reflection under the tray
    private final Shader floorGlow = new RadialGradient(0,0,1,new int[]{0x45000000,0x21000000,0},
            new float[]{0,.55f,1},Shader.TileMode.CLAMP);
    private final Point floor = iso(185,175,0);
    // swipe card above Loads: Range, Pack power, Extremes
    private static final String[] PAGES = {"Range","Pack power","Extremes"};
    // Pack power bar ends (owner, 2026-11-19): regen peaks around 130-140 kW, drive around 180 kW
    private static final int REGEN_KW = 140, DRIVE_KW = 180;
    private final SwipePager pager = new SwipePager(PAGES.length,SWIPE_R-SWIPE_L,ValueAnimator.areAnimatorsEnabled());
    private boolean swiping;
    private final Path busPositive = line(iso(54,87,45),iso(360,87,45));
    private final Path busNegative = line(iso(54,92,45),iso(360,92,45));
    // lit back edges of the BMS lid (its top face corners a, b, c: box(0,0,0,12,54,180,42))
    private final Path bmsGlint = polyline(new float[]{iso(0,0,54).x(),iso(0,0,54).y(),iso(54,0,54).x(),iso(54,0,54).y(),
            iso(54,180,54).x(),iso(54,180,54).y()});
    private SchematicData data = new SchematicData(null);
    // cell lift (CellMotion): targets are rebuilt in setData/setNight, never in onDraw
    private final CellMotion motion = new CellMotion(118, ValueAnimator.areAnimatorsEnabled());
    private final float[] lifts = new float[118];
    private final int[] liftColors = new int[118];
    private final boolean[] dangers = new boolean[118];
    private UiPalette palette;
    private Selection selected;
    private float downX, downY;

    PackSchematicView(Context context) {
        super(context);
        palette = UiPalette.of(context);
        Matrix gradient = new Matrix();
        gradient.setScale(WIDTH/2f,HEIGHT*.55f);
        gradient.postTranslate(LEFT+WIDTH/2f,HEIGHT*.45f);
        nightPanel.setLocalMatrix(gradient);
        Matrix glow = new Matrix();
        glow.setScale(232,24);
        floorGlow.setLocalMatrix(glow);
        for (Box box : solids()) {
            DrawableBox drawable = new DrawableBox(box);
            solids.add(drawable);
            if (box.cell() > 0) cells[box.cell() - 1] = drawable;
        }
        for (Sensor sensor : sensors()) sensors.add(new DrawableSensor(sensor));
        for (int i = 0; i < flows.length; i++) {
            flowPaths[i] = flowPath(FLOW_KEYS[i]);
            flows[i] = polyline(flowPaths[i]);
        }
        setClickable(true);
        setContentDescription("Battery pack schematic and energy flow");
        retargetCells(); // grey WAIT colours until the first reading, which then blends in
    }

    void setData(JSONObject batteryData) {
        data = new SchematicData(batteryData);
        retargetCells();
        invalidate();
    }

    void setNight(boolean night) {
        if (palette.night == night) return;
        palette = UiPalette.of(night);
        retargetCells();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Fit fit = fit(getWidth(),getHeight());
        if (fit.scale() <= 0) return;
        long now = SystemClock.uptimeMillis();
        motion.step(now);
        canvas.save();
        canvas.translate(fit.offsetX(),fit.offsetY());
        canvas.scale(fit.scale(),fit.scale());
        canvas.clipRect(LEFT,0,LEFT+WIDTH,HEIGHT);
        paint.setShader(palette.night ? nightPanel : null);
        round(canvas,LEFT,0,LEFT+WIDTH,HEIGHT,12,palette.bgDeep,0);
        paint.setShader(null);
        round(canvas,LEFT,0,LEFT+WIDTH,HEIGHT,12,0,tone(0x144cc9f0,palette.line));
        pager.step(now);
        drawSystem(canvas);
        drawSwipeCard(canvas);
        // glass pack: floor reflection, tray and BMS framed like the cards, translucent cells with a rim
        int edge = tone(0x664cc9f0,0xff9fb2c6), glint = tone(0xffe3eef7,0xffffffff);
        canvas.save();
        canvas.translate(floor.x(),floor.y()+12);
        canvas.rotate(-29);
        paint.setShader(floorGlow);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(palette.night ? 255 : 120);
        canvas.drawOval(-232,-24,232,24,paint);
        paint.setShader(null);
        canvas.restore();
        int tray = tone(0xff141a24,0xffb7c8d9);
        drawBox(canvas,base,tray,tone(0xff06101a,0xff8599af),shade(tray,.65f),edge,.85f);
        for (DrawableBox box : solids) {
            if (box.geometry.cell() == 0) {
                drawBox(canvas,box,tone(0xff2a3446,0xffe7eff7),tone(0xff191f2b,0xffbbccdc),
                        tone(0xff111621,0xffa9bdd2),edge,.85f);
                stroke(canvas,bmsGlint,(glint & 0xffffff) | 0x77000000,.85f);
            } else {
                int i = box.geometry.cell()-1;
                boolean waiting = data.cellBands[i] == SchematicReadings.Band.WAIT && !palette.night;
                int color = motion.color(i);
                float lift = motion.offset(i,now);
                if (lift > .3f) { // the empty slot it rose from, tinted red under a danger cell
                    fill(dangers[i] ? (palette.danger & 0xffffff) | 0x55000000 : tone(0x99000000,0x40000000));
                    canvas.drawPath(box.paths[2],paint);
                }
                canvas.save();
                canvas.translate(0,-lift*1.25f); // iso(): one unit of height is 1.25 design px up
                boolean calm = waiting || data.cellBands[i] == SchematicReadings.Band.GOOD
                        || data.cellBands[i] == SchematicReadings.Band.WAIT;
                fill(shade(color,.72f));
                paint.setAlpha(153);
                canvas.drawPath(box.paths[0],paint);
                fill(shade(color,.54f));
                paint.setAlpha(199);
                canvas.drawPath(box.paths[1],paint);
                fill(color);
                paint.setAlpha(calm ? 170 : 240);
                canvas.drawPath(box.paths[2],paint);
                stroke(canvas,box.paths[2],calm ? (glint & 0xffffff) | 0x55000000 : color,.55f);
                canvas.restore();
            }
        }
        stroke(canvas,busPositive,edge,7);
        stroke(canvas,busPositive,0xfff2616b,4);
        stroke(canvas,busNegative,edge,7);
        stroke(canvas,busNegative,tone(0xff2a3142,0xff3e5062),4);
        for (DrawableSensor drawable : sensors) {
            Sensor sensor = drawable.sensor;
            int n = sensor.number();
            if (n == 5 || n == 6 || n == 9 || n == 10) {
                circle(canvas,sensor.anchor(),8,sensor.color(),sensor.outline(),1.5f);
            }
        }
        drawLegendGroups(canvas);
        for (DrawableSensor sensor : sensors) drawSensor(canvas,sensor);
        drawSelection(canvas,now);
        drawTooltip(canvas);
        canvas.restore();
        if (motion.moving() || motion.hovering() || pager.moving() || pager.dragging()) postInvalidateOnAnimation();
    }

    /** New lift and colour targets from the current readings; the ripple starts at the most deviating cell. */
    private void retargetCells() {
        double median = data.extremes.median();
        int hot = 0;
        double worst = -1;
        for (int i = 0; i < lifts.length; i++) {
            SchematicReadings.Band band = data.cellBands[i];
            double deviation = data.cells[i] - median;
            lifts[i] = CellMotion.lift(band,deviation);
            liftColors[i] = cellColor(band);
            dangers[i] = band == SchematicReadings.Band.DANGER;
            if (Double.isFinite(deviation) && Math.abs(deviation) > worst) { worst = Math.abs(deviation); hot = i; }
        }
        motion.retarget(lifts,liftColors,dangers,hot,SystemClock.uptimeMillis());
    }

    /** Small rotating arc while the BECM SOH read runs (about 1-2 s); keeps animating only while shown. */
    private void spinner(Canvas canvas, float cx, float cy, float r) {
        float sweep = (android.os.SystemClock.uptimeMillis() % 900L) * 360f / 900f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(muted());
        paint.setAlpha(70);
        canvas.drawCircle(cx,cy,r,paint);
        paint.setColor(tone(NIGHT_ACCENT,palette.accent));
        canvas.drawArc(cx-r,cy-r,cx+r,cy+r,sweep,100f,false,paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        postInvalidateOnAnimation();
    }

    // Layout 2026-11-19 (design units, wires in SchematicGeometry.flowPath): HV pack card + SOC graph top-left with the
    // sensor legend under it; swipe card, Loads, and 12 V on the right sharing one left edge; DC-DC under the pack.
    private static final float HERO_L = -92, HERO_T = 6, HERO_R = 390, HERO_B = 127, RIGHT = 1278;
    private static final float SWIPE_L = 884, SWIPE_T = 8, SWIPE_R = RIGHT, SWIPE_B = 100, DOTS_Y = 108;
    private static final float LOADS_L = 884, LOADS_T = 120, LOADS_B = 340;
    private static final float INLET_L = 696, INLET_R = 856, INLET_T = 274, INLET_B = 322;
    private static final float LOW_T = 351, LOW_B = 452, DC_L = 574, DC_R = 856, LV_L = 884;
    private static final float SPARK_L = HERO_L + 277, SPARK_R = HERO_R - 16, SPARK_T = HERO_T + 46, SPARK_B = HERO_B - 26;

    void setTrend(SocTrend trend) {
        this.trend = trend;
        invalidate();
    }

    private void drawSystem(Canvas canvas) {
        int dim = tone(0x804cc9f0,0xff8298af), active = tone(NIGHT_ACCENT,palette.accent);
        int flowColor = data.charging ? palette.good : active;
        boolean[] enabled = {data.hvOn || data.charging,data.dcOn,data.dcOn,data.charging};
        boolean inletOn = data.inlet != SchematicReadings.INLET_NONE, v2l = data.inlet == SchematicReadings.INLET_V2L;
        int inletColor = v2l ? SchematicReadings.V2L_BLUE : palette.good;
        enabled[3] = inletOn;
        for (int i = 0; i < flows.length; i++) {
            if (i == 3 && !inletOn) continue; // no plug icon, no inlet wire
            paint.setPathEffect(enabled[i] ? null : DASH);
            stroke(canvas,flows[i],enabled[i] ? (i == 3 ? inletColor : i == 0 && data.charging ? palette.good : active) : dim,2.5f);
            paint.setPathEffect(null);
            float[] xy = flowPaths[i];
            int port = !enabled[i] ? dim : i == 3 ? inletColor : flowColor;
            circle(canvas,new Point(xy[0],xy[1]),4,port,0,0);
            circle(canvas,new Point(xy[xy.length-2],xy[xy.length-1]),4,port,0,0);
        }

        // HV pack card: SOC, SOH, power line, SOC graph
        node(canvas,HERO_L,HERO_T,HERO_R,HERO_B);
        float hx = HERO_L + 16;
        title(canvas,"HV pack",hx,HERO_T+25);
        label(canvas,"400 V",hx+82,HERO_T+25,13,muted(),NORMAL,Paint.Align.LEFT);
        Typeface socFont = palette.night ? LIGHT : BOLD;
        label(canvas,data.soc,hx,HERO_T+82,46,ink(),socFont,Paint.Align.LEFT);
        float sep = hx + 150;
        round(canvas,sep,HERO_T+44,sep+1.5f,HERO_T+84,0,tone(0x404cc9f0,palette.line),0);
        label(canvas,"SOH",sep+12,HERO_T+52,12,kicker(),MONO,Paint.Align.LEFT);
        if (data.sohReading) spinner(canvas,sep+24,HERO_T+70,11);
        else label(canvas,data.soh,sep+12,HERO_T+82,22,ink(),BOLD,Paint.Align.LEFT);
        label(canvas,data.powerLine,hx,HERO_B-13,15,data.powerIn ? palette.good : tone(NIGHT_ACCENT,ink()),BOLD,Paint.Align.LEFT);
        round(canvas,SPARK_L-12,HERO_T+14,SPARK_L-11,HERO_B-14,0,tone(0x214cc9f0,palette.line),0);
        drawTrend(canvas,flowColor);

        // Loads, or the charge in progress
        node(canvas,LOADS_L,LOADS_T,RIGHT,LOADS_B,data.charging ? palette.good : v2l ? inletColor : 0);
        float lx = LOADS_L + 16, rx = RIGHT - 16;
        if (data.charging) {
            label(canvas,data.chargeTitle,lx,LOADS_T+27,18,palette.good,BOLD,Paint.Align.LEFT);
            if (!data.chargeEta.isEmpty()) pill(canvas,data.chargeEta,rx,LOADS_T+26,palette.good,true);
            row(canvas,"SOC",data.socTarget,LOADS_T+62,data.socProgress,palette.good);
            row(canvas,"Added",data.chargeAdded,LOADS_T+103,-1,0);
            row(canvas,LOAD_NAMES[1],data.loadPower[1],LOADS_T+144,data.shares[1],active);
            row(canvas,LOAD_NAMES[2],data.loadPower[2],LOADS_T+185,data.shares[2],active);
        } else {
            if (v2l) label(canvas,data.loadsTitle,lx,LOADS_T+27,18,inletColor,BOLD,Paint.Align.LEFT);
            else {
                title(canvas,"Loads",lx,LOADS_T+27);
                pill(canvas,data.loadsTitle,rx,LOADS_T+26,data.hvOn ? active : muted(),data.hvOn);
            }
            for (int i = 0; i < LOAD_NAMES.length; i++) {
                row(canvas,LOAD_NAMES[i],data.loadPower[i],LOADS_T+62+i*41,data.shares[i],active);
            }
        }
        if (inletOn) drawInlet(canvas,inletColor,data.inlet == SchematicReadings.INLET_DC);

        // DC-DC and the 12 V battery
        node(canvas,DC_L,LOW_T,DC_R,LOW_B);
        title(canvas,"DC-DC",DC_L+16,LOW_T+27);
        pill(canvas,data.dcState,DC_R-16,LOW_T+26,data.dcOn ? palette.good : muted(),data.dcOn);
        readLabel(canvas,"HV side",DC_L+16,LOW_T+58,muted());
        value(canvas,data.dcHigh,DC_R-16,LOW_T+58);
        readLabel(canvas,"12 V",DC_L+16,LOW_T+84,muted());
        value(canvas,data.dcLow,DC_R-16,LOW_T+84);
        node(canvas,LV_L,LOW_T,RIGHT,LOW_B);
        title(canvas,"12 V battery",LV_L+16,LOW_T+27);
        boolean lvCharging = "charging".equals(data.lvState);
        if (!UiFormat.MISSING.equals(data.lvState)) pill(canvas,data.lvState,RIGHT-16,LOW_T+26,lvCharging ? palette.good : muted(),lvCharging);
        readLabel(canvas,"Voltage",LV_L+16,LOW_T+58,muted());
        label(canvas,data.lvVoltage,RIGHT-16,LOW_T+58,18,bandInk(data.lvBand,ink()),BOLD,Paint.Align.RIGHT);
        readLabel(canvas,"SOC",LV_L+16,LOW_T+84,muted());
        value(canvas,data.lvSoc,RIGHT-16,LOW_T+84);
    }

    /** A state pill ending at {@code right}, text baseline {@code y}; filled tints it with {@code color}. */
    private void pill(Canvas c,String text,float right,float y,int color,boolean filled) {
        if (text == null || text.isEmpty()) return;
        float w = measure(text,13,BOLD) + 22;
        round(c,right-w,y-17,right,y+6,11.5f,filled ? (color & 0xffffff) | tone(0x24000000,0x1c000000) : tone(0xff0d1a26,0xfff0f5fa),
                filled ? color : tone(0x384cc9f0,palette.line));
        label(c,text,right-w/2,y-1,13,color,BOLD,Paint.Align.CENTER);
    }

    /** Swipe card above Loads: three pages on one strip, clipped to the card; dots under it show the page. */
    private void drawSwipeCard(Canvas c) {
        float w = SWIPE_R - SWIPE_L, offset = pager.offset();
        c.save();
        swipeClip.rewind();
        swipeClip.addRoundRect(SWIPE_L,SWIPE_T-2,SWIPE_R,SWIPE_B+2,14,14,Path.Direction.CW);
        c.clipPath(swipeClip);
        for (int i = 0; i < PAGES.length; i++) {
            float dx = i * w + offset;
            if (dx <= -w || dx >= w) continue;
            c.save();
            c.translate(dx,0);
            node(c,SWIPE_L,SWIPE_T,SWIPE_R,SWIPE_B);
            title(c,PAGES[i],SWIPE_L+16,SWIPE_T+27);
            if (i == 0) drawRangePage(c);
            else if (i == 1) drawPowerPage(c);
            else drawExtremesPage(c);
            c.restore();
        }
        c.restore();
        float at = -offset / w, mid = (SWIPE_L + SWIPE_R) / 2;
        for (int i = 0; i < PAGES.length; i++) {
            float k = Math.max(0,1 - Math.abs(at - i));
            fill(k > .5f ? tone(NIGHT_ACCENT,palette.accent) : muted());
            paint.setAlpha((int) (255 * (.35f + .65f * k)));
            c.drawCircle(mid + (i - (PAGES.length - 1) / 2f) * 14,DOTS_Y,2.6f + 1.2f * k,paint);
        }
    }

    private void drawRangePage(Canvas c) {
        float l = SWIPE_L, t = SWIPE_T, r = SWIPE_R, b = SWIPE_B;
        Typeface big = palette.night ? LIGHT : BOLD;
        label(c,data.rangeKm,l+16,b-14,38,ink(),big,Paint.Align.LEFT);
        label(c,"km",l+22+measure(data.rangeKm,38,big),b-15,16,muted(),NORMAL,Paint.Align.LEFT);
        float sx = l + 190;
        round(c,sx-14,t+44,sx-12.5f,b-10,0,tone(0x214cc9f0,palette.line),0);
        label(c,"Energy left",sx,t+56,13,muted(),NORMAL,Paint.Align.LEFT);
        value(c,data.energyLeft,r-16,t+56);
        round(c,sx,b-26,r-16,b-22,2,track(),0);
        if (Double.isFinite(data.energyShare)) {
            round(c,sx,b-26,sx+(r-16-sx)*(float) Math.min(1,data.energyShare),b-22,2,tone(NIGHT_ACCENT,palette.accent),0);
        }
        label(c,"Usable at this SOC",sx,b-8,12,muted(),NORMAL,Paint.Align.LEFT);
    }

    private void drawPowerPage(Canvas c) {
        float l = SWIPE_L, r = SWIPE_R, b = SWIPE_B, t = SWIPE_T;
        int accent = tone(NIGHT_ACCENT,palette.accent);
        String state = data.powerState;
        boolean green = "Charging".equals(state) || "Regen".equals(state);
        pill(c,state,r-16,t+26,green ? palette.good : "In use".equals(state) ? accent : muted(),green || "In use".equals(state));
        Typeface big = palette.night ? LIGHT : BOLD;
        boolean in = data.packKw <= -.05;
        label(c,data.packKwText,l+16,b-14,30,in ? palette.good : ink(),big,Paint.Align.LEFT);
        label(c,"kW",l+22+measure(data.packKwText,30,big),b-15,15,muted(),NORMAL,Paint.Align.LEFT);
        // one bar on one kW scale: up to REGEN_KW back into the pack on the left, DRIVE_KW out on the right
        float bl = l + 150, br = r - 16, zero = bl + (br - bl) * REGEN_KW / (REGEN_KW + DRIVE_KW), y = b - 26;
        round(c,bl,y-3,br,y+3,3,track(),0);
        if (Double.isFinite(data.packKw) && Math.abs(data.packKw) >= .05) {
            float x = data.packKw > 0 ? zero + (br - zero) * (float) Math.min(1,data.packKw / DRIVE_KW)
                    : zero - (zero - bl) * (float) Math.min(1,-data.packKw / REGEN_KW);
            round(c,Math.min(x,zero),y-3,Math.max(x,zero),y+3,3,data.packKw > 0 ? accent : palette.good,0);
        }
        round(c,zero-.75f,y-8,zero+.75f,y+8,0,muted(),0);
        label(c,data.charging ? "Into pack" : "Regen",bl,y-10,11.5f,muted(),NORMAL,Paint.Align.LEFT);
        label(c,"Drive",br,y-10,11.5f,muted(),NORMAL,Paint.Align.RIGHT);
        label(c,String.valueOf(REGEN_KW),bl,y+18,11,muted(),NORMAL,Paint.Align.LEFT);
        label(c,"0",zero,y+18,11,muted(),NORMAL,Paint.Align.CENTER);
        label(c,String.valueOf(DRIVE_KW),br,y+18,11,muted(),NORMAL,Paint.Align.RIGHT);
    }

    private void drawExtremesPage(Canvas c) {
        float l = SWIPE_L, t = SWIPE_T, col = (SWIPE_R - SWIPE_L - 32) / 3;
        int hot = data.hotSensor, cool = data.coolSensor;
        extreme(c,l+16,"\u25b2 Highest cell",data.highCellV,data.highCell == 0 ? "cell --" : "cell #" + data.highCell,ink());
        extreme(c,l+16+col,"\u25b2 Hottest",hot == 0 ? UiFormat.MISSING : data.sensorReadings[hot-1] + "C",
                hot == 0 ? "sensor --" : "S" + hot + " \u00b7 " + sensorName(hot),hot == 0 ? ink() : bandInk(data.sensorBands[hot-1],ink()));
        extreme(c,l+16+2*col,"\u25bc Coolest",cool == 0 ? UiFormat.MISSING : data.sensorReadings[cool-1] + "C",
                cool == 0 ? "sensor --" : "S" + cool + " \u00b7 " + sensorName(cool),ink());
    }

    private void extreme(Canvas c,float x,String head,String main,String sub,int color) {
        label(c,head,x,SWIPE_T+47,12,muted(),NORMAL,Paint.Align.LEFT);
        label(c,main,x,SWIPE_T+68,18,color,BOLD,Paint.Align.LEFT);
        label(c,sub,x,SWIPE_T+85,12,muted(),NORMAL,Paint.Align.LEFT);
    }

    private String sensorName(int number) {
        for (DrawableSensor d : sensors) if (d.sensor.number() == number) return d.sensor.name();
        return "";
    }

    private int track() { return tone(0x1f4cc9f0,0xffdce6f0); }

    /** The plug badge left of the Loads card: AC Type 2 or CCS2 icon with its power; green in, blue out (V2L). */
    private void drawInlet(Canvas c,int color,boolean ccs2) {
        round(c,INLET_L,INLET_T,INLET_R,INLET_B,12,(color & 0x00ffffff) | 0x1c000000,color);
        float cx = INLET_L + 23, cy = (INLET_T + INLET_B) / 2f;
        c.save();
        c.translate(cx,cy);
        float k = .58f;
        c.scale(k,k);
        c.translate(-30,ccs2 ? -34 : -32);
        outline(color,3);
        c.drawPath(ccs2 ? CCS2_SHELL : TYPE2_SHELL,paint);
        fill(color);
        float[][] pins = ccs2 ? CCS2_PINS : TYPE2_PINS;
        for (float[] p : pins) c.drawCircle(p[0],p[1],p[2],paint);
        c.restore();
        label(c,data.inletName,INLET_L+45,cy-4,14,color,BOLD,Paint.Align.LEFT);
        label(c,data.inletValue,INLET_L+45,cy+14,12,color,NORMAL,Paint.Align.LEFT);
    }

    // connector faces in a 60-unit box: Type 2 (flat top, 7 pins); CCS2 (Type 2 signal half over two DC pins)
    private static final Path TYPE2_SHELL = new Path(), CCS2_SHELL = new Path();
    private static final float[][] TYPE2_PINS = {{23,21,2.6f},{37,21,2.6f},{17,32,4},{30,32,4},{43,32,4},{23,44,4},{37,44,4}};
    private static final float[][] CCS2_PINS = {{23,15,2.6f},{37,15,2.6f},{30,27,4},{20,53,6},{40,53,6}};
    static {
        TYPE2_SHELL.moveTo(11.2f,14);
        TYPE2_SHELL.lineTo(48.8f,14);
        TYPE2_SHELL.arcTo(4,6,56,58,-43.8f,267.6f,false);
        TYPE2_SHELL.close();
        CCS2_SHELL.moveTo(11.2f,8);
        CCS2_SHELL.lineTo(48.8f,8);
        CCS2_SHELL.arcTo(6,-6,54,38,-35,250,false);
        CCS2_SHELL.close();
        CCS2_SHELL.addRoundRect(9,42,51,64,11,11,Path.Direction.CW);
    }

    /** One card row: label left, value right, an optional share bar underneath (share < 0: no bar). */
    private void row(Canvas c,String name,String reading,float y,double share,int barColor) {
        label(c,name,LOADS_L+16,y,15,muted(),NORMAL,Paint.Align.LEFT);
        value(c,reading,RIGHT-16,y);
        if (share < 0) return;
        round(c,LOADS_L+16,y+8,RIGHT-16,y+12,2,track(),0);
        float w = (RIGHT-LOADS_L-32) * (float) Math.max(0,Math.min(1,share));
        if (w > 0) round(c,LOADS_L+16,y+8,LOADS_L+16+w,y+12,2,barColor,0);
    }

    /** The SOC sparkline in the HV PACK card; its window follows the car's state (SocTrend). */
    private void drawTrend(Canvas c,int color) {
        SocTrend t = trend;
        label(c,t == null ? "SOC" : t.label,SPARK_L,HERO_T+25,12,kicker(),MONO,Paint.Align.LEFT);
        if (t == null || t.empty()) {
            label(c,"collecting data",(SPARK_L+SPARK_R)/2,(SPARK_T+SPARK_B)/2+5,13,muted(),NORMAL,Paint.Align.CENTER);
            return;
        }
        label(c,t.delta,SPARK_R,HERO_T+25,13,ink(),BOLD,Paint.Align.RIGHT);
        float span = Math.max(1,t.toMs - t.fromMs), range = (float) Math.max(1e-6,t.hi - t.lo);
        sparkLine.rewind();
        for (int i = 0; i < t.soc.length; i++) {
            float x = SPARK_L + (float) ((t.ts[i] - t.fromMs) / span) * (SPARK_R - SPARK_L);
            float y = SPARK_B - (float) ((t.soc[i] - t.lo) / range) * (SPARK_B - SPARK_T);
            if (i == 0) sparkLine.moveTo(x,y); else sparkLine.lineTo(x,y);
            if (i == t.soc.length - 1) { sparkEndX = x; sparkEndY = y; }
        }
        float startX = SPARK_L + (float) ((t.ts[0] - t.fromMs) / span) * (SPARK_R - SPARK_L);
        sparkArea.set(sparkLine);
        sparkArea.lineTo(sparkEndX,SPARK_B);
        sparkArea.lineTo(startX,SPARK_B);
        sparkArea.close();
        round(c,SPARK_L,SPARK_B,SPARK_R,SPARK_B+1,0,tone(0xff1b3043,palette.line),0);
        fill(color);
        paint.setAlpha(30);
        c.drawPath(sparkArea,paint);
        if (Double.isFinite(t.target)) {
            float y = SPARK_B - (float) ((t.target - t.lo) / range) * (SPARK_B - SPARK_T);
            paint.setPathEffect(DASH);
            outline(palette.good,1.5f);
            c.drawLine(SPARK_L,y,SPARK_R,y,paint);
            paint.setPathEffect(null);
            label(c,UiFormat.fixed(t.target,0," %"),SPARK_L,y-4,11,palette.good,NORMAL,Paint.Align.LEFT);
        }
        stroke(c,sparkLine,color,2.2f);
        circle(c,new Point(sparkEndX,sparkEndY),4,color,0,0);
        label(c,t.fromText,SPARK_L,SPARK_B+16,11,muted(),NORMAL,Paint.Align.LEFT);
        label(c,"now",SPARK_R,SPARK_B+16,11,muted(),NORMAL,Paint.Align.RIGHT);
    }

    /** Group headers of the sensor legend with a bar beside each group's rows (LEGEND_HEADER_Y in the geometry). */
    private void drawLegendGroups(Canvas canvas) {
        int[] rowsPerGroup = {1,2,2,2};
        int color = kicker(); // group headers are labels, not accents
        paint.setLetterSpacing(1/12f);
        for (int g = 0; g < LEGEND_GROUPS.length; g++) {
            float header = LEGEND_HEADER_Y[g], last = header + 22 + (rowsPerGroup[g]-1) * 26;
            label(canvas,LEGEND_GROUPS[g],LEGEND_X,header,12,color,MONO,Paint.Align.LEFT);
            round(canvas,LEGEND_X-12,header-11,LEGEND_X-9,last+9,1.5f,(tone(0xff5f7890,0xff9fb2c6) & 0xffffff) | 0x8c000000,0);
        }
        paint.setLetterSpacing(0);
    }

    private void drawSensor(Canvas canvas, DrawableSensor drawable) {
        Sensor sensor = drawable.sensor;
        Point at = sensor.labelAt();
        stroke(canvas,drawable.lead,tone(0xff5f7890,0xff8c7131),1.4f);
        circle(canvas,sensor.anchor(),2.6f,tone(0xff8599af,0xffa18436),0,0);
        int index = sensor.number()-1;
        String reading = data.sensorReadings[index];
        int readingColor = bandInk(data.sensorBands[index],ink());
        if (sensor.tag()) {
            float nameWidth = measure(sensor.label(),12,NORMAL), width = nameWidth + 6 + measure(reading,14,BOLD) + 20;
            RectF r = drawable.labelBounds;
            r.set(at.x()-width/2,at.y()-12,at.x()+width/2,at.y()+12);
            round(canvas,r.left,r.top,r.right,r.bottom,7,tone(0xd9080c14,0xf2ffffff),tone(0x59a0afc8,palette.line));
            label(canvas,sensor.label(),r.left+10,at.y()+5,12,muted(),NORMAL,Paint.Align.LEFT);
            label(canvas,reading,r.left+16+nameWidth,at.y()+5,14,readingColor,BOLD,Paint.Align.LEFT);
        } else {
            circle(canvas,new Point(at.x()+5,at.y()-5),5,sensor.color(),sensor.outline(),1);
            label(canvas,sensor.label(),at.x()+17,at.y(),16,muted(),NORMAL,Paint.Align.LEFT);
            label(canvas,reading,LEGEND_VALUE_X,at.y(),17,readingColor,BOLD,Paint.Align.RIGHT);
        }
    }

    private Selection target(float x, float y) {
        for (int i = sensors.size()-1; i >= 0; i--) {
            DrawableSensor target = sensors.get(i);
            Sensor s = target.sensor;
            if (target.labelBounds.contains(x,y)) return new Selection(0,s,target.labelBounds);
            Point at = s.anchor();
            float radius = s.number() == 5 || s.number() == 6 || s.number() == 9 || s.number() == 10 ? 8 : 5;
            if (Math.hypot(x-at.x(),y-at.y()) <= radius) {
                return new Selection(0,s,new RectF(at.x()-radius,at.y()-radius,at.x()+radius,at.y()+radius));
            }
        }
        for (int i = solids.size()-1; i >= 0; i--) {
            DrawableBox box = solids.get(i);
            if (!box.bounds.contains(x,y)) continue;
            for (int j = 2; j >= 0; j--) {
                if (box.faces[j].contains(x,y)) {
                    return box.geometry.cell() == 0 ? null : new Selection(box.geometry.cell(),null,box.faceBounds[j]);
                }
            }
        }
        return null;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                downX = event.getX();
                downY = event.getY();
                Fit fit = fit(getWidth(),getHeight());
                float x = fit.designX(downX), y = fit.designY(downY);
                swiping = fit.scale() > 0 && x >= SWIPE_L && x <= SWIPE_R && y >= SWIPE_T && y <= SWIPE_B;
                if (swiping) pager.down(x,event.getEventTime());
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (!swiping) return true;
                Fit fit = fit(getWidth(),getHeight());
                pager.move(fit.designX(event.getX()),event.getEventTime());
                int slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if (Math.abs(event.getX()-downX) > slop && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                postInvalidateOnAnimation();
                return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
                if (swiping) {
                    swiping = false;
                    pager.up(fit(getWidth(),getHeight()).designX(downX),event.getEventTime()); // springs back
                    postInvalidateOnAnimation();
                }
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                Fit fit = fit(getWidth(),getHeight());
                if (swiping) {
                    swiping = false;
                    pager.up(fit.designX(event.getX()),event.getEventTime());
                    postInvalidateOnAnimation();
                    return true;
                }
                float tapX = fit.designX(event.getX()), tapY = fit.designY(event.getY());
                if (fit.scale() > 0 && Math.abs(tapY-DOTS_Y) <= 10 && Math.abs(tapX-(SWIPE_L+SWIPE_R)/2) <= 30) {
                    pager.go(Math.round((tapX-(SWIPE_L+SWIPE_R)/2)/14) + (PAGES.length-1)/2,event.getEventTime());
                    postInvalidateOnAnimation();
                    return true;
                }
                int slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if (fit.scale() > 0 && Math.hypot(event.getX()-downX,event.getY()-downY) <= slop) {
                    selected = target(fit.designX(event.getX()),fit.designY(event.getY()));
                    performClick();
                    if (selected != null) announceForAccessibility(selected.text(data));
                    invalidate();
                }
                return true;
            }
            default -> { return true; }
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void drawSelection(Canvas canvas,long now) {
        if (selected == null) return;
        RectF r = selected.bounds;
        float dy = selected.cell > 0 ? -motion.offset(selected.cell-1,now)*1.25f : 0; // follow a lifted cell
        outline(tone(0xff8addf5,palette.accent),2.5f);
        canvas.drawRoundRect(r.left-3,r.top-3+dy,r.right+3,r.bottom+3+dy,3,3,paint);
    }

    private void drawTooltip(Canvas canvas) {
        if (selected == null) return;
        RectF r = selected.bounds;
        String tip = selected.text(data);
        float width = measure(tip,13,NORMAL)+20;
        float x = Math.max(LEFT+4,Math.min(r.centerX()-width/2,LEFT+WIDTH-width-4));
        float y = r.top >= 38 ? r.top-36 : r.bottom+10;
        round(canvas,x,y,x+width,y+26,8,palette.surface2,palette.line);
        label(canvas,tip,x+10,y+18,13,palette.text,NORMAL,Paint.Align.LEFT);
    }

    private int cellColor(SchematicReadings.Band band) {
        return switch (band) {
            case GOOD -> tone(0xff42cb80,0xff3ddba0);
            case WARN -> tone(0xffeb9a04,0xfff5b73c);
            case DANGER -> tone(0xfffa7fb3,0xfff2616b); // night: berry, not red (red reads as broken)
            case WAIT -> tone(0xff2a3645,0xffc1d1df);
        };
    }

    private int bandInk(SchematicReadings.Band band,int fallback) {
        return switch (band) {
            case GOOD -> palette.good;
            case WARN -> palette.warn;
            case DANGER -> palette.danger;
            case WAIT -> fallback;
        };
    }

    private int tone(int night,int day) { return palette.night ? night : day; }
    private int ink() { return tone(0xffeef3fb,palette.text); }
    private int muted() { return tone(0xff9aa7bd,palette.muted); }
    private int kicker() { return tone(0xff8f9cb3,palette.muted); }
    private int nodeColor() { return tone(0xe60D1A26,palette.surface); }

    private void node(Canvas c,float l,float t,float r,float b) { node(c,l,t,r,b,0); }

    /** A card; accent != 0 draws its border in that colour (the Loads card turns green while charging). */
    private void node(Canvas c,float l,float t,float r,float b,int accent) {
        round(c,l,t,r,b,14,nodeColor(),accent != 0 ? accent : tone(0x384cc9f0,palette.line));
    }
    private void title(Canvas c,String s,float x,float y) { label(c,s,x,y,18,tone(0xffe3eef7,ink()),BOLD,Paint.Align.LEFT); }
    private void value(Canvas c,String s,float x,float y) { label(c,s,x,y,18,ink(),BOLD,Paint.Align.RIGHT); }
    private void readLabel(Canvas c,String s,float x,float y,int color) {
        label(c,s,x,y,17,color,NORMAL,Paint.Align.LEFT);
    }

    private void drawBox(Canvas c,DrawableBox b,int top,int side,int front,int edge,float width) {
        face(c,b.paths[0],side,edge,width);
        face(c,b.paths[1],front,edge,width);
        face(c,b.paths[2],top,edge,width);
    }

    private void face(Canvas c,Path p,int color,int edge,float width) {
        fill(color);
        c.drawPath(p,paint);
        stroke(c,p,edge,width);
    }

    private void fill(int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    private void outline(int color,float width) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private void stroke(Canvas c,Path path,int color,float width) {
        outline(color,width);
        c.drawPath(path,paint);
    }

    private void round(Canvas c,float l,float t,float r,float b,float radius,int color,int edge) {
        if (color != 0) {
            fill(color);
            c.drawRoundRect(l,t,r,b,radius,radius,paint);
        }
        if (edge != 0) {
            outline(edge,1);
            c.drawRoundRect(l,t,r,b,radius,radius,paint);
        }
    }

    private void circle(Canvas c,Point p,float radius,int color,int edge,float width) {
        fill(color);
        c.drawCircle(p.x(),p.y(),radius,paint);
        if (edge != 0) {
            outline(edge,width);
            c.drawCircle(p.x(),p.y(),radius,paint);
        }
    }

    private void textStyle(float size,Typeface font,Paint.Align align) {
        paint.setTypeface(font);
        paint.setTextSize(size);
        paint.setTextAlign(align);
    }

    private void label(Canvas c,String text,float x,float y,float size,int color,Typeface font,Paint.Align align) {
        fill(color);
        textStyle(size,font,align);
        c.drawText(text,x,y,paint);
    }

    private float measure(String text,float size,Typeface font) {
        textStyle(size,font,Paint.Align.LEFT);
        return paint.measureText(text);
    }

    private static Path polyline(float[] xy) {
        Path path = new Path();
        path.moveTo(xy[0],xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i],xy[i+1]);
        return path;
    }

    private static Path line(Point a,Point b) { return polyline(new float[]{a.x(),a.y(),b.x(),b.y()}); }

    private static final class DrawableBox {
        final Box geometry;
        final Face[] faces;
        final Path[] paths = new Path[3];
        final RectF[] faceBounds = new RectF[3];
        final RectF bounds = new RectF();

        DrawableBox(Box box) {
            geometry = box;
            faces = new Face[]{box.side(),box.front(),box.top()};
            for (int i = 0; i < faces.length; i++) {
                Face f = faces[i];
                paths[i] = polyline(new float[]{f.a().x(),f.a().y(),f.b().x(),f.b().y(),
                        f.c().x(),f.c().y(),f.d().x(),f.d().y()});
                paths[i].close();
                faceBounds[i] = new RectF();
                paths[i].computeBounds(faceBounds[i],true);
                bounds.union(faceBounds[i]);
            }
        }
    }

    private static final class DrawableSensor {
        final Sensor sensor;
        final Path lead;
        final RectF labelBounds;

        DrawableSensor(Sensor sensor) {
            this.sensor = sensor;
            Point a = sensor.anchor(), p = sensor.labelAt();
            if (sensor.tag()) {
                float half = (sensor.label().length()*7.2f+48)/2, toward = Math.signum(p.y()-a.y());
                labelBounds = new RectF(p.x()-half,p.y()-12,p.x()+half,p.y()+12); // resized to the text when drawn
                lead = line(a,new Point(a.x(),p.y()-toward*12));
            } else {
                labelBounds = new RectF(LEGEND_X-12,p.y()-16,LEGEND_VALUE_X+4,p.y()+6);
                lead = polyline(legendTrace(sensor));
            }
        }
    }

    private static final class Selection {
        private final int cell;
        private final Sensor sensor;
        private final RectF bounds;

        Selection(int cell,Sensor sensor,RectF bounds) {
            this.cell = cell;
            this.sensor = sensor;
            this.bounds = bounds;
        }


        String text(SchematicData data) {
            return cell != 0 ? SchematicReadings.cellTip(cell,data.cells[cell-1])
                    : SchematicReadings.sensorTip(sensor.number(),sensor.name(),data.temperatures[sensor.number()-1]);
        }
    }
}
