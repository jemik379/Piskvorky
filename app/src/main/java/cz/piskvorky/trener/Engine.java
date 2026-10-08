package cz.piskvorky.trener;

import java.util.Arrays;
import java.util.Random;

/**
 * AI: iterativní PVS alfa-beta s transpoziční tabulkou, rozpoznáním vynucených
 * výher (otevřená čtyřka za 3 půltahy), omezením tahů při hrozbě soupeře,
 * redukcí pozdních tahů a hledáním vynucené výhry čtyřkami (VCF).
 * Čistá Java, žádné závislosti na Androidu.
 */
final class Engine {
    static final int WIN = 1_000_000;
    private static final int INF = 2_000_000;
    private static final int MAX_PLY = 64;
    private static final int CAP = 19 * 19;
    private static final int TT_BITS = 20;
    private static final int TT_SIZE = 1 << TT_BITS;

    static final class Result {
        int move = -1;
        int score;          // z pohledu hráče na tahu
        int depth;
        long nodes;
        boolean mate;       // nalezena vynucená výhra / prohra
    }

    /** Výsledek analýzy: nejlepší tahy seřazené podle skóre (z pohledu hráče na tahu). */
    static final class Analysis {
        int[] moves = new int[0];
        int[] scores = new int[0];
        int[][] equiv = new int[0][];   // symetricky rovnocenná pole k jednotlivým tahům
        int depth;
        boolean mate;
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
    private final int[][] fbuf = new int[MAX_PLY + 2][2];
    private final int[][] gbuf = new int[MAX_PLY + 2][2];
    private final int[][] mbuf = new int[MAX_PLY + 2][CAP];
    private final int[][] sbuf = new int[MAX_PLY + 2][CAP];
    private final int[][] vf = new int[24][2];
    private final int[][] vg = new int[24][2];
    private final int[][] vcand = new int[24][CAP];
    private final int[][] vsc = new int[24][CAP];
    private final int[] scratch = new int[CAP];
    private final int[] mark2 = new int[CAP];
    private int stamp2;
    private int vcfMove = -1;

    // VCT (hrozby: čtyřky a otevřené trojky)
    private final int[][] tcand = new int[24][CAP];
    private final int[][] tsc = new int[24][CAP];
    private final int[][] tdef = new int[24][CAP];
    private final int[][] tf1 = new int[24][2];
    private final int[][] tf2 = new int[24][2];
    private int vctBudget;
    private int vctMove = -1;

    private void setup(Board src, long millis) {
        b = src.copy();
        timeUp = false;
        nodes = 0;
        deadline = System.currentTimeMillis() + millis;
        Arrays.fill(tk, 0L);
    }

    /** Hodnocení odehraného tahu: nejlepší tah a skóre vs. skóre odehraného tahu (stejná hloubka). */
    static final class ReviewResult {
        int best = -1;
        int bestScore;
        int playedScore;
        int depth;
        boolean equal = true;
    }

    private static int compactSym(int[] rm, int[] rs, int nr, int[] rep) {
        if (rep == null) return nr;
        int k = 0;
        for (int j = 0; j < nr; j++) {
            if (rep[rm[j]] == rm[j]) { rm[k] = rm[j]; rs[k] = rs[j]; k++; }
        }
        return k;
    }

    /**
     * Ohodnotí odehraný tah (played, nebo -1): nejlepší tah vs. odehraný tah hledaný do stejné
     * hloubky s plným oknem. Symetricky rovnocenné tahy dostanou stejné skóre.
     */
    ReviewResult reviewMove(Board src, long millis, int played) {
        Result r = think(src, millis, 30);
        ReviewResult out = new ReviewResult();
        out.best = r.move;
        out.bestScore = r.score;
        out.playedScore = r.score;
        out.depth = r.depth;
        if (r.move < 0 || played < 0 || played == r.move) return out;
        if (b.c[played] != Board.EMPTY) return out;
        int[] rep = b.symRep();
        if (rep != null && rep[played] == rep[r.move]) return out;
        out.equal = false;
        timeUp = false;
        deadline = System.currentTimeMillis() + Math.max(200, millis * 6 / 10);
        int d = Math.max(r.depth, 3);
        int target = rep != null ? rep[played] : played;   // symetrické tahy se hledají jako jeden a ten samý
        b.place(target);
        int v = b.wins(target) ? WIN - 1 : -search(d - 1, -INF, INF, 1);
        b.undo();
        if (!timeUp) out.playedScore = v;
        return out;
    }

    /** Hlavní vstup: najdi nejlepší tah. */
    Result think(Board src, long millis, int maxDepth) {
        setup(src, millis);
        Result res = new Result();
        int cells = b.n2;
        int stm = b.stm, opp = 3 - stm;
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
        int forcedCell = f[0];

        int[] rm = new int[cells];
        int[] rs = new int[cells];
        int nr = 0;
        boolean restricted = false;
        int[] rep = b.symRep();

        if (no == 0) {
            // otevřená čtyřka / dvojitá čtyřka za 3 půltahy
            if (b.threes(stm) > 0) {
                int nd = b.threatScan(stm, scratch);
                if (b.threatCount > 0) {
                    for (int q = 0; q < nd; q++) {
                        if (b.fourCompletions(scratch[q], stm) >= 2) {
                            res.move = scratch[q];
                            res.score = WIN - 3;
                            res.mate = true;
                            return res;
                        }
                    }
                }
            }
            // vynucená výhra čtyřkami (max. 40 % času)
            if (b.threePlus(stm) > 0) {
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
            // vynucená výhra čtyřkami a trojkami (max. 35 % času)
            if (b.threePlus(stm) > 0 || b.wc[(stm - 1) * 6 + 2] > 0) {
                long full = deadline;
                deadline = System.currentTimeMillis() + millis * 35 / 100;
                vctBudget = 400000;
                boolean win = vctAttack(stm, 8, true);
                deadline = full;
                if (win && vctMove >= 0) {
                    res.move = vctMove;
                    res.score = WIN - 40;
                    res.mate = true;
                    return res;
                }
                timeUp = false;
            }
            if (b.threes(opp) > 0) {
                int nd = b.threatScan(opp, scratch);
                if (b.threatCount > 0) {
                    restricted = true;
                    stamp2++;
                    for (int q = 0; q < nd; q++) {
                        int cell = scratch[q];
                        mark2[cell] = stamp2;
                        rm[nr] = cell;
                        rs[nr] = score(cell, stm, opp);
                        nr++;
                    }
                    if (b.threes(stm) > 0) {
                        for (int i = 0; i < cells; i++) {
                            if (b.c[i] == Board.EMPTY && b.near[i] > 0 && mark2[i] != stamp2
                                    && b.makesFour(i, stm)) {
                                rm[nr] = i;
                                rs[nr] = score(i, stm, opp);
                                nr++;
                            }
                        }
                    }
                }
            }
        }
        if (no == 1) {
            rm[nr++] = forcedCell;
        } else if (!restricted) {
            for (int i = 0; i < cells; i++) {
                if (b.c[i] == Board.EMPTY && b.near[i] > 0) {
                    rm[nr] = i;
                    rs[nr] = score(i, stm, opp);
                    nr++;
                }
            }
            nr = compactSym(rm, rs, nr, rep);
            sortDesc(rm, rs, nr);
            nr = Math.min(nr, 24);
        } else {
            nr = compactSym(rm, rs, nr, rep);
            sortDesc(rm, rs, nr);
        }
        if (nr == 0) return res;
        res.move = rm[0];
        boolean extend = restricted || no == 1;

        for (int depth = 1; depth <= maxDepth; depth++) {
            int alpha = -INF, bestS = -INF, bestM = -1, bestJ = -1;
            for (int j = 0; j < nr; j++) {
                int m = rm[j];
                b.place(m);
                int nd = extend ? depth : depth - 1;
                int v;
                if (j == 0) {
                    v = -search(nd, -INF, INF, 1);
                } else {
                    v = -search(nd, -alpha - 1, -alpha, 1);
                    if (v > alpha && !timeUp) v = -search(nd, -INF, -alpha, 1);
                }
                b.undo();
                if (timeUp) break;
                rs[j] = v;
                if (v > bestS) {
                    bestS = v;
                    bestM = m;
                    bestJ = j;
                }
                if (v > alpha) alpha = v;
            }
            if (timeUp) break;
            res.move = bestM;
            res.score = bestS;
            res.depth = depth;
            res.mate = Math.abs(bestS) > WIN - 200;
            // nejlepší tah dopředu, ostatní podle skóre
            rs[bestJ] = INF;
            sortDesc(rm, rs, nr);
            rs[0] = bestS;
            if (res.mate || nr == 1 && depth >= 3) break;
        }
        res = safeMove(res, rm, nr, millis, stm, opp);
        res.nodes = nodes;
        return res;
    }

    /**
     * Kontrola vybraného tahu: nemá po něm soupeř vynucenou výhru čtyřkami a trojkami?
     * Když ano, zkusí další nejlepší tahy.
     */
    private Result safeMove(Result res, int[] rm, int nr, long millis, int stm, int opp) {
        if (res.mate || res.move < 0) return res;
        long full = deadline;
        boolean oldUp = timeUp;
        timeUp = false;
        deadline = System.currentTimeMillis() + Math.max(80, millis * 15 / 100);
        int tried = 0;
        // pořadí: vybraný tah první, pak ostatní podle posledního pořadí
        int[] order = new int[Math.min(nr, 8) + 1];
        int no = 0;
        order[no++] = res.move;
        for (int j = 0; j < nr && no < order.length; j++) if (rm[j] != res.move) order[no++] = rm[j];
        int chosen = res.move;
        for (int j = 0; j < no && tried < 6; j++) {
            int m = order[j];
            b.place(m);
            boolean lose = false;
            if (!b.wins(m)) {
                vctBudget = 60000;
                lose = vctAttack(opp, 6, false);
            }
            b.undo();
            tried++;
            if (timeUp) break;
            if (!lose) {
                chosen = m;
                break;
            }
            if (j == 0) res.score = Math.min(res.score, -(WIN - 60));
        }
        deadline = full;
        timeUp = oldUp;
        if (chosen != res.move) {
            res.move = chosen;
            res.mate = false;
            res.score = 0;
        }
        return res;
    }

    /**
     * Analýza pozice: pro nejlepší kandidátní tahy spočítá přesné skóre
     * (plné okno), takže jdou porovnat. topK tahů vrací seřazených.
     */
    Analysis analyze(Board src, long millis, int topK) {
        setup(src, millis);
        Analysis out = new Analysis();
        int cells = b.n2;
        int stm = b.stm, opp = 3 - stm;
        int[] f = new int[2];
        if (b.cnt == 0) {
            out.moves = new int[]{(b.n / 2) * b.n + b.n / 2};
            out.scores = new int[]{0};
            return out;
        }
        if (b.fiveCells(stm, f) > 0) {
            out.moves = new int[]{f[0]};
            out.scores = new int[]{WIN - 1};
            out.mate = true;
            return out;
        }
        int no = b.fiveCells(opp, f);
        if (no >= 2) {
            out.moves = new int[]{f[0]};
            out.scores = new int[]{-(WIN - 2)};
            out.mate = true;
            return out;
        }
        int[] rm = new int[cells];
        int[] rs = new int[cells];
        int nr = 0;
        boolean extend = no == 1;
        int[] rep = b.symRep();
        if (no == 1) {
            rm[nr++] = f[0];
        } else {
            if (b.threes(stm) > 0) {
                int nd = b.threatScan(stm, scratch);
                if (b.threatCount > 0) {
                    for (int q = 0; q < nd; q++) {
                        if (b.fourCompletions(scratch[q], stm) >= 2) {
                            out.moves = new int[]{scratch[q]};
                            out.scores = new int[]{WIN - 3};
                            out.mate = true;
                            return out;
                        }
                    }
                }
            }
            if (b.threePlus(stm) > 0) {
                long full = deadline;
                deadline = System.currentTimeMillis() + millis * 3 / 10;
                boolean win = vcf(stm, 16, true);
                deadline = full;
                if (win) {
                    out.moves = new int[]{vcfMove};
                    out.scores = new int[]{WIN - 30};
                    out.mate = true;
                    return out;
                }
                timeUp = false;
            }
            boolean restricted = false;
            if (b.threes(opp) > 0) {
                int nd = b.threatScan(opp, scratch);
                if (b.threatCount > 0) {
                    restricted = true;
                    extend = true;
                    stamp2++;
                    for (int q = 0; q < nd; q++) {
                        mark2[scratch[q]] = stamp2;
                        rm[nr] = scratch[q];
                        rs[nr] = score(scratch[q], stm, opp);
                        nr++;
                    }
                    if (b.threes(stm) > 0) {
                        for (int i = 0; i < cells; i++) {
                            if (b.c[i] == Board.EMPTY && b.near[i] > 0 && mark2[i] != stamp2
                                    && b.makesFour(i, stm)) {
                                rm[nr] = i;
                                rs[nr] = score(i, stm, opp);
                                nr++;
                            }
                        }
                    }
                }
            }
            if (!restricted) {
                for (int i = 0; i < cells; i++) {
                    if (b.c[i] == Board.EMPTY && b.near[i] > 0) {
                        rm[nr] = i;
                        rs[nr] = score(i, stm, opp);
                        nr++;
                    }
                }
            }
            nr = compactSym(rm, rs, nr, rep);
            sortDesc(rm, rs, nr);
            nr = Math.min(nr, 14);
        }
        if (nr == 0) return out;
        int[] sc = new int[nr];
        out.moves = Arrays.copyOf(rm, 1);
        out.scores = new int[]{0};

        for (int depth = 1; depth <= 20; depth++) {
            boolean done = true;
            for (int j = 0; j < nr; j++) {
                b.place(rm[j]);
                int v = -search(extend ? depth : depth - 1, -INF, INF, 1);
                b.undo();
                if (timeUp) {
                    done = false;
                    break;
                }
                sc[j] = v;
            }
            if (!done) break;
            // seřadit podle skóre
            int[] mm = Arrays.copyOf(rm, nr);
            int[] ss = Arrays.copyOf(sc, nr);
            sortDesc(mm, ss, nr);
            int k = Math.min(topK, nr);
            out.moves = Arrays.copyOf(mm, k);
            out.scores = Arrays.copyOf(ss, k);
            out.equiv = new int[k][];
            for (int q = 0; q < k; q++) {
                int cntEq = 0;
                int[] tmpEq = new int[cells];
                if (rep != null) {
                    for (int cc = 0; cc < cells; cc++) {
                        if (cc != out.moves[q] && b.c[cc] == Board.EMPTY && rep[cc] == rep[out.moves[q]]) tmpEq[cntEq++] = cc;
                    }
                }
                out.equiv[q] = Arrays.copyOf(tmpEq, cntEq);
            }
            out.depth = depth;
            out.mate = Math.abs(ss[0]) > WIN - 200;
            System.arraycopy(mm, 0, rm, 0, nr);
            System.arraycopy(ss, 0, sc, 0, nr);
            if (out.mate) break;
        }
        return out;
    }

    private int score(int cell, int stm, int opp) {
        return b.gain(cell, stm) * 11 / 10 + b.gain(cell, opp);
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
        // tempo: nejlepší tah strany na tahu vs. nejlepší tah soupeře (žhavá pole)
        int hs = Math.min(b.maxGain(stm), HOT_CAP), ho = Math.min(b.maxGain(3 - stm), HOT_CAP);
        v += (hs * HOT_ME - ho * HOT_OPP) / 16;
        // poziční výhoda (blízkost středu)
        v += (stm == Board.BLACK ? b.posTotal : -b.posTotal) * POS_W / 4;
        return v + TEMPO;
    }

    static int HOT_CAP = 400, HOT_ME = 6, HOT_OPP = 3, POS_W = 6, TEMPO = 15;

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
        if ((nodes & 1023) == 0 && System.currentTimeMillis() > deadline) timeUp = true;
        if (timeUp) return 0;

        final int stm = b.stm, opp = 3 - stm;
        int[] f = fbuf[ply];
        if (b.fiveCells(stm, f) > 0) return WIN - ply;
        int[] g = gbuf[ply];
        int no = b.fiveCells(opp, g);
        if (no >= 2) return -(WIN - ply - 1);
        if (b.cnt >= b.n2) return 0;
        if (ply >= MAX_PLY - 2) return evalStm(stm);

        final boolean forced = no == 1;
        boolean restricted = false;
        int ndef = 0;
        if (!forced) {
            if (b.threes(stm) > 0) {
                b.threatScan(stm, scratch);
                if (b.threatCount > 0) return WIN - ply - 2;
            }
            if (b.threes(opp) > 0) {
                ndef = b.threatScan(opp, scratch);
                if (b.threatCount > 0) restricted = true;
            }
        }
        final boolean ext = (forced || restricted) && ply < 24;

        if (depth <= 0 && !forced && !restricted) {
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
                int sc0 = fromTT(ts[ti], ply);
                int fl = tf[ti];
                if (fl == 0) return sc0;
                if (fl == 1 && sc0 >= beta) return sc0;
                if (fl == 2 && sc0 <= alpha) return sc0;
            }
        }

        int[] moves = mbuf[ply];
        int[] sc = sbuf[ply];
        int nm = 0;
        int cells = b.n2;
        if (forced) {
            moves[0] = g[0];
            sc[0] = 0;
            nm = 1;
        } else if (restricted) {
            stamp2++;
            for (int q = 0; q < ndef; q++) {
                int cell = scratch[q];
                mark2[cell] = stamp2;
                moves[nm] = cell;
                sc[nm] = score(cell, stm, opp) + (cell == ttMove ? 1_000_000_000 : 0);
                nm++;
            }
            if (b.threes(stm) > 0) {
                for (int i = 0; i < cells; i++) {
                    if (b.c[i] == Board.EMPTY && b.near[i] > 0 && mark2[i] != stamp2
                            && b.makesFour(i, stm)) {
                        moves[nm] = i;
                        sc[nm] = score(i, stm, opp) + (i == ttMove ? 1_000_000_000 : 0);
                        nm++;
                    }
                }
            }
        } else {
            for (int i = 0; i < cells; i++) {
                if (b.c[i] == Board.EMPTY && b.near[i] > 0) {
                    moves[nm] = i;
                    sc[nm] = score(i, stm, opp) + (i == ttMove ? 1_000_000_000 : 0);
                    nm++;
                }
            }
        }
        int width = forced ? 1 : restricted ? 16 : (ply <= 2 ? 12 : (ply <= 5 ? 8 : 6));
        int lim = Math.min(width, nm);

        int best = -INF, bestMove = -1, origAlpha = alpha;
        for (int j = 0; j < lim; j++) {
            if (!forced) {
                int bi = j;
                for (int k = j + 1; k < nm; k++) if (sc[k] > sc[bi]) bi = k;
                int t1 = moves[j]; moves[j] = moves[bi]; moves[bi] = t1;
                int t2 = sc[j]; sc[j] = sc[bi]; sc[bi] = t2;
            }
            int m = moves[j];
            int nd = ext ? depth : depth - 1;
            b.place(m);
            int v;
            if (j == 0) {
                v = -search(nd, -beta, -alpha, ply + 1);
            } else {
                int r = (j >= 4 && nd >= 2 && !ext && sc[j] < 150) ? 1 : 0;
                v = -search(nd - r, -alpha - 1, -alpha, ply + 1);
                if (v > alpha && r > 0 && !timeUp) v = -search(nd, -alpha - 1, -alpha, ply + 1);
                if (v > alpha && v < beta && !timeUp) v = -search(nd, -beta, -alpha, ply + 1);
            }
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
     * Útočník (strana na tahu) hledá vynucenou výhru pomocí čtyřek a otevřených
     * trojek. Obránce odpovídá bloky v oknech hrozby nebo vlastními čtyřkami.
     */
    private boolean vctAttack(int att, int depth, boolean top) {
        if (--vctBudget < 0) return false;
        if ((++nodes & 511) == 0 && System.currentTimeMillis() > deadline) timeUp = true;
        if (timeUp) return false;
        int def = 3 - att;
        int[] fa = tf1[depth];
        if (b.fiveCells(att, fa) > 0) {
            if (top) vctMove = fa[0];
            return true;
        }
        int[] fd = tf2[depth];
        int nd = b.fiveCells(def, fd);
        if (nd >= 2) return false;
        if (nd == 1) {                                  // musím blokovat
            if (depth <= 0) return false;
            int m = fd[0];
            b.place(m);
            boolean r = vctDefend(att, depth - 1);
            b.undo();
            if (r && top) vctMove = m;
            return r;
        }
        if (b.threes(att) > 0) {
            int n0 = b.threatScan(att, scratch);
            if (b.threatCount > 0) {                    // otevřená čtyřka k dispozici
                if (top) {
                    for (int q = 0; q < n0; q++) {
                        if (b.fourCompletions(scratch[q], att) >= 2) {
                            vctMove = scratch[q];
                            break;
                        }
                    }
                }
                return true;
            }
        }
        if (depth <= 0) return false;

        int[] cand = tcand[depth];
        int[] sc = tsc[depth];
        int nc = 0;
        int cells = b.n2;
        for (int i = 0; i < cells; i++) {
            if (b.c[i] == Board.EMPTY && b.near[i] > 0
                    && (b.makesFour(i, att) || b.makesThree(i, att))) {
                cand[nc] = i;
                sc[nc] = b.gain(i, att) + b.gain(i, def) / 2;
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
            int[] fa2 = tf1[depth - 1 >= 0 ? depth - 1 : 0];
            int na = b.fiveCells(att, fa2);
            boolean ok = false;
            if (na >= 2) {
                ok = true;
            } else if (na == 1) {
                ok = vctDefend(att, depth - 1);
            } else if (b.threes(att) > 0) {
                b.threatScan(att, scratch);
                if (b.threatCount > 0) ok = vctDefend(att, depth - 1);
            }
            b.undo();
            if (timeUp) return false;
            if (ok) {
                if (top) vctMove = m;
                return true;
            }
        }
        return false;
    }

    /** Obránce na tahu po hrozbě útočníka. true = útočník vyhrává. */
    private boolean vctDefend(int att, int depth) {
        if (--vctBudget < 0) return false;
        int def = 3 - att;
        int[] fd = tf2[depth];
        if (b.fiveCells(def, fd) > 0) return false;      // obránce dá pětku
        int[] fa = tf1[depth];
        int na = b.fiveCells(att, fa);
        if (na >= 2) return true;
        if (na == 1) {                                   // jediná obrana: blok
            b.place(fa[0]);
            boolean r = vctAttack(att, depth, false);
            b.undo();
            return r;
        }
        if (b.threes(att) == 0) return false;
        int nd = b.threatScan(att, scratch);
        if (b.threatCount == 0) return false;
        int[] opts = tdef[depth];
        int no = 0;
        stamp2++;
        for (int q = 0; q < nd; q++) {
            opts[no++] = scratch[q];
            mark2[scratch[q]] = stamp2;
        }
        if (b.threes(def) > 0) {                         // protiútok čtyřkou
            int cells = b.n2;
            for (int i = 0; i < cells; i++) {
                if (b.c[i] == Board.EMPTY && b.near[i] > 0 && mark2[i] != stamp2 && b.makesFour(i, def)) {
                    opts[no++] = i;
                }
            }
        }
        for (int q = 0; q < no; q++) {
            b.place(opts[q]);
            boolean r = vctAttack(att, depth, false);
            b.undo();
            if (timeUp) return false;
            if (!r) return false;
        }
        return true;
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
        int cells = b.n2;
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
                int[] tmpArr = vf[depth - 1];
                int onf = b.fiveCells(opp, tmpArr);
                if (onf == 0) {
                    res = vcf(color, depth - 1, false);
                } else {
                    res = b.fiveCells(color, new int[2]) > 0;
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
