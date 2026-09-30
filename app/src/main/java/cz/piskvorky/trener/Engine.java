package cz.piskvorky.trener;

import java.util.Arrays;
import java.util.Random;

/**
 * AI: iterativní alfa-beta (negamax) s transpoziční tabulkou, řazením tahů podle
 * vzorů, vynucenými odpověďmi na čtyřky a hledáním vynucené výhry čtyřkami (VCF).
 * Čistá Java, žádné závislosti na Androidu.
 */
final class Engine {
    static final int WIN = 1_000_000;
    private static final int INF = 2_000_000;
    private static final int MAX_PLY = 64;
    private static final int TT_BITS = 18;
    private static final int TT_SIZE = 1 << TT_BITS;

    static final class Result {
        int move = -1;
        int score;          // z pohledu hráče na tahu
        int depth;
        long nodes;
        boolean mate;       // nalezena vynucená výhra / prohra
    }

    private Board b;
    private long deadline;
    private boolean timeUp;
    private long nodes;
    private int leafVcfDepth = 4;
    private final Random rnd = new Random();

    // transpoziční tabulka
    private final long[] tk = new long[TT_SIZE];
    private final int[] ts = new int[TT_SIZE];
    private final byte[] td = new byte[TT_SIZE];
    private final byte[] tf = new byte[TT_SIZE];
    private final short[] tm = new short[TT_SIZE];

    // pracovní pole podle hloubky
    private int[][] fbuf, gbuf, mbuf, sbuf;
    private int[][] vf, vg, vcand, vsc;
    private int vcfMove = -1;

    Result think(Board src, long millis, int maxDepth) {
        b = src.copy();
        int cells = b.n * b.n;
        timeUp = false;
        nodes = 0;
        deadline = System.currentTimeMillis() + millis;
        Arrays.fill(tk, 0L);
        fbuf = new int[MAX_PLY + 2][2];
        gbuf = new int[MAX_PLY + 2][2];
        mbuf = new int[MAX_PLY + 2][cells];
        sbuf = new int[MAX_PLY + 2][cells];
        vf = new int[24][2];
        vg = new int[24][2];
        vcand = new int[24][cells];
        vsc = new int[24][cells];

        Result res = new Result();
        int stm = b.turn(), opp = 3 - stm;
        if (b.cnt == 0) {
            res.move = (b.n / 2) * b.n + b.n / 2;
            return res;
        }
        int[] f = new int[2];
        if (b.fiveCells(stm, f) > 0) {
            res.move = f[0];
            res.score = WIN - 1;
            res.mate = true;
            return res;
        }
        int no = b.fiveCells(opp, f);
        if (no >= 2) {                      // prohráno, aspoň zablokuj jedno
            res.move = f[0];
            res.score = -(WIN - 2);
            res.mate = true;
            return res;
        }

        // vynucená výhra čtyřkami (max. 40 % času)
        if (no == 0 && b.threePlus(stm) > 0) {
            long full = deadline;
            deadline = System.currentTimeMillis() + millis * 4 / 10;
            boolean win = vcf(stm, 16, true);
            deadline = full;
            if (win) {
                res.move = vcfMove;
                res.score = WIN - 30;
                res.mate = true;
                return res;
            }
            timeUp = false;
        }

        // kořenové tahy
        int[] rm = new int[cells];
        int[] rs = new int[cells];
        int nr = 0;
        if (no == 1) {
            rm[nr++] = f[0];
        } else {
            for (int i = 0; i < cells; i++) {
                if (b.c[i] == Board.EMPTY && b.near[i] > 0) {
                    rm[nr] = i;
                    rs[nr] = b.gain(i, stm) * 11 / 10 + b.gain(i, opp);
                    nr++;
                }
            }
            sortDesc(rm, rs, nr);
            nr = Math.min(nr, 24);
        }
        if (nr == 0) return res;
        res.move = rm[0];

        for (int depth = 1; depth <= maxDepth; depth++) {
            int alpha = -INF, bestS = -INF, bestM = -1;
            for (int j = 0; j < nr; j++) {
                int m = rm[j];
                b.place(m);
                int v = -search(depth - 1, -INF, -alpha, 1);
                b.undo();
                if (timeUp) break;
                rs[j] = v;
                if (v > bestS || (v == bestS && rnd.nextInt(3) == 0)) {
                    bestS = v;
                    bestM = m;
                }
                if (v > alpha) alpha = v;
            }
            if (timeUp) break;
            res.move = bestM;
            res.score = bestS;
            res.depth = depth;
            res.mate = Math.abs(bestS) > WIN - 200;
            sortDesc(rm, rs, nr);
            if (res.mate) break;
        }
        res.nodes = nodes;
        return res;
    }

    private static void sortDesc(int[] m, int[] s, int n) {
        for (int i = 1; i < n; i++) {
            int mm = m[i], ss = s[i], j = i - 1;
            while (j >= 0 && s[j] < ss) {
                m[j + 1] = m[j];
                s[j + 1] = s[j];
                j--;
            }
            m[j + 1] = mm;
            s[j + 1] = ss;
        }
    }

    private int evalStm(int stm) {
        int[] wc = b.wc;
        int me = (stm - 1) * 6, op = (2 - stm) * 6;
        int v = wc[me + 1] + 8 * wc[me + 2] + 80 * wc[me + 3]
                - (wc[op + 1] + 8 * wc[op + 2] + 50 * wc[op + 3]);
        return v + 10;
    }

    private static int toTT(int s, int ply) {
        if (s > WIN - 1000) return s + ply;
        if (s < -WIN + 1000) return s - ply;
        return s;
    }

    private static int fromTT(int s, int ply) {
        if (s > WIN - 1000) return s - ply;
        if (s < -WIN + 1000) return s + ply;
        return s;
    }

    private int search(int depth, int alpha, int beta, int ply) {
        nodes++;
        if ((nodes & 2047) == 0 && System.currentTimeMillis() > deadline) timeUp = true;
        if (timeUp) return 0;

        int stm = b.turn(), opp = 3 - stm;
        int[] f = fbuf[ply];
        if (b.fiveCells(stm, f) > 0) return WIN - ply;
        int[] g = gbuf[ply];
        int no = b.fiveCells(opp, g);
        if (no >= 2) return -(WIN - ply - 1);
        if (b.cnt >= b.n * b.n) return 0;
        boolean forced = no == 1;
        if (ply >= MAX_PLY - 1) return evalStm(stm);

        if (depth <= 0 && !forced) {
            if (leafVcfDepth > 0 && b.threePlus(stm) > 0 && vcf(stm, leafVcfDepth, false)) {
                return timeUp ? 0 : WIN - ply - 2;
            }
            return evalStm(stm);
        }

        long h = b.hash;
        int ti = (int) (h ^ (h >>> 32)) & (TT_SIZE - 1);
        int ttMove = -1;
        if (tk[ti] == h) {
            ttMove = tm[ti];
            if (td[ti] >= depth) {
                int sc = fromTT(ts[ti], ply);
                int fl = tf[ti];
                if (fl == 0) return sc;
                if (fl == 1 && sc >= beta) return sc;
                if (fl == 2 && sc <= alpha) return sc;
            }
        }

        int[] moves = mbuf[ply];
        int[] sc = sbuf[ply];
        int nm = 0;
        if (forced) {
            moves[nm] = g[0];
            nm = 1;
        } else {
            int cells = b.n * b.n;
            for (int i = 0; i < cells; i++) {
                if (b.c[i] == Board.EMPTY && b.near[i] > 0) {
                    int s = b.gain(i, stm) * 11 / 10 + b.gain(i, opp);
                    if (i == ttMove) s += 1_000_000_000;
                    moves[nm] = i;
                    sc[nm] = s;
                    nm++;
                }
            }
        }
        int width = forced ? 1 : (ply <= 2 ? 12 : (ply <= 5 ? 8 : 6));
        int lim = Math.min(width, nm);

        int best = -INF, bestMove = -1, origAlpha = alpha;
        for (int j = 0; j < lim; j++) {
            if (!forced) {
                int bi = j;
                for (int k = j + 1; k < nm; k++) if (sc[k] > sc[bi]) bi = k;
                int tm1 = moves[j]; moves[j] = moves[bi]; moves[bi] = tm1;
                int ts1 = sc[j]; sc[j] = sc[bi]; sc[bi] = ts1;
            }
            int m = moves[j];
            b.place(m);
            int v = -search(depth - 1, -beta, -alpha, ply + 1);
            b.undo();
            if (timeUp) return 0;
            if (v > best) {
                best = v;
                bestMove = m;
            }
            if (v > alpha) alpha = v;
            if (alpha >= beta) break;
        }

        tk[ti] = h;
        ts[ti] = toTT(best, ply);
        td[ti] = (byte) Math.max(depth, 0);
        tf[ti] = (byte) (best <= origAlpha ? 2 : (best >= beta ? 1 : 0));
        tm[ti] = (short) bestMove;
        return best;
    }

    /**
     * Hledá vynucenou výhru samými čtyřkami (soupeř musí pokaždé blokovat).
     * Předpokládá, že soupeř nemá hned pětku.
     */
    private boolean vcf(int color, int depth, boolean top) {
        if (depth <= 0) return false;
        nodes++;
        if ((nodes & 511) == 0 && System.currentTimeMillis() > deadline) timeUp = true;
        if (timeUp) return false;

        int opp = 3 - color;
        int[] f = vf[depth];
        if (b.fiveCells(color, f) > 0) {
            if (top) vcfMove = f[0];
            return true;
        }
        if (b.threePlus(color) == 0) return false;

        int[] cand = vcand[depth];
        int[] sc = vsc[depth];
        int nc = 0;
        int cells = b.n * b.n;
        for (int i = 0; i < cells; i++) {
            if (b.c[i] == Board.EMPTY && b.near[i] > 0 && b.makesFour(i, color)) {
                cand[nc] = i;
                sc[nc] = b.gain(i, color) + b.gain(i, opp) / 2;
                nc++;
            }
        }
        for (int j = 0; j < nc; j++) {
            int bi = j;
            for (int k = j + 1; k < nc; k++) if (sc[k] > sc[bi]) bi = k;
            int t1 = cand[j]; cand[j] = cand[bi]; cand[bi] = t1;
            int t2 = sc[j]; sc[j] = sc[bi]; sc[bi] = t2;

            int m = cand[j];
            b.place(m);
            int[] fw = vg[depth];
            int nf = b.fiveCells(color, fw);
            boolean res = false;
            if (nf >= 2) {
                res = true;
            } else if (nf == 1) {
                int w = fw[0];
                b.place(w);                      // soupeř musí blokovat
                int[] tmpArr = vf[depth - 1 >= 0 ? depth - 1 : 0];
                int onf = b.fiveCells(opp, tmpArr);
                if (onf == 0) {
                    res = vcf(color, depth - 1, false);
                } else {
                    int[] tmp2 = new int[2];
                    res = b.fiveCells(color, tmp2) > 0;
                }
                b.undo();
            }
            b.undo();
            if (timeUp) return false;
            if (res) {
                if (top) vcfMove = m;
                return true;
            }
        }
        return false;
    }
}
