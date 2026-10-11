package cz.piskvorky.trener;

import java.util.List;

/** Statistika rozboru partie: šance černého na výhru, třídy tahů, přesnost hráčů. */
final class ReviewStats {
    static final double PSCALE = 350;
    static final String[] NAMES = {"nejlepší", "výborný", "dobrý", "nepřesnost", "chyba", "hrubka"};

    final double[] series;            // šance černého na výhru před každým tahem a na konci
    final int[] clsArr;               // třída tahu (0–5), -1 = neposuzováno
    final int[][] counts = new int[3][6];
    final Double[] acc = new Double[3];

    static double winProb(int v) {
        if (v > Engine.WIN - 200) return 1;
        if (v < -Engine.WIN + 200) return 0;
        return 1.0 / (1.0 + Math.exp(-v / PSCALE));
    }

    private static int clamp(int v) { return Math.max(-Engine.WIN, Math.min(Engine.WIN, v)); }

    ReviewStats(int[] score, int[] best, int[] loss, List<int[]> moves, boolean exactFive, int n) {
        int cnt = moves.size();
        series = new double[cnt + 1];
        clsArr = new int[cnt];
        Board bd = new Board(n);
        bd.exactFive = exactFive;
        java.util.ArrayList<Double> a1 = new java.util.ArrayList<Double>(), a2 = new java.util.ArrayList<Double>();
        for (int k = 0; k <= cnt; k++) {
            int stm = k == 0 ? Board.BLACK : 3 - moves.get(k - 1)[1];
            double p;
            if (k >= best.length || best[k] < 0) p = (k > 0 && bd.wins(moves.get(k - 1)[0])) ? 0 : 0.5;
            else p = winProb(clamp(score[k]));
            series[k] = stm == Board.BLACK ? p : 1 - p;
            if (k < cnt) bd.placeAs(moves.get(k)[0], moves.get(k)[1]);
        }
        for (int k = 0; k < cnt; k++) {
            int col = moves.get(k)[1];
            int stm = k == 0 ? Board.BLACK : 3 - moves.get(k - 1)[1];
            if (col != stm || k >= best.length || best[k] < 0) { clsArr[k] = -1; continue; }
            int bs = clamp(score[k]);
            double pB = winProb(bs), pP = winProb(bs - loss[k]);
            double l = Math.max(0, pB - pP);
            int c = (moves.get(k)[0] == best[k] || loss[k] == 0) ? 0 : l < 0.02 ? 1 : l < 0.05 ? 2 : l < 0.10 ? 3 : l < 0.20 ? 4 : 5;
            clsArr[k] = c;
            counts[col][c]++;
            double ac = Math.max(0, Math.min(100, 103.1668 * Math.exp(-0.04354 * l * 100) - 3.1669));
            (col == Board.BLACK ? a1 : a2).add(ac);
        }
        acc[1] = accOf(a1);
        acc[2] = accOf(a2);
    }

    private static Double accOf(java.util.ArrayList<Double> a) {
        if (a.isEmpty()) return null;
        double sum = 0, inv = 0;
        for (double x : a) { sum += x; inv += 1.0 / Math.max(1, x); }
        double mean = sum / a.size(), harm = a.size() / inv;
        return Math.round((mean + harm) / 2 * 10) / 10.0;
    }

    String summary() {
        StringBuilder sb = new StringBuilder();
        for (int col = 1; col <= 2; col++) {
            if (acc[col] == null) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(col == 1 ? "Černý" : "Bílý").append(": přesnost ").append(acc[col]).append(" %");
            StringBuilder bad = new StringBuilder();
            int[] c = counts[col];
            if (c[3] > 0) bad.append(c[3]).append("× nepřesnost");
            if (c[4] > 0) { if (bad.length() > 0) bad.append(", "); bad.append(c[4]).append("× chyba"); }
            if (c[5] > 0) { if (bad.length() > 0) bad.append(", "); bad.append(c[5]).append("× hrubka"); }
            if (bad.length() > 0) sb.append(" (").append(bad).append(")");
        }
        return sb.toString();
    }
}
