package cz.piskvorky.trener;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class CrashActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final SharedPreferences p = getSharedPreferences("crash", Context.MODE_PRIVATE);
        final String trace = p.getString("trace", null);
        if (trace == null) {
            startActivity(new Intent(this, MenuActivity.class));
            finish();
            return;
        }
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Aplikace minule spadla");
        title.setTextSize(20);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("Stiskni Kopírovat chybu a vlož text Claudovi do konverzace.");
        root.addView(help);

        Button copy = new Button(this);
        copy.setText("Kopírovat chybu");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("crash", trace));
                Toast.makeText(CrashActivity.this, "Zkopírováno", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(copy);

        Button go = new Button(this);
        go.setText("Smazat a pokračovat");
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                p.edit().remove("trace").commit();
                startActivity(new Intent(CrashActivity.this, MenuActivity.class));
                finish();
            }
        });
        root.addView(go);

        TextView tv = new TextView(this);
        tv.setText(trace);
        tv.setTextSize(11);
        tv.setTextIsSelectable(true);
        root.addView(tv);

        setContentView(scroll);
    }
}
