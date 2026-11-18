package com.catfish.battery;

/** Pack-local positions, including the original margins for the external nodes. */
final class FlowGeometry {
    static final int LEFT = -100, WIDTH = 1390, HEIGHT = 470;
    static final int HV = 0, DC = 1, LV = 2, CHARGER = 3;
    private static final int[][] ROUTES = {
            {-61, 110, -80, 110, -80, 462, 865, 462, 865, 210, 900, 210},
            {865, 341, 900, 341},
            {1258, 341, 1272, 341, 1272, 425, 1258, 425},
            {-60, 425, -70, 425, -70, 201, -64, 201}
    };
    private static final float[][] LENGTHS = new float[ROUTES.length][];

    static {
        for (int route = 0; route < ROUTES.length; route++) {
            int[] points = ROUTES[route];
            float[] lengths = new float[points.length / 2];
            for (int i = 1; i < lengths.length; i++) {
                int p = 2 * i;
                lengths[i] = lengths[i - 1] + (float) Math.hypot(
                        points[p] - points[p - 2], points[p + 1] - points[p - 1]);
            }
            LENGTHS[route] = lengths;
        }
    }

    static float scale(int width, int height) {
        return Math.min(width / (float) WIDTH, height / (float) HEIGHT);
    }

    static float originX(int width, float scale) {
        return (width - WIDTH * scale) / 2 - LEFT * scale;
    }

    static float originY(int height, float scale) {
        return (height - HEIGHT * scale) / 2;
    }

    static boolean plugged(double acHandle, double dcHandle) {
        return (acHandle >= 1 && acHandle <= 3) || (dcHandle >= 1 && dcHandle <= 3);
    }

    static int activeRoutes(double amps, boolean plugged, boolean converterOn) {
        int routes = converterOn ? (1 << DC) | (1 << LV) : 0;
        if (amps > 0.5 && amps <= 1000) routes |= 1 << HV;
        if (amps < -0.5 && amps >= -1000) routes |= 1 << (plugged ? CHARGER : HV);
        return routes;
    }

    static void position(int route, float fraction, float[] xy) {
        int[] points = ROUTES[route];
        float[] lengths = LENGTHS[route];
        float distance = fraction * lengths[lengths.length - 1];
        int segment = 1;
        while (segment < lengths.length - 1 && distance > lengths[segment]) segment++;
        float blend = (distance - lengths[segment - 1]) / (lengths[segment] - lengths[segment - 1]);
        int index = segment * 2;
        xy[0] = points[index - 2] + blend * (points[index] - points[index - 2]);
        xy[1] = points[index - 1] + blend * (points[index + 1] - points[index - 1]);
    }

    private FlowGeometry() { }
}
