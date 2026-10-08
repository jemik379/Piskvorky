package cz.piskvorky.trener;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Databáze uložených partií: otevřít v analýze, přejmenovat, sdílet, smazat. */
public class GamesActivity extends Activity {
    private GameDb db;
    private ListView list;
    private TextView empty;
    private ArrayList<GameDb.Row> rows = new ArrayList<GameDb.Row>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new GameDb(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(250, 249, 244));
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Uložené partie");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("Klepni na partii: otevřeš ji v analýze, přejmenuješ, sdílíš nebo smažeš.");
        help.setPadding(0, dp(4), 0, dp(8));
        root.addView(help);

        empty = new TextView(this);
        empty.setText("Zatím nic. Partii uložíš tlačítkem Uložit ve hře nebo na volné desce. "
                + "Dohrané partie se ukládají automaticky, pokud to nevypneš v menu.");
        root.addView(empty);

        list = new ListView(this);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                if (pos >= 0 && pos < rows.size()) showActions(rows.get(pos));
            }
        });

        Button back = new Button(this);
        back.setText("Zpět");
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        root.addView(back);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        db.close();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void refresh() {
        rows = db.list();
        String[] labels = new String[rows.size()];
        SimpleDateFormat f = new SimpleDateFormat("d.M.yyyy HH:mm", Locale.getDefault());
        for (int i = 0; i < labels.length; i++) {
            GameDb.Row r = rows.get(i);
            labels[i] = r.name + "\n" + f.format(new Date(r.created)) + "  •  " + r.codes.length
                    + " kamenů  •  " + r.result;
        }
        list.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels));
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showActions(final GameDb.Row r) {
        String[] items = {"Otevřít v analýze / rozboru", "Přejmenovat", "Sdílet jako text", "Smazat"};
        new AlertDialog.Builder(this)
                .setTitle(r.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) open(r);
                        else if (which == 1) rename(r);
                        else if (which == 2) share(r);
                        else confirmDelete(r);
                    }
                })
                .show();
    }

    private void open(GameDb.Row r) {
        Intent it = new Intent(this, LabActivity.class);
        it.putExtra("stones", r.codes);
        it.putExtra("stm", r.stm);
        startActivity(it);
    }

    private void rename(final GameDb.Row r) {
        final EditText in = new EditText(this);
        in.setText(r.name);
        new AlertDialog.Builder(this)
                .setTitle("Přejmenovat partii")
                .setView(in)
                .setPositiveButton("Uložit", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String name = in.getText().toString().trim();
                        if (name.length() > 0) {
                            db.rename(r.id, name);
                            refresh();
                        }
                    }
                })
                .setNegativeButton("Zrušit", null)
                .show();
    }

    private void confirmDelete(final GameDb.Row r) {
        new AlertDialog.Builder(this)
                .setTitle("Smazat partii?")
                .setPositiveButton("Smazat", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        db.delete(r.id);
                        refresh();
                    }
                })
                .setNegativeButton("Zrušit", null)
                .show();
    }

    private void share(GameDb.Row r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Piškvorky trenér – ").append(r.name).append("\n").append(r.result)
                .append("\nPravidla: ").append(r.exact ? "přesně pět" : "pět a více")
                .append("\nTahy: ");
        for (int k = 0; k < r.codes.length; k++) {
            if (k > 0) sb.append(' ');
            sb.append(k + 1).append('.').append(Board.name(r.codes[k] / 4, 15))
                    .append(r.codes[k] % 4 == Board.BLACK ? "(Č)" : "(B)");
        }
        Intent it = new Intent(Intent.ACTION_SEND);
        it.setType("text/plain");
        it.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(it, "Sdílet partii"));
    }
}
