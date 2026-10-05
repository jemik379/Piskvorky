package cz.piskvorky.trener;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Vlastní view: vykreslí desku, kameny, čísla tahů, nápovědu a vítěznou řadu. */
public class BoardView extends View {
    public interface OnCellTap {
        void onTap(int x, int y);
    }

    private Board board;
    private OnCellTap listener;
    private boolean showNumbers = true;
    private int hint = -1;
    private int[] winLine;
    private int[] marks = new int[0];

    private final Paint bg = new Paint();
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BoardView(Context context) {
        super(context);
        bg.setColor(Color.rgb(222, 184, 100));
        grid.setColor(Color.rgb(90, 70, 30));
        grid.setStyle(Paint.Style.STROKE);
        ring.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        label.setTextAlign(Paint.Align.CENTER);
        label.setColor(Color.rgb(110, 85, 40));
    }

    void setBoard(Board b) {
        board = b;
        hint = -1;
        winLine = null;
        marks = new int[0];
        invalidate();
    }

    void setListener(OnCellTap l) { listener = l; }

    void setShowNumbers(boolean s) { showNumbers = s; invalidate(); }

    void setHint(int cell) { hint = cell; invalidate(); }

    void setWinLine(int[] w) { winLine = w; invalidate(); }

    /** Značky analýzy: pole v pořadí 1., 2., 3. nejlepší tah. */
    void setMarks(int[] cells) { marks = cells == null ? new int[0] : cells; invalidate(); }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, w);
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        if (board == null) return;
        int n = board.n;
        float w = getWidth();
        float step = w / (n + 1);
        cv.drawRect(0, 0, w, w, bg);

        grid.setStrokeWidth(Math.max(1f, step * 0.04f));
        for (int i = 0; i < n; i++) {
            float p = step * (i + 1);
            cv.drawLine(step, p, step * n, p, grid);
            cv.drawLine(p, step, p, step * n, grid);
        }
        // hvězdičky
        if (n == 15) {
            int[] sp = {3, 7, 11};
            grid.setStyle(Paint.Style.FILL);
            for (int a : sp) for (int b : sp) {
                if ((a == 7) != (b == 7) ) continue;
                cv.drawCircle(step * (a + 1), step * (b + 1), step * 0.09f, grid);
            }
            grid.setStyle(Paint.Style.STROKE);
        }
        // souřadnice: písmena nahoře, čísla vlevo
        label.setTextSize(step * 0.4f);
        for (int i = 0; i < n; i++) {
            String letter = String.valueOf((char) ('A' + i));
            cv.drawText(letter, step * (i + 1), step * 0.62f, label);
            cv.drawText(String.valueOf(i + 1), step * 0.45f, step * (i + 1) + step * 0.14f, label);
        }

        float r = step * 0.46f;
        text.setTextSize(step * 0.42f);
        for (int k = 0; k < board.cnt; k++) {
            int cell = board.hist[k];
            int x = cell % n, y = cell / n;
            float cx = step * (x + 1), cy = step * (y + 1);
            boolean black = board.c[cell] == Board.BLACK;
            stone.setColor(black ? Color.rgb(20, 20, 20) : Color.rgb(248, 248, 248));
            cv.drawCircle(cx, cy, r, stone);
            ring.setColor(Color.rgb(60, 60, 60));
            ring.setStrokeWidth(Math.max(1f, step * 0.03f));
            cv.drawCircle(cx, cy, r, ring);
            boolean last = k == board.cnt - 1;
            if (showNumbers || last) {
                text.setColor(last ? Color.rgb(230, 40, 40) : (black ? Color.WHITE : Color.BLACK));
                cv.drawText(String.valueOf(k + 1), cx, cy + step * 0.15f, text);
            }
        }

        if (hint >= 0) {
            float cx = step * (hint % n + 1), cy = step * (hint / n + 1);
            ring.setColor(Color.rgb(20, 160, 60));
            ring.setStrokeWidth(step * 0.12f);
            cv.drawCircle(cx, cy, r * 0.95f, ring);
        }

        int[] rankColors = {Color.rgb(20, 160, 60), Color.rgb(240, 150, 0), Color.rgb(40, 110, 220)};
        for (int k = 0; k < marks.length && k < 3; k++) {
            float cx = step * (marks[k] % n + 1), cy = step * (marks[k] / n + 1);
            stone.setColor(rankColors[k]);
            cv.drawCircle(cx, cy, r * 0.62f, stone);
            text.setColor(Color.WHITE);
            cv.drawText(String.valueOf(k + 1), cx, cy + step * 0.15f, text);
        }

        if (winLine != null) {
            float x0 = step * (winLine[0] + 1), y0 = step * (winLine[1] + 1);
            float x1 = x0 + step * winLine[2] * (winLine[4] - 1);
            float y1 = y0 + step * winLine[3] * (winLine[4] - 1);
            ring.setColor(Color.rgb(230, 40, 40));
            ring.setStrokeWidth(step * 0.14f);
            cv.drawLine(x0, y0, x1, y1, ring);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (board == null) return false;
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float step = getWidth() / (float) (board.n + 1);
            int x = Math.round(e.getX() / step) - 1;
            int y = Math.round(e.getY() / step) - 1;
            if (board.inside(x, y) && listener != null) listener.onTap(x, y);
            return true;
        }
        return super.onTouchEvent(e);
    }
}
