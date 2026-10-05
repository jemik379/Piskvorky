package cz.piskvorky.trener;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hrací obrazovka: zahájení (Swap/Swap2), hra proti AI, tipy, zpět. */
public class GameActivity extends Activity {
    private static final int N = 15;
    private static final int CENTER = (N / 2) * N + N / 2;

    private enum Phase { OPEN_HUMAN, OPEN_AI, AI_DECIDE, CHOOSE_HUMAN, PLAY, OVER }

    private Settings set;
    private Board board;
    private final Engine engine = new Engine();
    private final Random rnd = new Random();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private BoardView view;
    private TextView status, info;

    private Phase phase = Phase.OPEN_AI;
    private int toPlace;
    private int humanColor;      // 0 = zatím nezvoleno
    private int playStart;
    private int gen;             // zneplatní výsledky starých vláken
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        set = Settings.load(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(250, 249, 244));

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(dp(10), dp(8), dp(10), dp(4));
        status.setMinLines(2);
        root.addView(status);

        view = new BoardView(this);
        view.setShowNumbers(set.numbers);
        root.addView(view, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        view.setListener(new BoardView.OnCellTap() {
            @Override
            public void onTap(int x, int y) {
                onCell(y * N + x);
            }
        });

        info = new TextView(this);
        info.setTextSize(14);
        info.setPadding(dp(10), dp(4), dp(10), dp(4));
        info.setMinLines(2);
        root.addView(info);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("Zpět", new View.OnClickListener() {
            public void onClick(View v) { undo(); }
        }));
        row.addView(button("Tip", new View.OnClickListener() {
            public void onClick(View v) { hint(); }
        }));
        row.addView(button("Rozbor", new View.OnClickListener() {
            public void onClick(View v) { openReview(); }
        }));
        row.addView(button("Nová", new View.OnClickListener() {
            public void onClick(View v) { newGame(); }
        }));
        row.addView(button("Menu", new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        }));
        root.addView(row);

        setContentView(root);
        if (getIntent().getIntArrayExtra("stones") != null) startFromPosition(); else newGame();
    }

    @Override
    protected void onDestroy() {
        gen++;
        pool.shutdownNow();
        super.onDestroy();
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String colorName(int c) { return c == Board.BLACK ? "černý" : "bílý"; }

    // ---------------------------------------------------------------- start z pozice, rozbor

    /** Spuštění z volné desky: pozice, strana na tahu a barva hráče přijdou v Intentu. */
    private void startFromPosition() {
        gen++;
        busy = false;
        int[] stones = getIntent().getIntArrayExtra("stones");
        board = new Board(N);
        board.exactFive = set.rules == 1;
        for (int code : stones) board.placeAs(code / 4, code % 4);
        board.setTurn(getIntent().getIntExtra("stm", Board.BLACK));
        humanColor = getIntent().getIntExtra("human", Board.BLACK);
        view.setBoard(board);
        info.setText("");
        startPlay();
    }

    /** Otevře volnou desku s touto partií a spustí rozbor. */
    private void openReview() {
        if (board.cnt == 0) return;
        int[] stones = new int[board.cnt];
        for (int k = 0; k < board.cnt; k++) stones[k] = board.hist[k] * 4 + board.histCol[k];
        android.content.Intent it = new android.content.Intent(this, LabActivity.class);
        it.putExtra("stones", stones);
        it.putExtra("review", true);
        startActivity(it);
    }

    // ---------------------------------------------------------------- nová hra

    private void newGame() {
        gen++;
        busy = false;
        board = new Board(N);
        board.exactFive = set.rules == 1;
        view.setBoard(board);
        humanColor = 0;
        info.setText("");

        if (set.opening == 0) {
            int pref = set.color == 2 ? (rnd.nextBoolean() ? 0 : 1) : set.color;
            humanColor = pref == 0 ? Board.BLACK : Board.WHITE;
            startPlay();
            return;
        }
        boolean humanIsA = set.role == 0 || (set.role == 2 && rnd.nextBoolean());
        if (humanIsA) {
            phase = Phase.OPEN_HUMAN;
            toPlace = 3;
            if (set.centerFirst) {
                board.place(CENTER);
                toPlace = 2;
            }
            view.invalidate();
            refreshStatus();
        } else {
            phase = Phase.OPEN_AI;
            status.setText("Soupeř (A) vybírá tři kameny zahájení…");
            final int g = gen;
            final boolean exact = board.exactFive;
            pool.execute(new Runnable() {
                public void run() {
                    final int[] st = Opening.aiOpening(engine, N, exact, rnd, 14, 120);
                    ui.post(new Runnable() {
                        public void run() {
                            if (g != gen) return;
                            for (int cell : st) board.place(cell);
                            view.invalidate();
                            askHumanChoice();
                        }
                    });
                }
            });
        }
    }

    private void refreshStatus() {
        switch (phase) {
            case OPEN_HUMAN: {
                int next = board.cnt;   // 0 = 1. kámen
                String[] who = {"černý", "bílý", "černý", "bílý", "černý"};
                String msg = "Zahájení: polož kámen č. " + (next + 1) + " (" + who[Math.min(next, 4)] + "). "
                        + "Zbývá: " + toPlace + ".";
                if (next == 0 && set.centerFirst) msg += " První kámen uprostřed.";
                status.setText(msg);
                break;
            }
            case PLAY: {
                String you = "Ty: " + colorName(humanColor) + ". ";
                status.setText(you + (board.turn() == humanColor ? "Jsi na tahu." : "AI přemýšlí…"));
                break;
            }
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- tahy

    private void onCell(int cell) {
        if (board.c[cell] != Board.EMPTY || busy) return;
        if (phase == Phase.OPEN_HUMAN) {
            if (board.cnt == 0 && set.centerFirst && cell != CENTER) {
                Toast.makeText(this, "První kámen musí být uprostřed.", Toast.LENGTH_SHORT).show();
                return;
            }
            board.place(cell);
            toPlace--;
            view.invalidate();
            if (toPlace == 0) afterHumanOpening(); else refreshStatus();
        } else if (phase == Phase.PLAY && board.turn() == humanColor) {
            view.setHint(-1);
            info.setText("");
            board.place(cell);
            view.invalidate();
            if (!checkEnd(cell)) {
                refreshStatus();
                maybeAiMove();
            }
        }
    }

    private boolean checkEnd(int cell) {
        if (board.wins(cell)) {
            phase = Phase.OVER;
            view.setWinLine(board.winLine(cell));
            boolean me = board.c[cell] == humanColor;
            status.setText(me ? "Vyhrál jsi! 🎉" : "AI vyhrála. Zkus to znovu nebo si vyžádej Tip.");
            return true;
        }
        if (board.cnt >= N * N) {
            phase = Phase.OVER;
            status.setText("Remíza – deska je plná.");
            return true;
        }
        return false;
    }

    private void startPlay() {
        phase = Phase.PLAY;
        playStart = board.cnt;
        view.invalidate();
        refreshStatus();
        maybeAiMove();
    }

    private void maybeAiMove() {
        if (phase != Phase.PLAY || board.turn() == humanColor || busy) return;
        busy = true;
        status.setText("Ty: " + colorName(humanColor) + ". AI přemýšlí…");
        final int g = gen;
        final Board snap = board.copy();
        final long ms = Settings.LEVEL_MS[set.level];
        pool.execute(new Runnable() {
            public void run() {
                final Engine.Result r = engine.think(snap, ms, 30);
                ui.post(new Runnable() {
                    public void run() {
                        if (g != gen) return;
                        busy = false;
                        if (r.move < 0 || board.c[r.move] != Board.EMPTY) return;
                        board.place(r.move);
                        view.invalidate();
                        if (!checkEnd(r.move)) refreshStatus();
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- swap

    /** Člověk dokončil kladení kamenů (3 jako A, nebo 2 při rozšíření ve Swap2). */
    private void afterHumanOpening() {
        phase = Phase.AI_DECIDE;
        status.setText("Soupeř (B) se rozhoduje…");
        final int g = gen;
        final Board snap = board.copy();
        final boolean three = board.cnt == 3;
        final boolean swap2 = set.opening == 2;
        pool.execute(new Runnable() {
            public void run() {
                final int s = Opening.evalBlack(engine, snap, 1500);
                int choice;        // 0 = AI černý, 1 = AI bílý, 2 = AI přidá 2 kameny
                if (!three || !swap2) {
                    choice = s >= 0 ? 0 : 1;
                } else if (s > Opening.BALANCED) {
                    choice = 0;
                } else if (s < -Opening.BALANCED) {
                    choice = 1;
                } else {
                    choice = 2;
                }
                final int[] extra = choice == 2 ? Opening.aiExtend(engine, snap, rnd, 14, 120) : null;
                final int fc = choice;
                ui.post(new Runnable() {
                    public void run() {
                        if (g != gen) return;
                        if (fc == 2) {
                            board.place(extra[0]);
                            board.place(extra[1]);
                            view.invalidate();
                            askHumanChoice();
                        } else {
                            int aiColor = fc == 0 ? Board.BLACK : Board.WHITE;
                            humanColor = 3 - aiColor;
                            String how = three && swap2
                                    ? (fc == 0 ? "Soupeř bere černého." : "Soupeř bere bílého a hraje 4. kámen.")
                                    : "Soupeř si vybral barvu: " + colorName(aiColor) + ".";
                            Toast.makeText(GameActivity.this, how + " Ty jsi " + colorName(humanColor) + ".",
                                    Toast.LENGTH_LONG).show();
                            startPlay();
                        }
                    }
                });
            }
        });
    }

    /** Člověk volí barvu (po 3 kamenech AI, nebo po 5 kamenech když AI rozšířila). */
    private void askHumanChoice() {
        phase = Phase.CHOOSE_HUMAN;
        status.setText("Vyber si barvu nebo možnost.");
        final boolean canExtend = set.opening == 2 && board.cnt == 3;
        String[] items = canExtend
                ? new String[]{"Hrát černého", "Hrát bílého", "Přidat 2 kameny (bílý + černý)"}
                : new String[]{"Hrát černého", "Hrát bílého"};
        new AlertDialog.Builder(this)
                .setTitle(board.cnt == 3 ? "Soupeř položil 3 kameny" : "Soupeř přidal 2 kameny")
                .setCancelable(false)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            humanColor = Board.BLACK;
                            startPlay();
                        } else if (which == 1) {
                            humanColor = Board.WHITE;
                            startPlay();
                        } else {
                            phase = Phase.OPEN_HUMAN;
                            toPlace = 2;
                            refreshStatus();
                            status.setText("Polož 2 kameny: nejdřív bílý, pak černý. Potom soupeř zvolí barvu.");
                        }
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- zpět a tip

    private void undo() {
        if (phase != Phase.PLAY && phase != Phase.OVER) return;
        if (board.cnt <= playStart) return;
        gen++;
        busy = false;
        board.undo();
        while (board.cnt > playStart && board.turn() != humanColor) board.undo();
        phase = Phase.PLAY;
        view.setWinLine(null);
        view.setHint(-1);
        info.setText("");
        view.invalidate();
        refreshStatus();
        maybeAiMove();
    }

    private void hint() {
        if (phase != Phase.PLAY || busy || board.turn() != humanColor) return;
        busy = true;
        info.setText("Analyzuji pozici…");
        final int g = gen;
        final Board snap = board.copy();
        final int stm = board.turn();
        final long ms = Math.max(3000, Settings.LEVEL_MS[set.level]);
        pool.execute(new Runnable() {
            public void run() {
                final Engine.Result r = engine.think(snap, ms, 30);
                ui.post(new Runnable() {
                    public void run() {
                        if (g != gen) return;
                        busy = false;
                        if (r.move < 0) return;
                        view.setHint(r.move);
                        char col = (char) ('A' + r.move % N);
                        info.setText("Tip: " + col + (r.move / N + 1) + "  (hloubka " + r.depth + ")\n"
                                + describe(r.score, stm, r.mate));
                    }
                });
            }
        });
    }

    /** Slovní hodnocení z pohledu hráče, který je na tahu (tedy člověka). */
    private String describe(int score, int stm, boolean mate) {
        if (mate) {
            int moves = (Engine.WIN - Math.abs(score) + 1) / 2;
            return score > 0 ? "Máš vynucenou výhru (asi za " + Math.max(1, moves) + " tahů)."
                    : "Pozor: soupeř má vynucenou výhru, hledej obranu.";
        }
        int a = Math.abs(score);
        String size = a < 25 ? "vyrovnáno" : a < 100 ? "mírná výhoda" : a < 300 ? "výrazná výhoda" : "velká výhoda";
        String who = a < 25 ? "" : (score > 0 ? " pro tebe" : " pro soupeře");
        return "Hodnocení: " + size + who + " (" + (score > 0 ? "+" : "") + score + ").";
    }
}
