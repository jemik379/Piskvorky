package cz.piskvorky.trener;

/** Společné texty pro hodnocení pozic. */
final class Texts {
    private Texts() {}

    static String colorName(int c) { return c == Board.BLACK ? "černý" : "bílý"; }

    /** Hodnocení z pohledu černého (kladné = výhoda černého). */
    static String evalBlack(int scoreStm, int stm, boolean mate) {
        int bs = stm == Board.BLACK ? scoreStm : -scoreStm;
        if (mate) {
            int plies = Engine.WIN - Math.abs(scoreStm);
            int moves = Math.max(1, (plies + 1) / 2);
            int winner = scoreStm > 0 ? stm : 3 - stm;
            return "Vynucená výhra: " + colorName(winner) + " (asi za " + moves + " tahů).";
        }
        int a = Math.abs(bs);
        String size = a < 25 ? "vyrovnáno" : a < 100 ? "mírná výhoda" : a < 300 ? "výrazná výhoda" : "velká výhoda";
        String who = a < 25 ? "" : (bs > 0 ? " černého" : " bílého");
        return "Černý " + (bs > 0 ? "+" : "") + bs + " – " + size + who + ".";
    }

    /** Krátké skóre tahu z pohledu hráče na tahu, např. +35. */
    static String shortScore(int s) {
        if (Math.abs(s) > Engine.WIN - 200) return s > 0 ? "výhra" : "prohra";
        return (s > 0 ? "+" : "") + s;
    }

    static String cell(int cell, int n) { return Board.name(cell, n); }
}
