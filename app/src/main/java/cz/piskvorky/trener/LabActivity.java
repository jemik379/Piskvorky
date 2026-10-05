package cz.piskvorky.trener;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Volná deska a analýza: libovolné pokládání kamenů, kroky vpřed/vzad,
 * mazání, analýza pozice (3 nejlepší tahy), tah AI, rozbor celé partie
 * a možnost dohrát pozici proti AI.
 */
public class LabActivity extends Activity {
    private static final int N = 15;
    private static final int TOOL_ALT = 0, TOOL_BLACK = 1, TOOL_WHITE = 2, TOOL_ERASE = 3;
    private static final String[] TOOL_NAMES = {"Střídat", "Černý", "Bílý", "Guma"};

    private Settings set;
    private Board board;
    private final Engine engine = new Engine();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private BoardView view;
    private TextView status, info;
    private final Button[] toolButtons = new Button[4];
    private Button stmButton;

    /** Kameny: {pole, barva}. Zobrazeno je prvních `cursor`. */
    private final ArrayList<int[]> moves = new ArrayList<int[]>();
    private int cursor;
    private int forcedStm;           // 0 = automaticky podle posledního kamene
    private int tool = TOOL_ALT;
    private int gen;
    private boolean busy;

    // rozbor partie
    private int[] revScore, revBest, revLoss;
    private boolean reviewReady;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        set = Settings.load(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(250, 249, 244));
        scroll.addView(root);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(dp(10), dp(8), dp(10), dp(4));
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
        info.setMinLines(3);
        root.addView(info);

        root.addView(row(
                button("|<", new View.OnClickListener() { public void onClick(View v) { goTo(0); } }),
                button("<", new View.OnClickListener() { public void onClick(View v) { goTo(cursor - 1); } }),
                button(">", new View.OnClickListener() { public void onClick(View v) { goTo(cursor + 1); } }),
                button(">|", new View.OnClickListener() { public void onClick(View v) { goTo(moves.size()); } }),
                button("Smazat", new View.OnClickListener() { public void onClick(View v) { clearBoard(); } })));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        for (int t = 0; t < 4; t++) {
            final int tt = t;
            toolButtons[t] = button(TOOL_NAMES[t], new View.OnClickListener() {
                public void onClick(View v) { setTool(tt); }
            });
            tools.addView(toolButtons[t]);
        }
        root.addView(tools);

        root.addView(row(
                button("Analýza", new View.OnClickListener() { public void onClick(View v) { analyze(); } }),
                button("AI tah", new View.OnClickListener() { public void onClick(View v) { aiMove(); } }),
                button("Rozbor", new View.OnClickListener() { public void onClick(View v) { review(); } }),
                button("Chyba >", new View.OnClickListener() { public void onClick(View v) { nextMistake(); } })));

        stmButton = button("Na tahu", new View.OnClickListener() {
            public void onClick(View v) { toggleStm(); }
        });
        root.addView(row(
                stmButton,
                button("Hrát odtud", new View.OnClickListener() { public void onClick(View v) { playFromHere(); } }),
                button("Menu", new View.OnClickListener() { public void onClick(View v) { finish(); } })));

        setContentView(scroll);

        int[] stones = getIntent().getIntArrayExtra("stones");
        if (stones != null) {
            for (int code : stones) moves.add(new int[]{code / 4, code % 4});
            cursor = moves.size();
        }
        setTool(TOOL_ALT);
        rebuild();
        if (stones != null && getIntent().getBooleanExtra("review", false)) {
            ui.post(new Runnable() {
                public void run() { review(); }
            });
        } else {
            info.setText("Volná deska: klepni na pole pro kámen. Střídat = barvy se střídají, "
                    + "Černý/Bílý = libovolná barva, Guma = smaže kámen. Tlačítky < > se vracíš v tazích.");
        }
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
        b.setTextSize(12);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private LinearLayout row(Button... bs) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (Button b : bs) r.addView(b);
        return r;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------ deska

    private void rebuild() {
        board = new Board(N);
        board.exactFive = set.rules == 1;
        for (int k = 0; k < cursor; k++) board.placeAs(moves.get(k)[0], moves.get(k)[1]);
        int stm = forcedStm != 0 ? forcedStm
                : (cursor == 0 ? Board.BLACK : 3 - moves.get(cursor - 1)[1]);
        board.setTurn(stm);
        view.setBoard(board);
        refreshStatus();
    }

    private void refreshStatus() {
        status.setText("Kamenů: " + board.cnt + "   •   na tahu: " + Texts.colorName(board.stm));
        stmButton.setText("Na tahu: " + Texts.colorName(board.stm));
    }

    private void setTool(int t) {
        tool = t;
        for (int i = 0; i < 4; i++) {
            toolButtons[i].setText(i == t ? "[" + TOOL_NAMES[i] + "]" : TOOL_NAMES[i]);
        }
    }

    private void clearReview() {
        reviewReady = false;
        revScore = revBest = revLoss = null;
    }

    private void onCell(int cell) {
        if (busy) return;
        view.setMarks(null);
        view.setHint(-1);
        if (tool == TOOL_ERASE) {
            for (int k = 0; k < cursor; k++) {
                if (moves.get(k)[0] == cell) {
                    moves.remove(k);
                    cursor--;
                    forcedStm = 0;
                    clearReview();
                    rebuild();
                    info.setText("Kámen odstraněn.");
                    return;
                }
            }
            return;
        }
        if (board.c[cell] != Board.EMPTY) return;
        int color = tool == TOOL_BLACK ? Board.BLACK : tool == TOOL_WHITE ? Board.WHITE : board.stm;
        while (moves.size() > cursor) moves.remove(moves.size() - 1);
        moves.add(new int[]{cell, color});
        cursor++;
        forcedStm = 0;
        clearReview();
        rebuild();
        info.setText("");
        if (board.wins(cell)) {
            view.setWinLine(board.winLine(cell));
            info.setText("Pětka v řadě – vyhrává " + Texts.colorName(color) + ".");
        }
    }

    private void goTo(int k) {
        if (busy) return;
        k = Math.max(0, Math.min(moves.size(), k));
        cursor = k;
        forcedStm = 0;
        rebuild();
        showReviewInfo();
    }

    private void clearBoard() {
        if (busy) return;
        gen++;
        moves.clear();
        cursor = 0;
        forcedStm = 0;
        clearReview();
        rebuild();
        info.setText("Deska vymazána.");
    }

    private void toggleStm() {
        if (busy) return;
        forcedStm = 3 - board.stm;
        rebuild();
        view.setMarks(null);
    }

    // ------------------------------------------------------------ analýza a AI

    private long analysisMs() {
        return Math.max(5000, Settings.LEVEL_MS[set.level]);
    }

    private void analyze() {
        if (busy) return;
        busy = true;
        view.setMarks(null);
        info.setText("Analyzuji pozici…");
        final int g = gen;
        final Board snap = board.copy();
        final int stm = snap.stm;
        final long ms = analysisMs();
        pool.execute(new Runnable() {
            public void run() {
                final Engine.Analysis a = engine.analyze(snap, ms, 3);
                ui.post(new Runnable() {
                    public void run() {
                        if (g != gen) return;
                        busy = false;
                        if (a.moves.length == 0) {
                            info.setText("Žádný tah k analýze.");
                            return;
                        }
                        view.setMarks(a.moves);
                        StringBuilder sb = new StringBuilder();
                        sb.append(Texts.evalBlack(a.scores[0], stm, a.mate)).append("\nNejlepší tahy (").append(Texts.colorName(stm)).append("): ");
                        for (int k = 0; k < a.moves.length; k++) {
                            if (k > 0) sb.append(",  ");
                            sb.append(k + 1).append(". ").append(Texts.cell(a.moves[k], N))
                                    .append(" (").append(Texts.shortScore(a.scores[k])).append(")");
                        }
                        sb.append("\nHloubka ").append(a.depth).append(".");
                        info.setText(sb.toString());
                    }
                });
            }
        });
    }

    private void aiMove() {
        if (busy) return;
        busy = true;
        view.setMarks(null);
        info.setText("AI přemýšlí…");
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
                        if (r.move < 0 || board.c[r.move] != Board.EMPTY) {
                            info.setText("AI nemá tah.");
                            return;
                        }
                        int color = board.stm;
                        while (moves.size() > cursor) moves.remove(moves.size() - 1);
                        moves.add(new int[]{r.move, color});
                        cursor++;
                        forcedStm = 0;
                        clearReview();
                        rebuild();
                        String txt = "AI zahrála " + Texts.cell(r.move, N) + " (hloubka " + r.depth + ").";
                        if (board.wins(r.move)) {
                            view.setWinLine(board.winLine(r.move));
                            txt += " Pětka – vyhrává " + Texts.colorName(color) + ".";
                        }
                        info.setText(txt);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------ rozbor partie

    private void review() {
        if (busy) return;
        if (moves.isEmpty()) {
            Toast.makeText(this, "Nejdřív polož nějaké kameny.", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        view.setMarks(null);
        view.setHint(-1);
        final int g = gen;
        final int total = moves.size();
        final ArrayList<int[]> copy = new ArrayList<int[]>(moves);
        final boolean exact = set.rules == 1;
        final long ms = Math.min(1500, Math.max(700, Settings.LEVEL_MS[set.level] / 4));
        info.setText("Rozbor partie: 0 / " + (total + 1) + " …");
        pool.execute(new Runnable() {
            public void run() {
                final int[] score = new int[total + 1];
                final int[] best = new int[total + 1];
                Board b = new Board(N);
                b.exactFive = exact;
                for (int k = 0; k <= total; k++) {
                    if (g != gen) return;
                    boolean over = k > 0 && b.wins(b.lastMove());
                    if (over || k == total && total >= N * N) {
                        score[k] = 0;
                        best[k] = -1;
                    } else {
                        Engine.Result r = engine.think(b, ms, 14);
                        score[k] = r.score;
                        best[k] = r.move;
                    }
                    final int done = k + 1;
                    ui.post(new Runnable() {
                        public void run() {
                            if (g == gen) info.setText("Rozbor partie: " + done + " / " + (total + 1) + " …");
                        }
                    });
                    if (k < total) {
                        b.placeAs(copy.get(k)[0], copy.get(k)[1]);
                        int nextStm = 3 - copy.get(k)[1];
                        b.setTurn(nextStm);
                    }
                }
                final int[] loss = new int[total];
                for (int m = 0; m < total; m++) {
                    int col = copy.get(m)[1];
                    int stmBefore = m == 0 ? Board.BLACK : 3 - copy.get(m - 1)[1];
                    boolean over = best[m + 1] == -1;
                    if (stmBefore != col || over || copy.get(m)[0] == best[m]) {
                        loss[m] = 0;
                        continue;
                    }
                    int e0 = Math.max(-1500, Math.min(1500, score[m]));
                    int e1 = Math.max(-1500, Math.min(1500, score[m + 1]));
                    loss[m] = Math.max(0, e0 + e1);
                }
                ui.post(new Runnable() {
                    public void run() {
                        if (g != gen) return;
                        busy = false;
                        revScore = score;
                        revBest = best;
                        revLoss = loss;
                        reviewReady = true;
                        cursor = moves.size();
                        rebuild();
                        showReviewSummary();
                    }
                });
            }
        });
    }

    private static String mistakeLabel(int loss) {
        if (loss >= 700) return "?? hrubá chyba";
        if (loss >= 250) return "? chyba";
        if (loss >= 80) return "?! nepřesnost";
        return null;
    }

    private void showReviewSummary() {
        int count = 0;
        StringBuilder sb = new StringBuilder("Rozbor hotov. ");
        for (int m = 0; m < revLoss.length; m++) if (revLoss[m] >= 250) count++;
        sb.append(count == 0 ? "Žádné výrazné chyby." : "Výrazných chyb: " + count + ". Tlačítkem „Chyba >“ je projdeš.");
        sb.append("\n").append(positionEval());
        info.setText(sb.toString());
        showBest();
    }

    private String positionEval() {
        if (!reviewReady || cursor >= revScore.length) return "";
        if (revBest[cursor] < 0) return "Konec partie.";
        int stm = board.stm;
        return Texts.evalBlack(revScore[cursor], stm, Math.abs(revScore[cursor]) > Engine.WIN - 200);
    }

    private void showBest() {
        if (reviewReady && cursor < revBest.length && revBest[cursor] >= 0) {
            view.setMarks(new int[]{revBest[cursor]});
        } else {
            view.setMarks(null);
        }
    }

    private void showReviewInfo() {
        view.setMarks(null);
        if (!reviewReady) {
            info.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (cursor > 0) {
            int m = cursor - 1;
            String label = mistakeLabel(revLoss[m]);
            sb.append("Tah ").append(cursor).append(" (").append(Texts.colorName(moves.get(m)[1])).append(") ")
                    .append(Texts.cell(moves.get(m)[0], N));
            if (label != null) {
                sb.append(": ").append(label);
                if (revBest[m] >= 0) sb.append(", lepší bylo ").append(Texts.cell(revBest[m], N));
            } else {
                sb.append(": v pořádku");
            }
            sb.append(".\n");
        }
        sb.append(positionEval());
        if (cursor < revBest.length && revBest[cursor] >= 0) {
            sb.append("\nZeleně: nejlepší pokračování ").append(Texts.cell(revBest[cursor], N)).append(".");
        }
        info.setText(sb.toString());
        showBest();
    }

    private void nextMistake() {
        if (busy) return;
        if (!reviewReady) {
            Toast.makeText(this, "Nejdřív spusť Rozbor.", Toast.LENGTH_SHORT).show();
            return;
        }
        for (int m = cursor; m < revLoss.length; m++) {
            if (revLoss[m] >= 250) {
                cursor = m + 1;
                forcedStm = 0;
                rebuild();
                showReviewInfo();
                return;
            }
        }
        Toast.makeText(this, "Další výrazná chyba už není.", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ dohrát odtud

    private void playFromHere() {
        if (busy) return;
        if (board.cnt == 0) {
            Toast.makeText(this, "Nejdřív polož nějaké kameny.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Dohrát pozici proti AI")
                .setItems(new String[]{"Hraju černého", "Hraju bílého"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        int[] stones = new int[board.cnt];
                        for (int k = 0; k < board.cnt; k++) stones[k] = board.hist[k] * 4 + board.histCol[k];
                        Intent it = new Intent(LabActivity.this, GameActivity.class);
                        it.putExtra("stones", stones);
                        it.putExtra("stm", board.stm);
                        it.putExtra("human", which == 0 ? Board.BLACK : Board.WHITE);
                        startActivity(it);
                    }
                })
                .show();
    }
}
