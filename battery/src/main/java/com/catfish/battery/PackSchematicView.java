package com.catfish.battery;

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
    private static final int CHAMPAGNE = 0xffe2c08d;
    private static final Typeface MONO = Typeface.create("monospace", Typeface.BOLD);
    private static final DashPathEffect DASH = new DashPathEffect(new float[]{4, 5}, 0);
    private static final String[] LOAD_NAMES = {"Motor", "Cabin climate", "Battery thermal", "Other"};
    private static final String[] FLOW_KEYS = {"hv", "dc", "lv", "chg"};

    private final Shader nightPanel = new RadialGradient(0,0,1,
            new int[]{0xff16161b,0xff0d0d10,0xff09090b},new float[]{0,.6f,1},Shader.TileMode.CLAMP);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<DrawableBox> solids = new ArrayList<>();
    private final DrawableBox base = new DrawableBox(box(0,-6,-6,0,372,192,12));
    private final DrawableBox[] cells = new DrawableBox[118];
    private final List<DrawableSensor> sensors = new ArrayList<>();
    private final Path[] flows = new Path[FLOW_KEYS.length];
    private final float[][] flowPaths = new float[FLOW_KEYS.length][];
    private final Path sparkLine = new Path(), sparkArea = new Path();
    private float sparkEndX, sparkEndY;
    private SocTrend trend;
    private final Point shadow = iso(180,98,0), front = iso(-1,170,20);
    private final Path busPositive = line(iso(54,87,45),iso(360,87,45));
    private final Path busNegative = line(iso(54,92,45),iso(360,92,45));
    private SchematicData data = new SchematicData(null);
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
    }

    void setData(JSONObject batteryData) {
        data = new SchematicData(batteryData);
        invalidate();
    }

    void setNight(boolean night) {
        if (palette.night == night) return;
        palette = UiPalette.of(night);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Fit fit = fit(getWidth(),getHeight());
        if (fit.scale() <= 0) return;
        canvas.save();
        canvas.translate(fit.offsetX(),fit.offsetY());
        canvas.scale(fit.scale(),fit.scale());
        canvas.clipRect(LEFT,0,LEFT+WIDTH,HEIGHT);
        paint.setShader(palette.night ? nightPanel : null);
        round(canvas,LEFT,0,LEFT+WIDTH,HEIGHT,12,palette.bgDeep,0);
        paint.setShader(null);
        round(canvas,LEFT,0,LEFT+WIDTH,HEIGHT,12,0,tone(0x14e2c08d,palette.line));
        drawSystem(canvas);
        fill(tone(0x66000000,0x22788ea3));
        canvas.drawOval(shadow.x()-328,shadow.y()-44,shadow.x()+328,shadow.y()+96,paint);
        drawBox(canvas,base,tone(0xff26323f,0xffd6e2ed),tone(0xff141a24,0xffb7c8d9),
                tone(0xff0d1119,0xffa5b8cb),tone(0xff51627d,0xff829bb3),.5f);
        for (DrawableBox box : solids) {
            if (box.geometry.cell() == 0) {
                drawBox(canvas,box,tone(0xff2a3446,0xffe7eff7),tone(0xff191f2b,0xffbbccdc),
                        tone(0xff111621,0xffa9bdd2),tone(0xff3c4c66,0xff879fb6),.5f);
            } else {
                SchematicReadings.Band band = data.cellBands[box.geometry.cell()-1];
                int color = cellColor(band);
                boolean waiting = band == SchematicReadings.Band.WAIT && !palette.night;
                drawBox(canvas,box,color,shade(color,waiting ? .87f : .6f),
                        shade(color,waiting ? .78f : .4f),0x2e000000,.4f);
            }
        }
        int wire = tone(0xff06101a,0xff8599af);
        stroke(canvas,busPositive,wire,7);
        stroke(canvas,busPositive,0xfff2616b,4);
        stroke(canvas,busNegative,wire,7);
        stroke(canvas,busNegative,tone(0xff2a3142,0xff3e5062),4);
        for (DrawableSensor drawable : sensors) {
            Sensor sensor = drawable.sensor;
            int n = sensor.number();
            if (n == 5 || n == 6 || n == 9 || n == 10) {
                circle(canvas,sensor.anchor(),8,sensor.color(),sensor.outline(),1.5f);
            }
        }
        label(canvas,"BMS",-62,24,14,kicker(),MONO,Paint.Align.LEFT);
        label(canvas,"SYSTEM",-62,42,14,kicker(),MONO,Paint.Align.LEFT);
        label(canvas,"HV BUS",-62,88,14,kicker(),MONO,Paint.Align.LEFT);
        label(canvas,"DC CHARGE",-62,164,14,kicker(),MONO,Paint.Align.LEFT);
        label(canvas,"COOLANT",-62,240,14,kicker(),MONO,Paint.Align.LEFT);
        for (DrawableSensor sensor : sensors) drawSensor(canvas,sensor);
        label(canvas,"FRONT",front.x(),front.y(),12,tone(0xff8fb0e0,0xff274e78),MONO,Paint.Align.CENTER);
        drawSelection(canvas);
        drawTooltip(canvas);
        canvas.restore();
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
        paint.setColor(tone(CHAMPAGNE,palette.accent));
        canvas.drawArc(cx-r,cy-r,cx+r,cy+r,sweep,100f,false,paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        postInvalidateOnAnimation();
    }

    // Staircase layout around the pack's diamond (design units, see SchematicGeometry.flowPath for the wires):
    // step 1 the wide HV PACK card above the pack's rising edge, step 2 the Loads/Charging card right of the apex with
    // the inlet on its left, step 3 DC-DC and 12 V side by side under the falling edge.
    private static final float HERO_L = 790, HERO_T = 6, HERO_B = 108, RIGHT = 1280;
    private static final float LOADS_L = 930, LOADS_T = 134, LOADS_B = 302;
    private static final float INLET_L = 796, INLET_R = 900, INLET_T = 250, INLET_B = 282;
    private static final float LOW_T = 316, LOW_B = 400, DC_L = 700, DC_R = 980, LV_L = 1004;
    private static final float SPARK_L = 1084, SPARK_R = 1266, SPARK_T = 38, SPARK_B = 84;

    void setTrend(SocTrend trend) {
        this.trend = trend;
        invalidate();
    }

    private void drawSystem(Canvas canvas) {
        int dim = tone(0x80e2c08d,0xff8298af), active = tone(CHAMPAGNE,palette.accent);
        int flowColor = data.charging ? palette.good : active;
        boolean[] enabled = {data.hvOn || data.charging,data.dcOn,data.dcOn,data.charging};
        for (int i = 0; i < flows.length; i++) {
            paint.setPathEffect(enabled[i] ? null : DASH);
            stroke(canvas,flows[i],enabled[i] ? (i == 3 || i == 0 && data.charging ? palette.good : active) : dim,2.5f);
            paint.setPathEffect(null);
            float[] xy = flowPaths[i];
            circle(canvas,new Point(xy[0],xy[1]),4,enabled[i] ? flowColor : dim,0,0);
            circle(canvas,new Point(xy[xy.length-2],xy[xy.length-1]),4,enabled[i] ? flowColor : dim,0,0);
        }

        // step 1: HV PACK card
        node(canvas,HERO_L,HERO_T,RIGHT,HERO_B);
        float x0 = HERO_L + 16;
        label(canvas,data.kicker,x0,26,14,tone(CHAMPAGNE,kicker()),MONO,Paint.Align.LEFT);
        Typeface socFont = palette.night ? LIGHT : BOLD;
        label(canvas,data.soc,x0,74,46,ink(),socFont,Paint.Align.LEFT);
        float sep = x0 + measure("100.0%",46,socFont) + 16;
        round(canvas,sep,40,sep+1.5f,78,0,tone(0x40e2c08d,palette.line),0);
        float sohX = sep + 16;
        label(canvas,"SOH",sohX,48,13,kicker(),MONO,Paint.Align.LEFT);
        if (data.sohReading) spinner(canvas,sohX+12,64,11);
        else label(canvas,data.soh,sohX,74,24,ink(),BOLD,Paint.Align.LEFT);
        label(canvas,data.powerLine,x0,99,16,data.powerIn ? palette.good : tone(CHAMPAGNE,ink()),BOLD,Paint.Align.LEFT);
        drawTrend(canvas,flowColor);

        // step 2: Loads, or the charge in progress
        node(canvas,LOADS_L,LOADS_T,RIGHT,LOADS_B,data.charging ? palette.good : 0);
        float lx = LOADS_L + 14, rx = RIGHT - 14;
        if (data.charging) {
            label(canvas,data.chargeTitle,lx,LOADS_T+24,19,palette.good,BOLD,Paint.Align.LEFT);
            label(canvas,data.chargeEta,rx,LOADS_T+24,15,palette.good,BOLD,Paint.Align.RIGHT);
            row(canvas,"SOC",data.socTarget,LOADS_T+50,data.socProgress,palette.good);
            row(canvas,"Added",data.chargeAdded,LOADS_T+78,-1,0);
            row(canvas,LOAD_NAMES[1],data.loadPower[1],LOADS_T+106,data.shares[1],active);
            row(canvas,LOAD_NAMES[2],data.loadPower[2],LOADS_T+134,data.shares[2],active);
        } else {
            title(canvas,data.loadsTitle,lx,LOADS_T+24);
            for (int i = 0; i < LOAD_NAMES.length; i++) {
                row(canvas,LOAD_NAMES[i],data.loadPower[i],LOADS_T+50+i*28,data.shares[i],active);
            }
        }
        boolean plugged = data.plugged;
        round(canvas,INLET_L,INLET_T,INLET_R,INLET_B,16,plugged ? tone(0x294fe09a,0xffe1f4e9) : nodeColor(),
                plugged ? palette.good : tone(0x38e2c08d,palette.line));
        label(canvas,data.inletLabel,(INLET_L+INLET_R)/2,INLET_T+21,13,plugged ? palette.good : muted(),MONO,Paint.Align.CENTER);

        // step 3: DC-DC and the 12 V battery side by side
        node(canvas,DC_L,LOW_T,DC_R,LOW_B);
        title(canvas,"DC-DC",DC_L+14,LOW_T+24);
        round(canvas,DC_R-66,LOW_T+8,DC_R-14,LOW_T+30,11,
                data.dcOn ? tone(0x294fe09a,0xffe1f4e9) : nodeColor(),data.dcOn ? palette.good : dim);
        label(canvas,data.dcState,DC_R-40,LOW_T+23.5f,13,data.dcOn ? palette.good : muted(),BOLD,Paint.Align.CENTER);
        readLabel(canvas,"HV side",DC_L+14,LOW_T+50,muted());
        value(canvas,data.dcHigh,DC_R-14,LOW_T+50);
        readLabel(canvas,"12 V",DC_L+14,LOW_T+74,muted());
        value(canvas,data.dcLow,DC_R-14,LOW_T+74);
        node(canvas,LV_L,LOW_T,RIGHT,LOW_B);
        title(canvas,"12 V battery",LV_L+14,LOW_T+24);
        value(canvas,data.lvSoc,RIGHT-14,LOW_T+24);
        readLabel(canvas,"Voltage",LV_L+14,LOW_T+50,muted());
        label(canvas,data.lvVoltage,RIGHT-14,LOW_T+50,18,bandInk(data.lvBand,ink()),BOLD,Paint.Align.RIGHT);
        readLabel(canvas,"State",LV_L+14,LOW_T+74,muted());
        value(canvas,data.lvState,RIGHT-14,LOW_T+74);
    }

    /** One card row: label left, value right, an optional share bar underneath (share < 0: no bar). */
    private void row(Canvas c,String name,String reading,float y,double share,int barColor) {
        readLabel(c,name,LOADS_L+14,y,muted());
        value(c,reading,RIGHT-14,y);
        if (share < 0) return;
        round(c,LOADS_L+14,y+6,RIGHT-14,y+9,1.5f,tone(0x1fe2c08d,0xffdce6f0),0);
        float w = (RIGHT-LOADS_L-28) * (float) Math.max(0,Math.min(1,share));
        if (w > 0) round(c,LOADS_L+14,y+6,LOADS_L+14+w,y+9,1.5f,barColor,0);
    }

    /** The SOC sparkline in the HV PACK card; its window follows the car's state (SocTrend). */
    private void drawTrend(Canvas c,int color) {
        SocTrend t = trend;
        label(c,t == null ? "SOC" : t.label,SPARK_L,26,12,kicker(),MONO,Paint.Align.LEFT);
        if (t == null || t.empty()) {
            label(c,"collecting data",(SPARK_L+SPARK_R)/2,(SPARK_T+SPARK_B)/2+5,13,muted(),NORMAL,Paint.Align.CENTER);
            return;
        }
        label(c,t.delta,SPARK_R,26,13,ink(),BOLD,Paint.Align.RIGHT);
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
        round(c,SPARK_L,SPARK_B,SPARK_R,SPARK_B+1,0,tone(0xff26262c,palette.line),0);
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

    private void drawSensor(Canvas canvas, DrawableSensor drawable) {
        Sensor sensor = drawable.sensor;
        Point at = sensor.labelAt();
        stroke(canvas,drawable.lead,tone(0xff9a8640,0xff8c7131),1.4f);
        circle(canvas,sensor.anchor(),2.6f,tone(0xffc9ad55,0xffa18436),0,0);
        int index = sensor.number()-1;
        String reading = data.sensorReadings[index];
        int readingColor = bandInk(data.sensorBands[index],ink());
        if (sensor.tag()) {
            RectF r = drawable.labelBounds;
            round(canvas,r.left,r.top,r.right,r.bottom,7,
                    tone(0xd9080c14,0xf2ffffff),tone(0x59a0afc8,palette.line));
            float nameWidth = measure(sensor.label(),12,NORMAL);
            float start = at.x()-(nameWidth+5+measure(reading,14,BOLD))/2;
            label(canvas,sensor.label(),start,at.y()+5,12,muted(),NORMAL,Paint.Align.LEFT);
            label(canvas,reading,start+nameWidth+5,at.y()+5,14,readingColor,BOLD,Paint.Align.LEFT);
        } else {
            circle(canvas,at,5,sensor.color(),sensor.outline(),1);
            readLabel(canvas,sensor.label(),-44,at.y()+6,muted());
            label(canvas,reading,54,at.y()+6.5f,18,readingColor,BOLD,Paint.Align.RIGHT);
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
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                Fit fit = fit(getWidth(),getHeight());
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

    private void drawSelection(Canvas canvas) {
        if (selected == null) return;
        RectF r = selected.bounds;
        outline(tone(0xfff0d6ad,palette.accent),2.5f);
        canvas.drawRoundRect(r.left-3,r.top-3,r.right+3,r.bottom+3,3,3,paint);
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
            case GOOD -> 0xff3ddba0;
            case WARN -> 0xfff5b73c;
            case DANGER -> 0xfff2616b;
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
    private int nodeColor() { return tone(0xe6121216,palette.surface); }

    private void node(Canvas c,float l,float t,float r,float b) { node(c,l,t,r,b,0); }

    /** A card; accent != 0 draws its border in that colour (the Loads card turns green while charging). */
    private void node(Canvas c,float l,float t,float r,float b,int accent) {
        round(c,l,t,r,b,8,nodeColor(),accent != 0 ? accent : tone(0x38e2c08d,palette.line));
    }
    private void title(Canvas c,String s,float x,float y) { label(c,s,x,y,20,tone(0xfff4efe7,ink()),BOLD,Paint.Align.LEFT); }
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
                float half = (sensor.label().length()*7.2f+48)/2;
                labelBounds = new RectF(p.x()-half,p.y()-12,p.x()+half,p.y()+12);
                lead = polyline(trace(p,new Point(a.x(),p.y()),a));
            } else {
                labelBounds = new RectF(-62,p.y()-11,60,p.y()+11);
                lead = polyline(trace(new Point(60,p.y()),new Point(sensor.lane(),p.y()),
                        new Point(sensor.lane(),a.y()),a));
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
