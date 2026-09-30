package cz.piskvorky.trener;

import android.content.Context;
import android.content.SharedPreferences;

/** Nastavení hry, ukládá se do SharedPreferences. */
final class Settings {
    static final String[] OPENING = {"Bez swapu", "Swap (klasický)", "Swap2"};
    static final String[] ROLE = {"Zahajuji já (A – kladu 3 kameny)", "Vybírám já (B – volím barvu)", "Náhodně"};
    static final String[] COLOR = {"Černý (začínám)", "Bílý", "Náhodně"};
    static final String[] LEVEL = {"Rychlá (0,3 s)", "Normální (1 s)", "Silná (3 s)", "Maximální (8 s)"};
    static final long[] LEVEL_MS = {300, 1000, 3000, 8000};
    static final String[] RULES = {"Gomoku (pět a více)", "Přesně pět (šestka neplatí)"};

    int opening = 2;
    int role = 2;
    int color = 2;
    int level = 2;
    int rules = 0;
    boolean centerFirst = true;
    boolean numbers = true;

    static Settings load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        Settings s = new Settings();
        s.opening = p.getInt("opening", s.opening);
        s.role = p.getInt("role", s.role);
        s.color = p.getInt("color", s.color);
        s.level = p.getInt("level", s.level);
        s.rules = p.getInt("rules", s.rules);
        s.centerFirst = p.getBoolean("centerFirst", s.centerFirst);
        s.numbers = p.getBoolean("numbers", s.numbers);
        return s;
    }

    void save(Context ctx) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putInt("opening", opening)
                .putInt("role", role)
                .putInt("color", color)
                .putInt("level", level)
                .putInt("rules", rules)
                .putBoolean("centerFirst", centerFirst)
                .putBoolean("numbers", numbers)
                .apply();
    }
}
