package cz.piskvorky.trener;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.widget.EditText;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Databáze uložených partií (SQLite). Tahy se ukládají jako seznam kódů
 * pole*4 + barva (1 = černý, 2 = bílý), takže jde uložit i volně postavená pozice.
 */
final class GameDb extends SQLiteOpenHelper {
    static final class Row {
        long id;
        String name;
        long created;
        String result;
        int[] codes;
        int stm;
        boolean exact;
    }

    private static final String[] COLS = {"_id", "name", "created", "result", "moves", "stm", "exact"};

    GameDb(Context c) {
        super(c, "games.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE games (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, "
                + "created INTEGER, result TEXT, moves TEXT, stm INTEGER, exact INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS games");
        onCreate(db);
    }

    static String encode(int[] codes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < codes.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(codes[i]);
        }
        return sb.toString();
    }

    static int[] decode(String s) {
        if (s == null || s.length() == 0) return new int[0];
        String[] p = s.split(",");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) out[i] = Integer.parseInt(p[i].trim());
        return out;
    }

    static int[] codesOf(Board b) {
        int[] codes = new int[b.cnt];
        for (int k = 0; k < b.cnt; k++) codes[k] = b.hist[k] * 4 + b.histCol[k];
        return codes;
    }

    static String defaultName() {
        return "Partie " + new SimpleDateFormat("d.M. HH:mm", Locale.getDefault()).format(new Date());
    }

    long insert(String name, String result, int[] codes, int stm, boolean exact) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("created", System.currentTimeMillis());
        v.put("result", result);
        v.put("moves", encode(codes));
        v.put("stm", stm);
        v.put("exact", exact ? 1 : 0);
        return getWritableDatabase().insert("games", null, v);
    }

    private static Row fromCursor(Cursor c) {
        Row r = new Row();
        r.id = c.getLong(0);
        r.name = c.getString(1);
        r.created = c.getLong(2);
        r.result = c.getString(3);
        r.codes = decode(c.getString(4));
        r.stm = c.getInt(5);
        r.exact = c.getInt(6) == 1;
        return r;
    }

    ArrayList<Row> list() {
        ArrayList<Row> out = new ArrayList<Row>();
        Cursor c = getReadableDatabase().query("games", COLS, null, null, null, null, "created DESC");
        while (c.moveToNext()) out.add(fromCursor(c));
        c.close();
        return out;
    }

    void rename(long id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        getWritableDatabase().update("games", v, "_id=?", new String[]{String.valueOf(id)});
    }

    void delete(long id) {
        getWritableDatabase().delete("games", "_id=?", new String[]{String.valueOf(id)});
    }

    /** Uloží partii bez ptaní (automatické ukládání dohraných partií). */
    static void quickSave(Context ctx, String result, int[] codes, int stm, boolean exact) {
        GameDb db = new GameDb(ctx);
        try {
            db.insert(defaultName(), result, codes, stm, exact);
        } finally {
            db.close();
        }
    }

    /** Zeptá se na název a partii uloží. */
    static void saveWithDialog(final Activity a, final String result, final int[] codes,
                               final int stm, final boolean exact) {
        final String def = defaultName();
        final EditText in = new EditText(a);
        in.setText(def);
        new AlertDialog.Builder(a)
                .setTitle("Uložit partii")
                .setView(in)
                .setPositiveButton("Uložit", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String name = in.getText().toString().trim();
                        if (name.length() == 0) name = def;
                        GameDb db = new GameDb(a);
                        try {
                            db.insert(name, result, codes, stm, exact);
                        } finally {
                            db.close();
                        }
                        Toast.makeText(a, "Partie uložena do databáze.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Zrušit", null)
                .show();
    }
}
