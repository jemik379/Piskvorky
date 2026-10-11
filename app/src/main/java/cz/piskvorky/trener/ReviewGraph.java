package cz.piskvorky.trener;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Graf šance černého na výhru; klepnutím se skočí na pozici. */
final class ReviewGraph extends View {
    interface OnPick { void pick(int position); }

    private double[] series = new double[0];
    private int[] cls = new int[0];
    private int cursor;
    private OnPick listener;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    ReviewGraph(Context c) {
        super(c);
        setBackgroundColor(Color.WHITE);
    }

    void setData(double[] s, int[] c, int cur) {
        series = s == null ? new double[0] : s;
        cls = c == null ? new int[0] : c;
        cursor = cur;
        invalidate();
    }

    void setListener(OnPick l) { listener = l; }

    @Override
    protected void onMeasure(int w, int h) {
        int ww = MeasureSpec.getSize(w);
        setMeasuredDimension(ww, Math.round(90 * getResources().getDisplayMetrics().density));
    }

    private float x(int k, int n, float w) { return 6 + (w - 12) * (n <= 1 ? 0 : (float) k / (n - 1)); }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        int n = series.length;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(20, 40, 40, 40));
        cv.drawRect(0, h / 2, w, h, p);
        p.setColor(Color.GRAY);
        p.setStrokeWidth(1);
        cv.drawLine(0, h / 2, w, h / 2, p);
        if (n == 0) return;
        p.setColor(Color.rgb(42, 111, 219));
        p.setStrokeWidth(4);
        for (int k = 1; k < n; k++) {
            cv.drawLine(x(k - 1, n, w), (float) (4 + (h - 8) * (1 - series[k - 1])),
                    x(k, n, w), (float) (4 + (h - 8) * (1 - series[k])), p);
        }
        int[] colors = {0, 0, 0, Color.rgb(230, 161, 0), Color.rgb(224, 112, 26), Color.rgb(214, 40, 40)};
        for (int k = 0; k < cls.length && k + 1 < n; k++) {
            if (cls[k] >= 3) {
                p.setColor(colors[cls[k]]);
                cv.drawCircle(x(k + 1, n, w), (float) (4 + (h - 8) * (1 - series[k + 1])), 7, p);
            }
        }
        p.setColor(Color.rgb(34, 34, 34));
        p.setStrokeWidth(3);
        float cx = x(Math.min(cursor, n - 1), n, w);
        cv.drawLine(cx, 0, cx, h, p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && listener != null && series.length > 1) {
            int n = series.length;
            int k = Math.round((e.getX() - 6) / (getWidth() - 12) * (n - 1));
            listener.pick(Math.max(0, Math.min(n - 1, k)));
        }
        return true;
    }
}
