package cz.piskvorky.trener;

import java.util.ArrayList;
import java.util.Random;

/**
 * Hrací deska (výchozí 15x15) s inkrementálním hodnocením vzorů.
 * Čistá Java, žádné závislosti na Androidu (dá se testovat samostatně).
 *
 * Hodnocení se počítá z "oken" o pěti polích (ve 4 směrech). Pro každé okno
 * se drží počet černých a bílých kamenů; okno s kameny jen jedné barvy
 * je potenciální pětka.
 *
 * Barva kamene a strana na tahu jsou explicitní: place(i) položí kámen strany
 * na tahu, placeAs(i, barva) položí kámen libovolné barvy (pro volnou desku).
 */
final class Board {
    static final int EMPTY = 0, BLACK = 1, WHITE = 2;

    /** Váhy oken o pěti polích podle počtu vlastních kamenů (bez soupeřových). */
    static final int[] W = {0, 1, 8, 60, 600, 100000};

    static final int[] DX = {1, 0, 1, 1};
    static final int[] DY = {0, 1, 1, -1};

    private static final long[] Z = new long[2 * 19 * 19 + 2];
    private static final long ZSIDE;
    static {
        Random r = new Random(0x5EED1234L);
        for (int i = 0; i < Z.length; i++) Z[i] = r.nextLong();
        ZSIDE = r.nextLong();
    }

    /** Předpočítaná topologie oken pro danou velikost desky. */
    private static final class Topo {
        final int[][] cw;     // pro každé pole: ids oken, která ho obsahují
        final int[] winCells; // pro okno wid: 5 polí (wid*5 + k)
        final int[] pos;      // poziční bonus pole (blíž ke středu = víc)
        Topo(int n) {
            int n2 = n * n;
            winCells = new int[4 * n2 * 5];
            pos = new int[n2];
            int mid = n / 2;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    pos[y * n + x] = Math.max(0, 7 - Math.max(Math.abs(x - mid), Math.abs(y - mid)));
                }
            }
            ArrayList<Integer>[] lists = new ArrayList[n2];
            for (int i = 0; i < n2; i++) lists[i] = new ArrayList<Integer>();
            for (int d = 0; d < 4; d++) {
                for (int sy = 0; sy < n; sy++) {
                    for (int sx = 0; sx < n; sx++) {
                        int ex = sx + DX[d] * 4, ey = sy + DY[d] * 4;
                        if (ex < 0 || ey < 0 || ex >= n || ey >= n) continue;
                        int wid = d * n2 + sy * n + sx;
                        for (int k = 0; k < 5; k++) {
                            int cell = (sy + DY[d] * k) * n + sx + DX[d] * k;
                            lists[cell].add(wid);
                            winCells[wid * 5 + k] = cell;
                        }
                    }
                }
            }
            cw = new int[n2][];
            for (int i = 0; i < n2; i++) {
                cw[i] = new int[lists[i].size()];
                for (int k = 0; k < cw[i].length; k++) cw[i][k] = lists[i].get(k);
            }
        }
    }

    private static final Topo[] TOPO = new Topo[32];

    private static synchronized Topo topo(int n) {
        if (TOPO[n] == null) TOPO[n] = new Topo(n);
        return TOPO[n];
    }

    final int n;
    final int n2;
    final int[] c;
    final int[] hist;
    final int[] histCol;
    int cnt;
    int stm = BLACK;        // strana na tahu
    boolean exactFive;      // true = vyhrává jen přesně pět, false = pět a více
    long hash;
    final int[] near;       // kolik kamenů je v okolí 2 pole

    /** Součet hodnot oken: černá minus bílá. */
    int total;
    /** wc[(barva-1)*6 + k] = počet oken s k kameny dané barvy a bez soupeřových. */
    final int[] wc = new int[12];
    /** Výstup threatScan: počet polí, která vytvoří otevřenou/dvojitou čtyřku. */
    int threatCount;

    private final int[][] cw;
    private final int[] winCells;
    private final int[] pos;
    /** Přírůstek hodnoty pro černého / bílého, kdyby zahrál na dané pole (inkrementálně). */
    private final int[] gB, gW;
    /** Poziční bonus: černá minus bílá. */
    int posTotal;
    private final byte[] wb, ww;   // počty černých / bílých kamenů v oknech
    private final int[] mark;
    private int stamp;

    Board(int n) {
        this.n = n;
        this.n2 = n * n;
        this.c = new int[n2];
        this.hist = new int[n2];
        this.histCol = new int[n2];
        this.near = new int[n2];
        Topo tp = topo(n);
        this.cw = tp.cw;
        this.winCells = tp.winCells;
        this.pos = tp.pos;
        this.gB = new int[n2];
        this.gW = new int[n2];
        this.wb = new byte[4 * n2];
        this.ww = new byte[4 * n2];
        this.mark = new int[n2];
    }

    int turn() { return stm; }

    int lastMove() { return cnt > 0 ? hist[cnt - 1] : -1; }

    boolean inside(int x, int y) { return x >= 0 && y >= 0 && x < n && y < n; }

    static String name(int cell, int n) {
        return "" + (char) ('A' + cell % n) + (cell / n + 1);
    }

    void setTurn(int col) {
        if (col != stm) {
            hash ^= ZSIDE;
            stm = col;
        }
    }

    Board copy() {
        Board b = new Board(n);
        b.exactFive = exactFive;
        for (int k = 0; k < cnt; k++) b.placeAs(hist[k], histCol[k]);
        b.setTurn(stm);
        return b;
    }

    void place(int i) { placeAs(i, stm); }

    void placeAs(int i, int col) {
        c[i] = col;
        hist[cnt] = i;
        histCol[cnt] = col;
        cnt++;
        hash ^= Z[i * 2 + col - 1];
        posTotal += col == BLACK ? pos[i] : -pos[i];
        setTurn(3 - col);
        int x = i % n, y = i / n;
        for (int dy = -2; dy <= 2; dy++) {
            int yy = y + dy;
            if (yy < 0 || yy >= n) continue;
            for (int dx = -2; dx <= 2; dx++) {
                int xx = x + dx;
                if (xx >= 0 && xx < n) near[yy * n + xx]++;
            }
        }
        int[] ws = cw[i];
        for (int k = 0; k < ws.length; k++) addStone(ws[k], col);
    }

    void undo() {
        cnt--;
        int i = hist[cnt];
        int col = histCol[cnt];
        c[i] = EMPTY;
        hash ^= Z[i * 2 + col - 1];
        posTotal -= col == BLACK ? pos[i] : -pos[i];
        setTurn(col);
        int x = i % n, y = i / n;
        for (int dy = -2; dy <= 2; dy++) {
            int yy = y + dy;
            if (yy < 0 || yy >= n) continue;
            for (int dx = -2; dx <= 2; dx++) {
                int xx = x + dx;
                if (xx >= 0 && xx < n) near[yy * n + xx]--;
            }
        }
        int[] ws = cw[i];
        for (int k = 0; k < ws.length; k++) removeStone(ws[k], col);
    }

    private static int cB(int b, int w) { return (w == 0 && b < 5) ? W[b + 1] - W[b] : 0; }

    private static int cW(int b, int w) { return (b == 0 && w < 5) ? W[w + 1] - W[w] : 0; }

    private void changeWindow(int wid, int nb, int nw) {
        int b = wb[wid], w = ww[wid];
        if (b > 0 && w == 0) { total -= W[b]; wc[b]--; }
        else if (w > 0 && b == 0) { total += W[w]; wc[6 + w]--; }
        if (nb > 0 && nw == 0) { total += W[nb]; wc[nb]++; }
        else if (nw > 0 && nb == 0) { total -= W[nw]; wc[6 + nw]++; }
        int dB = cB(nb, nw) - cB(b, w), dW = cW(nb, nw) - cW(b, w);
        if (dB != 0 || dW != 0) {
            int base = wid * 5;
            for (int k = 0; k < 5; k++) {
                int cell = winCells[base + k];
                gB[cell] += dB;
                gW[cell] += dW;
            }
        }
        wb[wid] = (byte) nb;
        ww[wid] = (byte) nw;
    }

    private void addStone(int wid, int col) {
        if (col == BLACK) changeWindow(wid, wb[wid] + 1, ww[wid]);
        else changeWindow(wid, wb[wid], ww[wid] + 1);
    }

    private void removeStone(int wid, int col) {
        if (col == BLACK) changeWindow(wid, wb[wid] - 1, ww[wid]);
        else changeWindow(wid, wb[wid], ww[wid] - 1);
    }

    private int run(int i, int d, int sgn, int color) {
        int dx = DX[d] * sgn, dy = DY[d] * sgn;
        int x = i % n + dx, y = i / n + dy;
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
                return new int[]{i % n - DX[d] * a, i / n - DY[d] * a, DX[d], DY[d], len};
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

    private byte[] mine(int color) { return color == BLACK ? wb : ww; }

    private byte[] theirs(int color) { return color == BLACK ? ww : wb; }

    /** Prázdné pole v okně wid (kromě pole skip), nebo -1. */
    private int emptyIn(int wid, int skip) {
        int d = wid / n2, s = wid % n2;
        int sx = s % n, sy = s / n;
        for (int k = 0; k < 5; k++) {
            int cell = (sy + DY[d] * k) * n + sx + DX[d] * k;
            if (c[cell] == EMPTY && cell != skip) return cell;
        }
        return -1;
    }

    /** Najde (nejvýše 2) pole, kde barva color dá pětku. Vrací počet (0, 1, nebo 2). */
    int fiveCells(int color, int[] out) {
        if (wc[(color - 1) * 6 + 4] == 0) return 0;
        byte[] m = mine(color), t = theirs(color);
        int k = 0;
        for (int wid = 0; wid < 4 * n2; wid++) {
            if (m[wid] == 4 && t[wid] == 0) {
                int e = emptyIn(wid, -1);
                if (e < 0) continue;
                if (exactFive && !makesFive(e, color)) continue;
                if (k == 1 && out[0] == e) continue;
                out[k++] = e;
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

    /** Počet oken s přesně třemi kameny barvy (a bez soupeřových). */
    int threes(int color) { return wc[(color - 1) * 6 + 3]; }

    /** Přírůstek hodnoty, kdyby barva color zahrála na prázdné pole i. */
    int gain(int i, int color) { return color == BLACK ? gB[i] : gW[i]; }

    /** Nejlepší přírůstek, který může barva color získat jedním tahem (nejžhavější pole). */
    int maxGain(int color) {
        int[] g = color == BLACK ? gB : gW;
        int best = 0;
        for (int i = 0; i < n2; i++) {
            if (c[i] == EMPTY && near[i] > 0 && g[i] > best) best = g[i];
        }
        return best;
    }

    /** Vytvoří tah barvy color na poli i čtyřku (hrozbu pětky)? */
    boolean makesFour(int i, int color) {
        byte[] m = mine(color), t = theirs(color);
        int[] ws = cw[i];
        for (int k = 0; k < ws.length; k++) {
            int wid = ws[k];
            if (t[wid] == 0 && m[wid] >= 3) return true;
        }
        return false;
    }

    /** Vytvoří tah barvy color na poli i trojku (okno se dvěma vlastními kameny a bez soupeřových)? */
    boolean makesThree(int i, int color) {
        byte[] m = mine(color), t = theirs(color);
        int[] ws = cw[i];
        for (int k = 0; k < ws.length; k++) {
            int wid = ws[k];
            if (t[wid] == 0 && m[wid] == 2) return true;
        }
        return false;
    }

    /** Kolik různých polí dá pětku, když barva color zahraje na prázdné pole i (0, 1, 2+)? */
    int fourCompletions(int i, int color) {
        byte[] m = mine(color), t = theirs(color);
        int[] ws = cw[i];
        int first = -1, found = 0;
        for (int k = 0; k < ws.length; k++) {
            int wid = ws[k];
            if (t[wid] == 0 && m[wid] == 3) {
                int e = emptyIn(wid, i);
                if (e < 0) continue;
                if (found == 0) {
                    first = e;
                    found = 1;
                } else if (e != first) {
                    return 2;
                }
            }
        }
        return found;
    }

    /**
     * Prohledá okna s třemi kameny barvy color. Do def zapíše prázdná pole těchto
     * oken (bez duplicit) – to jsou všechna pole, kde se hrozba dá bránit.
     * Do threatCount uloží počet polí, která vytvoří otevřenou / dvojitou čtyřku
     * (tedy vynucenou výhru). Vrací počet polí v def.
     */
    int threatScan(int color, int[] def) {
        byte[] m = mine(color), t = theirs(color);
        int nd = 0;
        stamp++;
        for (int wid = 0; wid < 4 * n2; wid++) {
            if (m[wid] == 3 && t[wid] == 0) {
                int d = wid / n2, s = wid % n2;
                int sx = s % n, sy = s / n;
                for (int k = 0; k < 5; k++) {
                    int cell = (sy + DY[d] * k) * n + sx + DX[d] * k;
                    if (c[cell] == EMPTY && mark[cell] != stamp) {
                        mark[cell] = stamp;
                        def[nd++] = cell;
                    }
                }
            }
        }
        int tc = 0;
        for (int q = 0; q < nd; q++) {
            if (fourCompletions(def[q], color) >= 2) tc++;
        }
        threatCount = tc;
        return nd;
    }
}
