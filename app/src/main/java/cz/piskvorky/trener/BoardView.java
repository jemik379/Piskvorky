package cz.piskvorky.trener;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * Vlastní view: vykreslí desku, kameny, čísla tahů, nápovědu a vítěznou řadu.
 * Dva vzhledy: 0 = gomoku (kameny na průsečnících), 1 = piškvorky
 * (modré křížky a červená kolečka ve čtvercích).
 */
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
    private int style = 0;

    private final Paint bg = new Paint();
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numRight = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BoardView(Context context) {
        super(context);
        grid.setStyle(Paint.Style.STROKE);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        label.setTextAlign(Paint.Align.CENTER);
        numRight.setTextAlign(Paint.Align.RIGHT);
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

    void setStyle(int s) { style = s; invalidate(); }

    void setHint(int cell) { hint = cell; invalidate(); }

    void setWinLine(int[] w) { winLine = w; invalidate(); }

    /** Značky analýzy: pole v pořadí 1., 2., 3. nejlepší tah. */
    void setMarks(int[] cells) { marks = cells == null ? new int[0] : cells; invalidate(); }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, w);
    }

    private float px(float s, int x) { return style == 1 ? s * (x + 1.5f) : s * (x + 1); }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        if (board == null) return;
        int n = board.n;
        float w = getWidth();
        boolean sq = style == 1;
        float s = sq ? w / (n + 1.1f) : w / (n + 1f);

        if (sq) drawSquares(cv, w, s); else drawGomoku(cv, w, s);

        float r = s * (sq ? 0.4f : 0.46f);
        if (hint >= 0) {
            float cx = px(s, hint % n), cy = px(s, hint / n);
            ring.setColor(Color.rgb(20, 160, 60));
            ring.setStrokeWidth(s * 0.12f);
            if (sq) cv.drawRect(cx - s * 0.44f, cy - s * 0.44f, cx + s * 0.44f, cy + s * 0.44f, ring);
            else cv.drawCircle(cx, cy, r * 0.95f, ring);
        }

        int[] rankColors = {Color.rgb(20, 160, 60), Color.rgb(240, 150, 0), Color.rgb(40, 110, 220)};
        text.setTextSize(s * 0.42f);
        for (int k = 0; k < marks.length && k < 3; k++) {
            float cx = px(s, marks[k] % n), cy = px(s, marks[k] / n);
            stone.setColor(rankColors[k]);
            cv.drawCircle(cx, cy, r * 0.62f, stone);
            text.setColor(Color.WHITE);
            cv.drawText(String.valueOf(k + 1), cx, cy + s * 0.15f, text);
        }

        if (winLine != null) {
            float x0 = px(s, winLine[0]), y0 = px(s, winLine[1]);
            float x1 = x0 + s * winLine[2] * (winLine[4] - 1);
            float y1 = y0 + s * winLine[3] * (winLine[4] - 1);
            ring.setColor(sq ? Color.rgb(42, 157, 58) : Color.rgb(230, 40, 40));
            ring.setStrokeWidth(s * 0.14f);
            cv.drawLine(x0, y0, x1, y1, ring);
        }
    }

    private void drawGomoku(Canvas cv, float w, float step) {
        int n = board.n;
        bg.setColor(Color.rgb(222, 184, 100));
        cv.drawRect(0, 0, w, w, bg);

        grid.setColor(Color.rgb(90, 70, 30));
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(Math.max(1f, step * 0.04f));
        for (int i = 0; i < n; i++) {
            float p = step * (i + 1);
            cv.drawLine(step, p, step * n, p, grid);
            cv.drawLine(p, step, p, step * n, grid);
        }
        if (n == 15) {
            int[] sp = {3, 7, 11};
            grid.setStyle(Paint.Style.FILL);
            for (int a : sp) for (int b : sp) {
                if ((a == 7) != (b == 7)) continue;
                cv.drawCircle(step * (a + 1), step * (b + 1), step * 0.09f, grid);
            }
            grid.setStyle(Paint.Style.STROKE);
        }
        label.setColor(Color.rgb(110, 85, 40));
        label.setTextSize(step * 0.4f);
        for (int i = 0; i < n; i++) {
            cv.drawText(String.valueOf((char) ('A' + i)), step * (i + 1), step * 0.62f, label);
            cv.drawText(String.valueOf(i + 1), step * 0.45f, step * (i + 1) + step * 0.14f, label);
        }

        float r = step * 0.46f;
        text.setTextSize(step * 0.42f);
        ring.setStrokeWidth(Math.max(1f, step * 0.03f));
        for (int k = 0; k < board.cnt; k++) {
            int cell = board.hist[k];
            float cx = step * (cell % n + 1), cy = step * (cell / n + 1);
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
    }

    private void drawSquares(Canvas cv, float w, float s) {
        int n = board.n;
        bg.setColor(Color.rgb(251, 251, 242));
        cv.drawRect(0, 0, w, w, bg);

        int last = board.lastMove();
        if (last >= 0) {
            stone.setColor(Color.rgb(255, 241, 168));
            float lx = s * (last % n + 1), ly = s * (last / n + 1);
            cv.drawRect(lx, ly, lx + s, ly + s, stone);
        }

        grid.setColor(Color.rgb(143, 184, 224));
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(Math.max(1f, s * 0.04f));
        for (int i = 0; i <= n; i++) {
            float p = s * (i + 1);
            cv.drawLine(s, p, s * (n + 1), p, grid);
            cv.drawLine(p, s, p, s * (n + 1), grid);
        }

        label.setColor(Color.rgb(111, 135, 163));
        label.setTextSize(s * 0.4f);
        for (int i = 0; i < n; i++) {
            cv.drawText(String.valueOf((char) ('A' + i)), s * (i + 1.5f), s * 0.72f, label);
            cv.drawText(String.valueOf(i + 1), s * 0.5f, s * (i + 1.5f) + s * 0.14f, label);
        }

        numRight.setTextSize(s * 0.3f);
        numRight.setColor(Color.rgb(85, 85, 102));
        ring.setStrokeWidth(s * 0.13f);
        for (int k = 0; k < board.cnt; k++) {
            int cell = board.hist[k];
            int x = cell % n, y = cell / n;
            float cx = px(s, x), cy = px(s, y);
            ring.setStrokeWidth(s * 0.13f);
            if (board.c[cell] == Board.BLACK) {                 // modrý křížek
                float d = s * 0.29f;
                ring.setColor(Color.rgb(31, 95, 209));
                cv.drawLine(cx - d, cy - d, cx + d, cy + d, ring);
                cv.drawLine(cx - d, cy + d, cx + d, cy - d, ring);
            } else {                                            // červené kolečko
                ring.setColor(Color.rgb(214, 40, 40));
                cv.drawCircle(cx, cy, s * 0.31f, ring);
            }
            if (showNumbers || k == board.cnt - 1) {
                cv.drawText(String.valueOf(k + 1), s * (x + 2) - s * 0.06f, s * (y + 2) - s * 0.09f, numRight);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (board == null) return false;
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() == MotionEvent.ACTION_UP) {
            int n = board.n;
            int x, y;
            if (style == 1) {
                float s = getWidth() / (n + 1.1f);
                x = (int) Math.floor(e.getX() / s - 1);
                y = (int) Math.floor(e.getY() / s - 1);
            } else {
                float step = getWidth() / (float) (n + 1);
                x = Math.round(e.getX() / step) - 1;
                y = Math.round(e.getY() / step) - 1;
            }
            if (board.inside(x, y) && listener != null) listener.onTap(x, y);
            return true;
        }
        return super.onTouchEvent(e);
    }
}
