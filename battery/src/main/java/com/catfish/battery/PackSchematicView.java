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
        for (int i = 0; i < flows.length; i++) flows[i] = polyline(flowPath(FLOW_KEYS[i]));
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
        marker(canvas,data.extremes.maximum(),"▲");
        marker(canvas,data.extremes.minimum(),"▼");
        drawTooltip(canvas);
        canvas.restore();
    }

    private void drawSystem(Canvas canvas) {
        int dim = tone(0x80e2c08d,0xff8298af), active = tone(CHAMPAGNE,palette.accent);
        boolean[] enabled = {data.hvOn,data.dcOn,data.dcOn,data.charging};
        paint.setPathEffect(DASH);
        for (int i = 0; i < flows.length; i++) {
            if (i != 3 || data.plugged) stroke(canvas,flows[i],enabled[i] ? active : dim,2.5f);
        }
        paint.setPathEffect(null);
        circle(canvas,new Point(865,341),3.6f,data.dcOn ? active : dim,0,0);
        label(canvas,"HV PACK · 400 V",1258,28,15,tone(CHAMPAGNE,kicker()),MONO,Paint.Align.RIGHT);
        Typeface socFont = palette.night ? LIGHT : BOLD;
        label(canvas,data.soc,1258,72,46,ink(),socFont,Paint.Align.RIGHT);
        // SOH is its own column at a fixed spot left of the SOC hero, so it never jumps with the SOC width
        float sohRight = 1258 - measure("100.0%",46,socFont) - 30;
        label(canvas,"SOH",sohRight,46,13,kicker(),MONO,Paint.Align.RIGHT);
        label(canvas,data.soh,sohRight,72,24,ink(),BOLD,Paint.Align.RIGHT);
        label(canvas,data.range,1258,98,17,muted(),NORMAL,Paint.Align.RIGHT);
        float rangeWidth = data.range.isEmpty() ? 0 : measure(data.range,17,NORMAL)+10;
        label(canvas,data.packPower,1258-rangeWidth,98,20,data.powerIn ? palette.good : ink(),BOLD,Paint.Align.RIGHT);
        label(canvas,data.packVA,1258,120,17,muted(),NORMAL,Paint.Align.RIGHT);

        node(canvas,900,132,1258,288);
        title(canvas,data.loadsTitle,914,158);
        for (int i = 0; i < LOAD_NAMES.length; i++) {
            float y = 180 + i * 30;
            readLabel(canvas,LOAD_NAMES[i],914,y,muted());
            value(canvas,data.loadPower[i],1244,y);
            round(canvas,914,y+7,1244,y+11,2,tone(0x1fe2c08d,0xffdce6f0),0);
            if (data.shares[i] > 0) {
                round(canvas,914,y+7,914+231*(float)data.shares[i],y+11,2,
                        tone(CHAMPAGNE,palette.accent),0);
            }
        }
        node(canvas,900,298,1258,384);
        title(canvas,"DC-DC",914,322);
        round(canvas,1192,306,1244,328,11,
                data.dcOn ? tone(0x294fe09a,0xffe1f4e9) : nodeColor(),data.dcOn ? palette.good : dim);
        label(canvas,data.dcState,1218,321.5f,13,data.dcOn ? palette.good : muted(),BOLD,Paint.Align.CENTER);
        readLabel(canvas,"HV side",914,348,muted());
        value(canvas,data.dcHigh,1244,348);
        readLabel(canvas,"12 V",914,372,muted());
        value(canvas,data.dcLow,1244,372);

        node(canvas,900,394,1258,456);
        title(canvas,"12 V battery",914,418);
        value(canvas,data.lvSoc,1244,418);
        readLabel(canvas,data.lvVoltage,914,444,bandInk(data.lvBand,muted()));
        value(canvas,data.lvState,1244,444);
        if (data.plugged) {
            node(canvas,-60,394,196,456);
            title(canvas,data.dcPlug ? "DC charger" : "AC charger",-48,418);
            value(canvas,data.chargePower,184,418);
            readLabel(canvas,data.chargeNote,-48,444,muted());
        }
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

    private void marker(Canvas canvas,int index,String glyph) {
        if (index < 0) return;
        RectF top = cells[index].faceBounds[2];
        textStyle(13,BOLD,Paint.Align.CENTER);
        fill(0xeb080c14);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.5f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawText(glyph,top.centerX(),top.centerY()+4,paint);
        label(canvas,glyph,top.centerX(),top.centerY()+4,13,0xffffffff,BOLD,Paint.Align.CENTER);
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

    private void node(Canvas c,float l,float t,float r,float b) {
        round(c,l,t,r,b,12,nodeColor(),tone(0x38e2c08d,palette.line));
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
