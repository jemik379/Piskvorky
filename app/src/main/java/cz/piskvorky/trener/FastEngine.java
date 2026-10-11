package cz.piskvorky.trener;

import java.util.Arrays;

/**
 * Rychlé úzké hledání (volná pravidla, 15×15): okna po pěti polích, seznamy čtyřek a trojek,
 * VCF v listech pro obě strany, K tahů podle zbývající hloubky, sudé prohlubování, tabulka pozic.
 * Hráči 1 a 2; hodnoty jsou z pohledu strany na tahu.
 */
final class FastEngine {
    static final int N = 15, NN = N * N, CENTER = 112;
    static final int[] W = {0, 1, 10, 100, 1000, 100000};
    static final int WIN = 10000000;
    private static final int NEG = -1000000000, POS = 1000000000;
    private static final int[][] DIRS = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};

    private static final int NW;
    private static final int[][] winCells;
    private static final int[][] cellWins;
    private static final short[][] NB2, NB1;
    private static final int[] Z1a = new int[NN], Z1b = new int[NN], Z2a = new int[NN], Z2b = new int[NN];
    private static final int SIDE1, SIDE2;
    private static final int TTN = 1 << 20;
    private static int[] tk1, tk2, tv;
    private static byte[] td, tf;
    private static short[] tm;

    static {
        java.util.ArrayList<int[]> wc = new java.util.ArrayList<int[]>();
        java.util.ArrayList<Integer>[] cw = new java.util.ArrayList[NN];
        for (int i = 0; i < NN; i++) cw[i] = new java.util.ArrayList<Integer>();
        for (int d = 0; d < 4; d++) {
            int dr = DIRS[d][0], dc = DIRS[d][1];
            for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) {
                int er = r + 4 * dr, ec = c + 4 * dc;
                if (er < 0 || er >= N || ec < 0 || ec >= N) continue;
                int[] cells = new int[5];
                for (int k = 0; k < 5; k++) cells[k] = (r + k * dr) * N + c + k * dc;
                int id = wc.size();
                wc.add(cells);
                for (int x : cells) cw[x].add(id);
            }
        }
        NW = wc.size();
        winCells = wc.toArray(new int[0][]);
        cellWins = new int[NN][];
        for (int i = 0; i < NN; i++) {
            cellWins[i] = new int[cw[i].size()];
            for (int q = 0; q < cellWins[i].length; q++) cellWins[i][q] = cw[i].get(q);
        }
        NB2 = new short[NN][];
        NB1 = new short[NN][];
        for (int i = 0; i < NN; i++) {
            int r = i / N, c = i % N;
            java.util.ArrayList<Integer> l2 = new java.util.ArrayList<Integer>(), l1 = new java.util.ArrayList<Integer>();
            for (int dr = -2; dr <= 2; dr++) for (int dc = -2; dc <= 2; dc++) {
                int rr = r + dr, cc = c + dc;
                if (rr < 0 || rr >= N || cc < 0 || cc >= N) continue;
                l2.add(rr * N + cc);
                if (Math.abs(dr) <= 1 && Math.abs(dc) <= 1) l1.add(rr * N + cc);
            }
            NB2[i] = new short[l2.size()];
            for (int q = 0; q < NB2[i].length; q++) NB2[i][q] = (short) (int) l2.get(q);
            NB1[i] = new short[l1.size()];
            for (int q = 0; q < NB1[i].length; q++) NB1[i][q] = (short) (int) l1.get(q);
        }
        java.util.Random zr = new java.util.Random(20261009L);
        for (int i = 0; i < NN; i++) { Z1a[i] = zr.nextInt(); Z1b[i] = zr.nextInt(); Z2a[i] = zr.nextInt(); Z2b[i] = zr.nextInt(); }
        SIDE1 = zr.nextInt();
        SIDE2 = zr.nextInt();
    }

    private static synchronized void ttInit() {
        if (tk1 == null) {
            tk1 = new int[TTN]; tk2 = new int[TTN]; tv = new int[TTN];
            td = new byte[TTN]; tf = new byte[TTN]; tm = new short[TTN];
            Arrays.fill(td, (byte) -1);
            Arrays.fill(tm, (short) -1);
        }
    }

    static final class Config {
        int depth = 16, root = 24, vcf = 6, vcfNodes = 250, rootVcf = 16, near1 = 5;
        boolean ext = true;
        int[] K = {0, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 12, 12, 12};
    }

    static final class Result {
        int move = -1, value, depth;
        long nodes;
        String kind = "";
    }

    // ------------------------------------------------------------------ pozice
    private final byte[] board = new byte[NN];
    private final byte[][] cnt = new byte[3][NW];
    private int S;
    private final int[] nb = new int[NN], nb1 = new int[NN];
    private int stones;
    private final int[] f4 = new int[3], t3 = new int[3];
    private final short[][] lfW = new short[3][NW], lfPos = new short[3][NW];
    private final short[][] ltW = new short[3][NW], ltPos = new short[3][NW];
    private int h1, h2;
    private int vb;
    private final double[] ts = new double[64];
    private final int[] ti = new int[64];
    private long nodes, deadline;
    private boolean abort, abortOK;
    private final int[][] candBuf = new int[80][64];
    private final int[][] vcCand = new int[16][160];
    private final int[][] vcFc = new int[16][8];
    private final int[] tmp8 = new int[16];
    private final int[] fcA = new int[8], fcB = new int[8];

    FastEngine() {
        for (int p = 1; p <= 2; p++) { Arrays.fill(lfPos[p], (short) -1); Arrays.fill(ltPos[p], (short) -1); }
    }

    static FastEngine fromBoard(Board b) {
        FastEngine e = new FastEngine();
        for (int k = 0; k < b.cnt; k++) e.make(b.hist[k], b.histCol[k]);
        return e;
    }

    private void bump(int i, int d) {
        short[] l2 = NB2[i], l1 = NB1[i];
        for (short q : l2) nb[q] += d;
        for (short q : l1) nb1[q] += d;
    }

    private void ladd(short[][] lw, short[][] lp, int[] c, int p, int w) {
        lw[p][c[p]] = (short) w;
        lp[p][w] = (short) c[p];
        c[p]++;
    }

    private void ldel(short[][] lw, short[][] lp, int[] c, int p, int w) {
        int k = lp[p][w];
        int last = lw[p][--c[p]];
        lw[p][k] = (short) last;
        lp[p][last] = (short) k;
        lp[p][w] = -1;
    }

    private boolean make(int i, int who) {
        int o = 3 - who;
        byte[] cn = cnt[who], co = cnt[o];
        int[] ws = cellWins[i];
        boolean won = false;
        int dS = 0;
        board[i] = (byte) who;
        for (int q = 0; q < ws.length; q++) {
            int w = ws[q], a = cn[w], b = co[w];
            int v0 = (a != 0 && b != 0) ? 0 : (a != 0 ? W[a] : (b != 0 ? -W[b] : 0));
            int v1 = b != 0 ? 0 : W[a + 1];
            dS += v1 - v0;
            if (b == 0) {
                if (a == 4) ldel(lfW, lfPos, f4, who, w); else if (a == 3) ldel(ltW, ltPos, t3, who, w);
                if (a == 3) ladd(lfW, lfPos, f4, who, w); else if (a == 2) ladd(ltW, ltPos, t3, who, w);
            } else if (a == 0) {
                if (b == 4) ldel(lfW, lfPos, f4, o, w); else if (b == 3) ldel(ltW, ltPos, t3, o, w);
            }
            cn[w] = (byte) (a + 1);
            if (a == 4) won = true;
        }
        S += (who == 2 ? 1 : -1) * dS;
        stones++;
        bump(i, 1);
        h1 ^= (who == 1 ? Z1a[i] : Z1b[i]);
        h2 ^= (who == 1 ? Z2a[i] : Z2b[i]);
        return won;
    }

    private void unmake(int i, int who) {
        int o = 3 - who;
        byte[] cn = cnt[who], co = cnt[o];
        int[] ws = cellWins[i];
        int dS = 0;
        for (int q = 0; q < ws.length; q++) {
            int w = ws[q], a = cn[w] - 1, b = co[w];
            int v1 = (a != 0 && b != 0) ? 0 : (a != 0 ? W[a] : (b != 0 ? -W[b] : 0));
            int v0 = b != 0 ? 0 : W[a + 1];
            dS += v1 - v0;
            if (b == 0) {
                if (a == 3) ldel(lfW, lfPos, f4, who, w); else if (a == 2) ldel(ltW, ltPos, t3, who, w);
                if (a == 4) ladd(lfW, lfPos, f4, who, w); else if (a == 3) ladd(ltW, ltPos, t3, who, w);
            } else if (a == 0) {
                if (b == 4) ladd(lfW, lfPos, f4, o, w); else if (b == 3) ladd(ltW, ltPos, t3, o, w);
            }
            cn[w] = (byte) a;
        }
        S += (who == 2 ? 1 : -1) * dS;
        board[i] = 0;
        stones--;
        bump(i, -1);
        h1 ^= (who == 1 ? Z1a[i] : Z1b[i]);
        h2 ^= (who == 1 ? Z2a[i] : Z2b[i]);
    }

    /** Pole, kam p zahraje pětku (zapíše do out, max 8), vrací počet. */
    private int fourCells(int p, int[] out) {
        int n = f4[p], k = 0;
        for (int q = 0; q < n && k < out.length; q++) {
            for (int c : winCells[lfW[p][q]]) {
                if (board[c] == 0) {
                    boolean dup = false;
                    for (int z = 0; z < k; z++) if (out[z] == c) { dup = true; break; }
                    if (!dup) out[k++] = c;
                    break;
                }
            }
        }
        return k;
    }

    /** Nejlepších k tahů podle jednoduchého skóre; vrací počet, výsledek v out. */
    private int ordered(int who, int k, int first, int near, int[] out) {
        if (stones == 0) { out[0] = CENTER; return 1; }
        byte[] cw = cnt[who], co = cnt[3 - who];
        int[] nbb = near == 1 ? nb1 : nb;
        int n = 0;
        for (int i = 0; i < NN; i++) {
            if (board[i] != 0 || nbb[i] == 0) continue;
            double s;
            if (i == first) s = 1e12;
            else {
                int att = 0, def = 0;
                int[] ws = cellWins[i];
                for (int q = 0; q < ws.length; q++) {
                    int w = ws[q], a = cw[w], b = co[w];
                    if (b == 0) att += W[a + 1] - W[a];
                    if (a == 0) def += W[b + 1] - W[b];
                }
                s = att + def * 0.95;
            }
            if (n < k) {
                int j = n++;
                while (j > 0 && ts[j - 1] < s) { ts[j] = ts[j - 1]; ti[j] = ti[j - 1]; j--; }
                ts[j] = s; ti[j] = i;
            } else if (s > ts[n - 1]) {
                int j = n - 1;
                while (j > 0 && ts[j - 1] < s) { ts[j] = ts[j - 1]; ti[j] = ti[j - 1]; j--; }
                ts[j] = s; ti[j] = i;
            }
        }
        for (int j = 0; j < n; j++) out[j] = ti[j];
        return n;
    }

    /** Vítězství spojitými čtyřkami; p je na tahu. Vrací první tah nebo -1. */
    private int vcf(int p, int depth) {
        int o = 3 - p;
        if (f4[p] > 0) { fourCells(p, fcB); return fcB[0]; }
        if (depth <= 0 || f4[o] > 0 || t3[p] == 0) return -1;
        if (vb-- <= 0) return -1;
        byte[] cp = cnt[p], co = cnt[o];
        int[] cands = vcCand[depth];
        int nc = 0;
        int nt = t3[p];
        for (int q = 0; q < nt; q++) {
            for (int c : winCells[ltW[p][q]]) {
                if (board[c] == 0) {
                    boolean dup = false;
                    for (int z = 0; z < nc; z++) if (cands[z] == c) { dup = true; break; }
                    if (!dup && nc < cands.length) cands[nc++] = c;
                }
            }
        }
        for (int zi = 0; zi < nc; zi++) {
            int c = cands[zi];
            make(c, p);
            int[] fc = vcFc[depth];
            int nf = 0;
            for (int w : cellWins[c]) {
                if (cp[w] == 4 && co[w] == 0) {
                    for (int q : winCells[w]) {
                        if (board[q] == 0) {
                            boolean dup = false;
                            for (int z = 0; z < nf; z++) if (fc[z] == q) { dup = true; break; }
                            if (!dup && nf < 8) fc[nf++] = q;
                            break;
                        }
                    }
                }
            }
            boolean ok = false;
            if (nf >= 2) ok = true;
            else if (nf == 1) {
                int x = fc[0];
                boolean w2 = make(x, o);
                if (!w2 && vcf(p, depth - 1) >= 0) ok = true;
                unmake(x, o);
            }
            unmake(c, p);
            if (ok) return c;
        }
        return -1;
    }

    private int vcfB(int p, int depth, int budget) { vb = budget; return vcf(p, depth); }

    private int leaf(int who, Config cfg) {
        int o = 3 - who;
        if (cfg.vcf > 0) {
            if (t3[who] > 0 && vcfB(who, cfg.vcf, cfg.vcfNodes) >= 0) return WIN;
            if (t3[o] > 0 && vcfB(o, cfg.vcf, cfg.vcfNodes) >= 0) {
                boolean defended = false;
                int[] buf = candBuf[79];
                int n = ordered(who, 8, -1, 2, buf);
                for (int q = 0; q < n && !defended; q++) {
                    int i = buf[q];
                    make(i, who);
                    boolean bad = t3[o] > 0 && vcfB(o, cfg.vcf, cfg.vcfNodes) >= 0;
                    unmake(i, who);
                    if (!bad) defended = true;
                }
                if (!defended) return -(WIN - 1000);
            }
        }
        return who == 2 ? S : -S;
    }

    private int search(int d, int alpha, int beta, int who, Config cfg, int ply) {
        int o = 3 - who;
        ++nodes;
        if ((nodes & 1023) == 0 && deadline != 0 && System.currentTimeMillis() > deadline && abortOK) { abort = true; return 0; }
        if (f4[who] > 0) return WIN + d;
        int forced = -1;
        if (f4[o] > 0) {
            int nfc = fourCells(o, fcA);
            if (nfc >= 2) return -(WIN + d);
            if (cfg.ext) forced = fcA[0];
        }
        if (forced >= 0 && ply < 60) {
            make(forced, who);
            int v = -search(d, -beta, -alpha, o, cfg, ply + 1);
            unmake(forced, who);
            return abort ? 0 : v;
        }
        if (d <= 0) return leaf(who, cfg);

        int ttMove = -1, idx = 0, k2 = 0, key1 = 0;
        int alpha0 = alpha;
        key1 = h1 ^ (who == 2 ? SIDE1 : 0);
        k2 = h2 ^ (who == 2 ? SIDE2 : 0);
        idx = key1 & (TTN - 1);
        if (td[idx] >= 0 && tk1[idx] == key1 && tk2[idx] == k2) {
            ttMove = tm[idx];
            if (td[idx] >= d) {
                int tvv = tv[idx], f = tf[idx];
                if (f == 0) return tvv;
                if (f == 1 && tvv > alpha) alpha = tvv;
                else if (f == 2 && tvv < beta) beta = tvv;
                if (alpha >= beta) return tvv;
            }
        }
        int kk = cfg.K[Math.min(d, cfg.K.length - 1)];
        int[] cands = candBuf[ply < 78 ? ply : 78];
        int nc = ordered(who, kk, ttMove, d <= cfg.near1 ? 1 : 2, cands);
        if (nc == 0) return 0;
        int best = NEG, bestMove = -1;
        for (int q = 0; q < nc; q++) {
            int i = cands[q];
            make(i, who);
            int v = -search(d - 1, -beta, -alpha, o, cfg, ply + 1);
            unmake(i, who);
            if (abort) return 0;
            if (v > best) { best = v; bestMove = i; }
            if (v > alpha) alpha = v;
            if (alpha >= beta) break;
        }
        tk1[idx] = key1;
        tk2[idx] = k2;
        td[idx] = (byte) d;
        tv[idx] = best;
        tm[idx] = (short) bestMove;
        tf[idx] = (byte) (best <= alpha0 ? 2 : (best >= beta ? 1 : 0));
        return best;
    }

    /** Najde tah pro stranu who; ms = časový limit; zastaví se i při výhře/prohře. */
    Result think(int who, long ms, int maxDepth, Config cfg, java.util.Random rnd) {
        Result res = new Result();
        int o = 3 - who;
        if (stones == 0) { res.move = CENTER; res.kind = "open"; return res; }
        int[] fc = new int[8];
        if (f4[who] > 0) { fourCells(who, fc); res.move = fc[0]; res.value = WIN; res.kind = "five"; return res; }
        if (f4[o] > 0) {
            int n = fourCells(o, fc);
            res.move = fc[0];
            res.value = n >= 2 ? -WIN : 0;
            res.kind = n >= 2 ? "lost" : "forced";
            return res;
        }
        if (cfg.rootVcf > 0 && t3[who] > 0) {
            int m = vcfB(who, cfg.rootVcf, 30000);
            if (m >= 0) { res.move = m; res.value = WIN; res.kind = "vcf"; return res; }
        }
        ttInit();
        Arrays.fill(td, (byte) -1);
        int[] roots = new int[Math.max(cfg.root, 8)];
        int nr = ordered(who, cfg.root, -1, 2, roots);
        deadline = System.currentTimeMillis() + ms;
        nodes = 0;
        abort = false;
        int[] rootVals = new int[nr];
        for (int dd = 2; dd <= maxDepth; dd += 2) {
            abortOK = dd > 4;
            int best = NEG;
            int nv = 0;
            int[] vi = new int[nr], vv = new int[nr];
            for (int q = 0; q < nr; q++) {
                int i = roots[q];
                make(i, who);
                int v = -search(dd - 1, NEG, best == NEG ? POS : (1 - best), o, cfg, 1);
                unmake(i, who);
                if (abort) break;
                vi[nv] = i; vv[nv] = v; nv++;
                if (v > best) best = v;
                if (best >= WIN / 2) break;
            }
            if (abort) break;
            // seřadit podle hodnoty sestupně (stabilně), zbylé kořeny za ně
            Integer[] order = new Integer[nv];
            for (int q = 0; q < nv; q++) order[q] = q;
            final int[] fvv = vv;
            Arrays.sort(order, new java.util.Comparator<Integer>() {
                @Override public int compare(Integer a, Integer b) { return fvv[b] - fvv[a]; }
            });
            int[] nroots = new int[nr];
            int c = 0;
            for (int q = 0; q < nv; q++) nroots[c++] = vi[order[q]];
            for (int q = 0; q < nr; q++) {
                boolean in = false;
                for (int z = 0; z < nv; z++) if (vi[z] == roots[q]) { in = true; break; }
                if (!in) nroots[c++] = roots[q];
            }
            roots = nroots;
            int ties = 0;
            int[] tl = new int[nv];
            for (int q = 0; q < nv; q++) if (vv[q] == best) tl[ties++] = vi[q];
            res.move = tl[rnd.nextInt(Math.max(1, ties))];
            res.value = best;
            res.kind = "search";
            res.depth = dd;
            if (best >= WIN / 2) break;
        }
        abort = false;
        abortOK = false;
        res.nodes = nodes;
        return res;
    }
}
