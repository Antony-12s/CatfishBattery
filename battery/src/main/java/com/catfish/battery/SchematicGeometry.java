package com.catfish.battery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Original 1180×470 pack space, with the web view's left and right system gutters. */
public final class SchematicGeometry {
    public static final int LEFT = -100, WIDTH = 1390, HEIGHT = 470;

    public static final class Fit {
        private final float scale;
        private final float offsetX;
        private final float offsetY;

        Fit(float scale, float offsetX, float offsetY) {
            this.scale = scale;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }

        public float scale() { return scale; }
        public float offsetX() { return offsetX; }
        public float offsetY() { return offsetY; }

        public float x(float designX) { return offsetX + designX * scale; }
        public float y(float designY) { return offsetY + designY * scale; }
        public float designX(float x) { return (x - offsetX) / scale; }
        public float designY(float y) { return (y - offsetY) / scale; }
    }

    public static Fit fit(float width, float height) {
        float scale = Math.min(width / WIDTH, height / HEIGHT);
        return new Fit(scale, (width - WIDTH * scale) / 2 - LEFT * scale,
                (height - HEIGHT * scale) / 2);
    }

    /**
     * Interleaved design-space x/y coordinates in forward flow order. Staircase layout (2026-11-18): every wire is one
     * straight run from a port on the pack outline or a card edge into the middle of the next card's edge.
     * hv: pack apex -> Loads card; dc: pack lower-right face -> DC-DC; lv: DC-DC -> 12 V; chg: inlet -> Loads card.
     */
    public static float[] flowPath(String key) {
        return switch (key) {
            case "hv" -> new float[]{764,192, 930,192};
            case "dc" -> new float[]{430,392, 607,392};
            case "lv" -> new float[]{887,392, 931,392};
            case "chg" -> new float[]{900,274, 930,274};
            default -> throw new IllegalArgumentException(key);
        };
    }

    static final class Point {
        private final float x;
        private final float y;

        Point(float x, float y) {
            this.x = x;
            this.y = y;
        }

        float x() { return x; }
        float y() { return y; }
    }
    static final class Box {
        private final int cell;
        private final float depth;
        private final Face side;
        private final Face front;
        private final Face top;

        Box(int cell, float depth, Face side, Face front, Face top) {
            this.cell = cell;
            this.depth = depth;
            this.side = side;
            this.front = front;
            this.top = top;
        }

        int cell() { return cell; }
        float depth() { return depth; }
        Face side() { return side; }
        Face front() { return front; }
        Face top() { return top; }
    }
    static final class Face {
        private final Point a;
        private final Point b;
        private final Point c;
        private final Point d;

        Face(Point a, Point b, Point c, Point d) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
        }

        Point a() { return a; }
        Point b() { return b; }
        Point c() { return c; }
        Point d() { return d; }

        List<Point> points() { return List.of(a, b, c, d); }
        boolean contains(float x, float y) {
            boolean positive = false, negative = false;
            List<Point> points = points();
            for (int i = 0; i < 4; i++) {
                Point p = points.get(i), q = points.get((i + 1) % 4);
                float cross = (q.x - p.x) * (y - p.y) - (q.y - p.y) * (x - p.x);
                positive |= cross > 0;
                negative |= cross < 0;
            }
            return !(positive && negative);
        }
    }

    static Point iso(float x, float y, float z) {
        // 460: the pack sits 70 units left of the WebView original, so it does not crowd the right-hand cards
        return new Point(460 + (x + y - 270) * 1.1f,
                273.75f + (y - x + 90) * .62f - z * 1.25f);
    }

    static Box box(int cell, float x, float y, float z, float w, float h, float rise) {
        Point a = iso(x,y,z+rise), b = iso(x+w,y,z+rise);
        Point c = iso(x+w,y+h,z+rise), d = iso(x,y+h,z+rise);
        return new Box(cell, y+h/2-x-w/2+z/2,
                new Face(a,d,iso(x,y+h,z),iso(x,y,z)),
                new Face(d,c,iso(x+w,y+h,z),iso(x,y+h,z)), new Face(a,b,c,d));
    }

    static List<Box> solids() {
        List<Box> boxes = new ArrayList<>();
        boxes.add(box(0,0,0,12,54,180,42));
        for (int[] module : new int[][]{{62,0,1,30},{216,0,90,29},{62,94,31,30},{216,94,61,29}}) {
            float width = 146f / module[3];
            for (int k = 0; k < module[3]; k++) {
                boxes.add(box(module[2]+k,module[0]+k*width,module[1],12,width-.4f,86,32));
            }
        }
        boxes.sort(Comparator.comparingDouble(Box::depth));
        return List.copyOf(boxes);
    }

    static final class Sensor {
        private final int number;
        private final String label;
        private final String name;
        private final Point anchor;
        private final Point labelAt;
        private final int color;
        private final int outline;
        private final float lane;

        Sensor(int number, String label, String name, Point anchor, Point labelAt,
                  int color, int outline, float lane) {
            this.number = number;
            this.label = label;
            this.name = name;
            this.anchor = anchor;
            this.labelAt = labelAt;
            this.color = color;
            this.outline = outline;
            this.lane = lane;
        }

        int number() { return number; }
        String label() { return label; }
        String name() { return name; }
        Point anchor() { return anchor; }
        Point labelAt() { return labelAt; }
        int color() { return color; }
        int outline() { return outline; }
        float lane() { return lane; }

        boolean tag() { return Float.isNaN(lane); }
    }

    static List<Sensor> sensors() {
        return List.of(
                tag(1,"P1","P1",iso(135,43,44),-4,-40),
                tag(4,"P4","P4",iso(289,43,44),6,-40),
                tag(2,"P2","P2",iso(135,137,44),-4,40),
                tag(3,"P3","P3",iso(289,137,44),34,22),
                row(11,"board","BMS board",iso(27,20,54),64,154,0xff8f9cb3),
                row(7,"B+","B+",iso(70,87,45),110,146,0xfff2616b),
                row(8,"B−","B−",iso(54,92,45),140,138,0xff1a1e26),
                row(9,"DC+","DC+",iso(-1,66,27),186,130,0xfff2616b),
                row(10,"DC−","DC−",iso(-1,86,27),216,122,0xff1a1e26),
                row(5,"out","Coolant out",iso(-1,126,27),262,114,0xfff0792a),
                row(6,"in","Coolant in",iso(-1,148,27),292,106,0xff2f8fe0),
                tag(12,"air","Air",iso(208,90,30),30,-28));
    }

    private static Sensor row(int n, String label, String name, Point at, float y, float lane, int color) {
        return new Sensor(n,label,name,at,new Point(-56,y),color,
                color == 0xff1a1e26 ? 0xff5a6478 : 0xff0a0f16,lane);
    }

    private static Sensor tag(int n, String label, String name, Point at, float dx, float dy) {
        return new Sensor(n,label,name,at,new Point(at.x+dx,at.y+dy),0,0,Float.NaN);
    }

    static float[] trace(Point... points) {
        float[] result = new float[(points.length - 1) * 4];
        result[0] = points[0].x;
        result[1] = points[0].y;
        int next = 2;
        for (int i = 1; i < points.length - 1; i++) {
            Point p = points[i-1], q = points[i], r = points[i+1];
            float incoming = (float) Math.hypot(q.x-p.x,q.y-p.y);
            float outgoing = (float) Math.hypot(r.x-q.x,r.y-q.y);
            float corner = Math.min(5,Math.min(incoming,outgoing)/2);
            if (incoming < 1 || outgoing < 1) corner = 0;
            float before = incoming == 0 ? 0 : corner/incoming;
            float after = outgoing == 0 ? 0 : corner/outgoing;
            result[next++] = q.x-(q.x-p.x)*before;
            result[next++] = q.y-(q.y-p.y)*before;
            result[next++] = q.x+(r.x-q.x)*after;
            result[next++] = q.y+(r.y-q.y)*after;
        }
        Point end = points[points.length-1];
        result[next++] = end.x;
        result[next] = end.y;
        return result;
    }

    private SchematicGeometry() {}
}
