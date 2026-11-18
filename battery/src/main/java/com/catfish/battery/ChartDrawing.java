package com.catfish.battery;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.List;

final class ChartDrawing {
    static Path path(List<ChartGeometry.Vertex> vertices) {
        Path path = new Path();
        ChartGeometry.Vertex first = vertices.get(0);
        path.moveTo(first.x, first.y);
        for (int i = 1; i < vertices.size(); i++) {
            ChartGeometry.Vertex point = vertices.get(i);
            path.lineTo(point.x, point.y);
        }
        return path;
    }

    static void text(Canvas canvas, Paint paint, String label, float x, float y, int color, Paint.Align align) {
        paint.setColor(color);
        paint.setTextAlign(align);
        canvas.drawText(label, x, y, paint);
    }

    static void stroke(Canvas canvas, Paint paint, Path path, int color, float width) {
        paint.setColor(color);
        paint.setStrokeWidth(width);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    static int alpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private ChartDrawing() { }
}
