package cz.piskvorky.trener;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/** Úvodní obrazovka s nastavením. */
public class MenuActivity extends Activity {
    private Settings s;
    private Spinner spOpening, spRole, spColor, spLevel, spRules, spStyle;
    private CheckBox cbCenter, cbNumbers, cbAuto;
    private TextView roleLabel, colorLabel;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        s = Settings.load(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Piškvorky – trenér");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("5 v řadě proti AI se Swap / Swap2, tipy a hodnocením pozice.");
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        spOpening = addSpinner(root, "Zahájení", Settings.OPENING, s.opening);
        roleLabel = lastLabel;
        spRole = addSpinner(root, "Tvoje role ve swapu", Settings.ROLE, s.role);
        TextView roleTv = lastLabel;
        spColor = addSpinner(root, "Tvoje barva (bez swapu)", Settings.COLOR, s.color);
        TextView colorTv = lastLabel;
        spLevel = addSpinner(root, "Síla AI (čas na tah)", Settings.LEVEL, s.level);
        spRules = addSpinner(root, "Pravidla", Settings.RULES, s.rules);
        spStyle = addSpinner(root, "Vzhled desky", Settings.STYLE, s.style);

        cbCenter = new CheckBox(this);
        cbCenter.setText("První černý kámen vždy uprostřed");
        cbCenter.setChecked(s.centerFirst);
        root.addView(cbCenter);

        cbNumbers = new CheckBox(this);
        cbNumbers.setText("Zobrazovat čísla tahů");
        cbNumbers.setChecked(s.numbers);
        root.addView(cbNumbers);

        cbAuto = new CheckBox(this);
        cbAuto.setText("Ukládat dohrané partie automaticky");
        cbAuto.setChecked(s.autoSave);
        root.addView(cbAuto);

        Button start = new Button(this);
        start.setText("Začít hru");
        start.setTextSize(18);
        root.addView(start);
        ((LinearLayout.LayoutParams) start.getLayoutParams()).topMargin = dp(16);

        Button lab = new Button(this);
        lab.setText("Volná deska / Analýza");
        root.addView(lab);
        lab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                s.level = spLevel.getSelectedItemPosition();
                s.rules = spRules.getSelectedItemPosition();
                s.numbers = cbNumbers.isChecked();
                s.style = spStyle.getSelectedItemPosition();
                s.autoSave = cbAuto.isChecked();
                s.save(MenuActivity.this);
                startActivity(new Intent(MenuActivity.this, LabActivity.class));
            }
        });

        Button games = new Button(this);
        games.setText("Uložené partie");
        root.addView(games);
        games.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MenuActivity.this, GamesActivity.class));
            }
        });

        final TextView fRole = roleTv, fColor = colorTv;
        spOpening.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                boolean swap = pos != 0;
                int a = swap ? View.VISIBLE : View.GONE;
                int b = swap ? View.GONE : View.VISIBLE;
                fRole.setVisibility(a);
                spRole.setVisibility(a);
                fColor.setVisibility(b);
                spColor.setVisibility(b);
                cbCenter.setVisibility(a);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                s.opening = spOpening.getSelectedItemPosition();
                s.role = spRole.getSelectedItemPosition();
                s.color = spColor.getSelectedItemPosition();
                s.level = spLevel.getSelectedItemPosition();
                s.rules = spRules.getSelectedItemPosition();
                s.centerFirst = cbCenter.isChecked();
                s.numbers = cbNumbers.isChecked();
                s.style = spStyle.getSelectedItemPosition();
                s.autoSave = cbAuto.isChecked();
                s.save(MenuActivity.this);
                startActivity(new Intent(MenuActivity.this, GameActivity.class));
            }
        });

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // vzhled se dá přepnout i ve hře, tak ho znovu načteme
        Settings t = Settings.load(this);
        s.style = t.style;
        spStyle.setSelection(t.style);
    }

    private TextView lastLabel;

    private Spinner addSpinner(LinearLayout parent, String label, String[] items, int selected) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(10), 0, 0);
        parent.addView(tv);
        lastLabel = tv;
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(selected);
        parent.addView(sp);
        return sp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
