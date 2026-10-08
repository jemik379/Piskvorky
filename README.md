# Piškvorky trenér (Android)

Trenérská aplikace na piškvorky / gomoku (5 v řadě, deska 15×15) proti AI.

## Funkce
- **Zahájení:** bez swapu, klasický **Swap**, **Swap2** (včetně třetí možnosti „přidat 2 kameny“).
- **Tvoje role:** hráč A (kladeš 3 kameny), hráč B (vybíráš barvu), nebo náhodně.
- **AI:** iterativní alfa-beta s transpoziční tabulkou, řazením tahů podle vzorů a hledáním vynucené výhry čtyřkami (VCF). AI umí i hrát za A (vybere vyvážené zahájení) a za B (rozhodne se podle hodnocení pozice).
- **Síla:** čas na tah 0,3 s / 1 s / 3 s / 8 s.
- **Trénink:** tlačítko **Tip** (nejlepší tah + slovní hodnocení pozice), **Zpět**, čísla tahů, souřadnice A–O / 1–15.
- **Pravidla:** gomoku (pět a více) nebo přesně pět.

## Jak získat APK z GitHubu
1. Na github.com vytvoř nový repozitář (např. `piskvorky-trener`).
2. Nahraj do něj **celý obsah této složky** včetně skryté složky `.github`.
   (Když se `.github` nenahraje: Add file → Create new file, jako název zadej
   `.github/workflows/build.yml` a vlož obsah souboru `build.yml` z této složky.)
3. Otevři záložku **Actions**. Build „Build APK“ se spustí sám (trvá kolem 3–6 minut).
   Případně ho spusť ručně: Actions → Build APK → Run workflow.
4. Po dokončení najdeš APK v **Releases** → „Nejnovější APK“ → `app-debug.apk`.
   Stáhni ho v telefonu a nainstaluj (povol instalaci z neznámých zdrojů).
   Druhá možnost: Actions → poslední běh → Artifacts → `piskvorky-trener-apk`.

Když build selže, otevři neúspěšný běh v Actions, zkopíruj chybovou hlášku a pošli ji Claudovi.

## Struktura
- `Board.java` – deska, pravidla, inkrementální hodnocení
- `Engine.java` – AI (alfa-beta + VCF)
- `Opening.java` – Swap/Swap2 logika pro AI
- `GameActivity.java`, `MenuActivity.java`, `BoardView.java` – obrazovky

## Verze 2
- Výrazně silnější AI (rychlejší hodnocení, PVS, rozpoznání vynucených výher, omezení tahů při hrozbě) a vyšší úrovně až 40 s na tah.
- Volná deska / Analýza: pokládání kamenů libovolné barvy, kroky vpřed/vzad, guma, tři nejlepší tahy, tah AI, rozbor partie s chybami a dohrání pozice proti AI.
