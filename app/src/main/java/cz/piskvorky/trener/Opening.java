package cz.piskvorky.trener;

import java.util.ArrayList;
import java.util.Random;

/** Pomocné funkce pro zahájení Swap / Swap2 (čistá Java, bez Androidu). */
final class Opening {
    /** Pod tuto hodnotu (v bodech hodnocení) považuje AI pozici za vyváženou. */
    static final int BALANCED = 45;

    private Opening() {}

    /** Hodnocení z pohledu černého (kladné = černý je lepší). */
    static int evalBlack(Engine e, Board b, long ms) {
        Engine.Result r = e.think(b, ms, 8);
        int s = Math.max(-2000, Math.min(2000, r.score));
        return b.turn() == Board.BLACK ? s : -s;
    }

    /** AI jako hráč A: vybere tři kameny (B, W, B), co nejvyváženější. První je uprostřed. */
    static int[] aiOpening(Engine e, int n, boolean exactFive, Random r, int tries, long msEach) {
        int c = (n / 2) * n + n / 2;
        int[] best = null;
        int bestKey = Integer.MAX_VALUE;
        for (int t = 0; t < tries; t++) {
            int w, b2;
            do {
                w = (n / 2 + r.nextInt(5) - 2) * n + (n / 2 + r.nextInt(5) - 2);
            } while (w == c);
            do {
                b2 = (n / 2 + r.nextInt(7) - 3) * n + (n / 2 + r.nextInt(7) - 3);
            } while (b2 == c || b2 == w);
            Board b = new Board(n);
            b.exactFive = exactFive;
            b.place(c);
            b.place(w);
            b.place(b2);
            int key = Math.abs(evalBlack(e, b, msEach)) + r.nextInt(12);
            if (key < bestKey) {
                bestKey = key;
                best = new int[]{c, w, b2};
            }
        }
        return best;
    }

    /**
     * AI jako hráč B ve Swap2 (třetí možnost): přidá bílý a černý kámen tak,
     * aby byla pozice co nejvyváženější. Vrací {bílý, černý}.
     */
    static int[] aiExtend(Engine e, Board three, Random r, int tries, long msEach) {
        ArrayList<Integer> cells = new ArrayList<>();
        for (int i = 0; i < three.n * three.n; i++) {
            if (three.c[i] == Board.EMPTY && three.near[i] > 0) cells.add(i);
        }
        int[] best = null;
        int bestKey = Integer.MAX_VALUE;
        for (int t = 0; t < tries; t++) {
            int w = cells.get(r.nextInt(cells.size()));
            int b2;
            do {
                b2 = cells.get(r.nextInt(cells.size()));
            } while (b2 == w);
            Board b = three.copy();
            b.place(w);
            b.place(b2);
            int key = Math.abs(evalBlack(e, b, msEach)) + r.nextInt(12);
            if (key < bestKey) {
                bestKey = key;
                best = new int[]{w, b2};
            }
        }
        return best;
    }
}
