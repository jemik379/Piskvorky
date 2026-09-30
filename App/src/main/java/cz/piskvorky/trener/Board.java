package cz.piskvorky.trener;

import java.util.Arrays;
import java.util.Random;

/**
 * Hrací deska (výchozí 15x15) s inkrementálním hodnocením vzorů.
 * Čistá Java, žádné závislosti na Androidu (dá se testovat samostatně).
 *
 * Barva kamene na tahu se vždy odvozuje z počtu kamenů: sudý počet = černý,
 * lichý = bílý. Přesně tak fungují i zahájení Swap / Swap2 (B W B W B ...).
 */
final class Board {
    static final int EMPTY = 0, BLACK = 1, WHITE = 2;

    /** Váhy oken o pěti polích podle počtu vlastních kamenů (bez soupeřových). */
    static final int[] W = {0, 1, 8, 60, 600, 100000};

    static final int[] DX = {1, 0, 1, 1};
    static final int[] DY = {0, 1, 1, -1};

    private static final long[] Z = new long[2 * 19 * 19 + 2];
    static {
        Random r = new Random(0x5EED1234L);
        for (int i = 0; i < Z.length; i++) Z[i] = r.nextLong();
    }

    final int n;
    final int[] c;
    final int[] hist;
    int cnt;
    boolean exactFive;      // true = vyhrává jen přesně pět, false = pět a více
    long hash;
    final int[] near;       // kolik kamenů je v okolí 2 pole

    /** Součet hodnot oken: černá minus bílá. */
    int total;
    /** wc[(barva-1)*6 + k] = počet oken s k kameny dané barvy a bez soupeřových. */
    final int[] wc = new int[12];

    private final int[] lineOf;   // [dir * n*n + cell] -> id řady
    private final int[] lx, ly, ldx, ldy, llen;
    private final int[] lval;
    private final int[] lcnt;
    private final int[] tmp;
    private final int[] newCnt = new int[12];

    Board(int n) {
        this.n = n;
        this.c = new int[n * n];
        this.hist = new int[n * n];
        this.near = new int[n * n];
        this.tmp = new int[n + 2];

        int maxLines = 6 * n;
        lx = new int[maxLines]; ly = new int[maxLines];
        ldx = new int[maxLines]; ldy = new int[maxLines]; llen = new int[maxLines];
        lineOf = new int[4 * n * n];
        int id = 0;
        for (int d = 0; d < 4; d++) {
            for (int sy = 0; sy < n; sy++) {
                for (int sx = 0; sx < n; sx++) {
                    int px = sx - DX[d], py = sy - DY[d];
                    boolean prevInside = px >= 0 && py >= 0 && px < n && py < n;
                    if (prevInside) continue;       // není začátek řady
                    int len = 0;
                    int x = sx, y = sy;
                    while (x >= 0 && y >= 0 && x < n && y < n) {
                        lineOf[d * n * n + y * n + x] = id;
                        len++;
                        x += DX[d];
                        y += DY[d];
                    }
                    lx[id] = sx; ly[id] = sy; ldx[id] = DX[d]; ldy[id] = DY[d]; llen[id] = len;
                    id++;
                }
            }
        }
        lval = new int[id];
        lcnt = new int[id * 12];
    }

    int turn() { return (cnt & 1) == 0 ? BLACK : WHITE; }

    int lastMove() { return cnt > 0 ? hist[cnt - 1] : -1; }

    boolean inside(int x, int y) { return x >= 0 && y >= 0 && x < n && y < n; }

    Board copy() {
        Board b = new Board(n);
        b.exactFive = exactFive;
        for (int k = 0; k < cnt; k++) b.place(hist[k]);
        return b;
    }

    void place(int i) {
        int col = turn();
        c[i] = col;
        hist[cnt++] = i;
        hash ^= Z[i * 2 + col - 1];
        int x = i % n, y = i / n;
        for (int dy = -2; dy <= 2; dy++) {
            int yy = y + dy;
            if (yy < 0 || yy >= n) continue;
            for (int dx = -2; dx <= 2; dx++) {
                int xx = x + dx;
                if (xx >= 0 && xx < n) near[yy * n + xx]++;
            }
        }
        for (int d = 0; d < 4; d++) recalc(lineOf[d * n * n + i]);
    }

    void undo() {
        int i = hist[--cnt];
        int col = c[i];
        c[i] = EMPTY;
        hash ^= Z[i * 2 + col - 1];
        int x = i % n, y = i / n;
        for (int dy = -2; dy <= 2; dy++) {
            int yy = y + dy;
            if (yy < 0 || yy >= n) continue;
            for (int dx = -2; dx <= 2; dx++) {
                int xx = x + dx;
                if (xx >= 0 && xx < n) near[yy * n + xx]--;
            }
        }
        for (int d = 0; d < 4; d++) recalc(lineOf[d * n * n + i]);
    }

    private void recalc(int id) {
        int len = llen[id], x = lx[id], y = ly[id], dx = ldx[id], dy = ldy[id];
        for (int i = 0; i < len; i++) tmp[i] = c[(y + dy * i) * n + x + dx * i];
        int val = 0;
        Arrays.fill(newCnt, 0);
        for (int w = 0; w + 5 <= len; w++) {
            int b = 0, wh = 0;
            for (int k = 0; k < 5; k++) {
                int v = tmp[w + k];
                if (v == BLACK) b++; else if (v == WHITE) wh++;
            }
            if (b > 0 && wh == 0) { val += W[b]; newCnt[b]++; }
            else if (wh > 0 && b == 0) { val -= W[wh]; newCnt[6 + wh]++; }
        }
        int base = id * 12;
        for (int j = 0; j < 12; j++) {
            wc[j] += newCnt[j] - lcnt[base + j];
            lcnt[base + j] = newCnt[j];
        }
        total += val - lval[id];
        lval[id] = val;
    }

    private int run(int i, int d, int sgn, int color) {
        int x = i % n + DX[d] * sgn, y = i / n + DY[d] * sgn;
        int dx = DX[d] * sgn, dy = DY[d] * sgn;
        int r = 0;
        while (x >= 0 && y >= 0 && x < n && y < n && c[y * n + x] == color) {
            r++;
            x += dx;
            y += dy;
        }
        return r;
    }

    /** Vyhrál kámen na poli i (podle zvoleného pravidla)? */
    boolean wins(int i) {
        int color = c[i];
        if (color == EMPTY) return false;
        for (int d = 0; d < 4; d++) {
            int len = 1 + run(i, d, 1, color) + run(i, d, -1, color);
            if (exactFive ? len == 5 : len >= 5) return true;
        }
        return false;
    }

    /** Pro UI: {x0, y0, dx, dy, délka} vítězné řady, nebo null. */
    int[] winLine(int i) {
        int color = c[i];
        if (color == EMPTY) return null;
        for (int d = 0; d < 4; d++) {
            int a = run(i, d, -1, color), b = run(i, d, 1, color);
            int len = 1 + a + b;
            if (exactFive ? len == 5 : len >= 5) {
                int x0 = i % n - DX[d] * a, y0 = i / n - DY[d] * a;
                return new int[]{x0, y0, DX[d], DY[d], len};
            }
        }
        return null;
    }

    /** Udělal by kámen barvy color na prázdném poli i pětku? */
    boolean makesFive(int i, int color) {
        for (int d = 0; d < 4; d++) {
            int len = 1 + run(i, d, 1, color) + run(i, d, -1, color);
            if (exactFive ? len == 5 : len >= 5) return true;
        }
        return false;
    }

    /** Najde (nejvýše 2) pole, kde barva color dá pětku. Vrací počet (0, 1, nebo 2). */
    int fiveCells(int color, int[] out) {
        if (wc[(color - 1) * 6 + 4] == 0) return 0;
        int k = 0;
        for (int i = 0; i < n * n; i++) {
            if (c[i] == EMPTY && near[i] > 0 && makesFive(i, color)) {
                out[k++] = i;
                if (k >= 2) break;
            }
        }
        return k;
    }

    /** Je vůbec nějaké okno se třemi a více kameny (šance na čtyřku)? */
    int threePlus(int color) {
        int b = (color - 1) * 6;
        return wc[b + 3] + wc[b + 4];
    }

    /** Přírůstek hodnoty, kdyby barva color zahrála na prázdné pole i. */
    int gain(int i, int color) {
        int opp = 3 - color;
        int x = i % n, y = i / n;
        int g = 0;
        for (int d = 0; d < 4; d++) {
            int dx = DX[d], dy = DY[d];
            for (int s = -4; s <= 0; s++) {
                int sx = x + dx * s, sy = y + dy * s;
                int ex = sx + dx * 4, ey = sy + dy * 4;
                if (sx < 0 || sy < 0 || sx >= n || sy >= n || ex < 0 || ey < 0 || ex >= n || ey >= n) continue;
                int own = 0;
                boolean bad = false;
                for (int k = 0; k < 5; k++) {
                    if (k == -s) continue;
                    int v = c[(sy + dy * k) * n + sx + dx * k];
                    if (v == opp) { bad = true; break; }
                    if (v == color) own++;
                }
                if (!bad) g += W[own + 1] - W[own];
            }
        }
        return g;
    }

    /** Vytvoří tah barvy color na poli i čtyřku (hrozbu pětky)? */
    boolean makesFour(int i, int color) {
        int opp = 3 - color;
        int x = i % n, y = i / n;
        for (int d = 0; d < 4; d++) {
            int dx = DX[d], dy = DY[d];
            for (int s = -4; s <= 0; s++) {
                int sx = x + dx * s, sy = y + dy * s;
                int ex = sx + dx * 4, ey = sy + dy * 4;
                if (sx < 0 || sy < 0 || sx >= n || sy >= n || ex < 0 || ey < 0 || ex >= n || ey >= n) continue;
                int own = 0;
                boolean bad = false;
                for (int k = 0; k < 5; k++) {
                    if (k == -s) continue;
                    int v = c[(sy + dy * k) * n + sx + dx * k];
                    if (v == opp) { bad = true; break; }
                    if (v == color) own++;
                }
                if (!bad && own >= 3) return true;
            }
        }
        return false;
    }
}
