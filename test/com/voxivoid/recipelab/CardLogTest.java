package com.voxivoid.recipelab;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shareable logs on the card: where they go, what a line looks like, and the roll-over that keeps them small.
 * Whether the camera's card layer takes the folder, and whether a line survives a reboot, needs a camera.
 */
class CardLogTest {
    @TempDir File root;

    private static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    @Test void folderAndFileNamesAreDos83() {
        for (String n : new String[] { CardLog.DIR, CardLog.SUB }) assertTrue(n.length() <= 8 && n.equals(n.toUpperCase()), n);
        for (String n : new String[] { CardLog.LOG, CardLog.DIFF, CardLog.KEYS, CardLog.LOCKS, CardLog.PARAMS, CardLog.older(CardLog.LOG), CardLog.older(CardLog.LOCKS) }) assertTrue(n.matches("[A-Z0-9]{1,8}\\.[A-Z]{1,3}"), n);
        assertEquals("RECIPLAB/LOGS/LOG.TXT", CardLog.path(CardLog.LOG));
    }

    @Test void noCardNoFolder() {
        assertNull(CardLog.dir(null));
        assertThrows(IOException.class, () -> CardLog.append(null, CardLog.LOG, "x\n"));
    }

    @Test void appendCreatesTheFoldersAndAppends() throws IOException {
        File dir = CardLog.dir(root);
        CardLog.append(dir, CardLog.LOG, "one\n");
        CardLog.append(dir, CardLog.LOG, "two\n");
        assertEquals(new File(new File(root, "RECIPLAB"), "LOGS"), dir);
        assertEquals("one\ntwo\n", read(new File(dir, CardLog.LOG)));
    }

    @Test void aFullLogRollsOverAndKeepsOneOldFile() throws IOException {
        File dir = CardLog.dir(root);
        char[] big = new char[CardLog.MAX_BYTES - 10]; Arrays.fill(big, 'a');
        CardLog.append(dir, CardLog.LOG, new String(big));
        CardLog.append(dir, CardLog.LOG, "first after the cap\n");
        assertEquals("first after the cap\n", read(new File(dir, CardLog.LOG)));
        assertEquals(CardLog.MAX_BYTES - 10, new File(dir, "LOG1.TXT").length());

        CardLog.append(dir, CardLog.LOG, new String(big));       // a second roll replaces the old file, never a third
        assertEquals(new String(big), read(new File(dir, CardLog.LOG)));
        assertEquals(2, dir.list().length);
    }

    @Test void everyFileRollsToItsOwnOlderFile() throws IOException {
        File dir = CardLog.dir(root);
        char[] big = new char[CardLog.MAX_BYTES]; Arrays.fill(big, 'a');
        CardLog.append(dir, CardLog.KEYS, new String(big));
        CardLog.append(dir, CardLog.KEYS, "more\n");
        assertEquals("more\n", read(new File(dir, CardLog.KEYS)));
        assertEquals(CardLog.MAX_BYTES, new File(dir, "KEYS1.TXT").length());
        assertEquals("LOCKS1.TXT", CardLog.older(CardLog.LOCKS));
    }

    @Test void paramsAreReplacedNeverAppended() throws IOException {
        File dir = CardLog.dir(root);
        CardLog.replace(dir, CardLog.PARAMS, "one\n");
        CardLog.replace(dir, CardLog.PARAMS, "two\n");
        assertEquals("two\n", read(new File(dir, CardLog.PARAMS)));
    }

    @Test void aParamIsFoundAnywhereButNotInsideAnotherKey() {
        String flat = "color-mode=mono;scene-mode=shutter-speed;scene-mode-values=auto,portrait";
        assertEquals("shutter-speed", CardLog.param(flat, CardLog.MODE_KEY));
        assertEquals("mono", CardLog.param(flat, "color-mode"));
        assertEquals("auto,portrait", CardLog.param(flat, "scene-mode-values"));
        assertNull(CardLog.param(flat, "mode"));
        assertNull(CardLog.param(null, "mode"));
    }

    @Test void theParamsFileIsOneSortedLinePerParameter() {
        assertEquals("# head\na=1\nb=2\nc=\n", CardLog.paramsFile("head", "c=;b=2;a=1"));
    }

    @Test void theStateNamesEveryRowButTheRecipe() {
        int[] rows = new int[Params.ROW_NAME.length]; rows[1] = 3; rows[2] = -1;
        String st = CardLog.state(rows);
        assertTrue(st.startsWith("STYLE=3 SAT=-1 CON=0"), st);
        assertFalse(st.contains("RECIPE"), st);
        assertEquals(Params.ROW_NAME.length - 1, st.split(" ").length);
    }

    @Test void rollsOnlyAFileThatHasSomethingInIt() {
        assertFalse(CardLog.rolls(0, CardLog.MAX_BYTES * 2));
        assertFalse(CardLog.rolls(10, CardLog.MAX_BYTES - 10));
        assertTrue(CardLog.rolls(10, CardLog.MAX_BYTES - 9));
    }

    @Test void aLineHasTheTimeTheKindAndTheMessage() {
        String l = CardLog.line(CardLog.WRITE, "Velvia: 2 slots");
        assertTrue(l.matches("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d  WRITE    Velvia: 2 slots\n"), l);
    }

    @Test void theCameraClockIsUtcPlusItsOffset() {
        assertEquals("2026-10-15 14:03:27", CardLog.stamp(2026, 10, 15, 13, 3, 27, 60));    // Lisbon summer time
        assertEquals("2026-01-01 00:30:00", CardLog.stamp(2025, 12, 31, 23, 30, 0, 60));    // across midnight and the year
        assertEquals("2026-10-15 08:03:27", CardLog.stamp(2026, 10, 15, 13, 3, 27, -300));
    }

    @Test void offTheCameraTheSystemClock() { assertTrue(CardLog.now().startsWith(String.valueOf(java.time.Year.now().getValue())), CardLog.now()); }

    @Test void aMultiLineMessageKeepsItsIndent() {
        String[] l = CardLog.line(CardLog.CRASH, "boom\nat x").split("\n");
        assertEquals(2, l.length);
        assertEquals(l[0].indexOf("boom"), l[1].indexOf("at x"));
    }

    @Test void theHeaderNamesTheBodyAndTheBuild() {
        assertEquals("model ILCE-6000  ·  firmware 3.21  ·  platform 2.0  ·  app 1.7.0  ·  mode shutter-speed  ·  keys Fn",
                CardLog.header("ILCE-6000", "3.21", "2.0", "1.7.0", "Fn", "shutter-speed"));
        assertEquals("model unknown  ·  firmware unknown  ·  platform unknown  ·  app unknown  ·  mode unknown  ·  keys unknown", CardLog.header(null, null, "", null, null, null));
    }

    @Test void autoMayBeTheMovieDial() {
        assertTrue(CardLog.header("ILCE-6000", "3.21", "2.4", "1.7.0", "Fn", "auto").contains("mode auto (Auto or Movie)"));
        assertEquals("manual-exposure", CardLog.modeLabel("manual-exposure"));
    }

    @Test void theAppLineSaysWhatTheScreenShowed() {
        assertEquals("recipe Velvia  PREVIEW EDITED  ·  language auto (en)  ·  custom recipes 2 (skipped: BAD.YML line 3)  ·  favourites 5",
                CardLog.app("Velvia", false, true, "auto (en)", 2, Arrays.asList("BAD.YML line 3"), 5));
        assertEquals("recipe Factory  ACTIVE  ·  language zh-Hans  ·  custom recipes 0  ·  favourites 0",
                CardLog.app("Factory", true, false, "zh-Hans", 0, Arrays.<String>asList(), 0));
    }

    @Test void aRefusedWriteSaysWhereItStopped() {
        assertEquals("01070c7f = 3 refused: Backup_get_datasize failed  (2 of 9 written before it)",
                CardLog.writeFailed(0x01070c7f, 3, "Backup_get_datasize failed", 2, 9));
    }

    @Test void aWriteListsEverySlot() {
        assertEquals("Velvia: 2 slots  01070175=1  01070187=3",
                CardLog.written("Velvia", Arrays.asList(new Params.Write(0x01070175, 1), new Params.Write(0x01070187, 3))));
        assertEquals("Factory: 1 slot  01070175=0", CardLog.written("Factory", Arrays.asList(new Params.Write(0x01070175, 0))));
    }

    @Test void idsAreEightHexDigits() {
        assertEquals("01070175 0107031c", CardLog.ids(Arrays.asList(0x01070175, 0x0107031c)));
        assertEquals("", CardLog.ids(Arrays.<Integer>asList()));
    }

    @Test void previewNamesOnlyTheValuesTheCameraDidNotKeep() {
        Map<String, String> wanted = new LinkedHashMap<String, String>(), got = new LinkedHashMap<String, String>();
        wanted.put("color-mode", "mono"); wanted.put("saturation", "0"); wanted.put("contrast", "1");
        got.put("color-mode", "standard"); got.put("saturation", "0");
        assertEquals("color-mode mono>standard  contrast 1>null", CardLog.previewDropped(wanted, got));
        got.put("color-mode", "mono"); got.put("contrast", "1");
        assertEquals("", CardLog.previewDropped(wanted, got));
    }

    @Test void theReportHoldsTheCameraNowAndTheEndOfEveryFile() throws IOException {
        File dir = CardLog.dir(root);
        CardLog.append(dir, CardLog.LOG, "opened\n");
        CardLog.append(dir, CardLog.KEYS, "down 232\n");
        String r = CardLog.report(dir, "2026-10-15 14:03:27", "model ILCE-6000", "recipe Velvia  ACTIVE", "STYLE=1", "scene-mode=manual-exposure;color-mode=mono");
        assertTrue(r.startsWith("Recipe Lab report"), r);
        assertTrue(r.contains("model ILCE-6000\nrecipe Velvia  ACTIVE\nSTATE STYLE=1\n"), r);
        assertTrue(r.contains("===== LOG =====\nopened\n"), r);
        assertTrue(r.contains("===== KEYS =====\ndown 232\n"), r);
        assertTrue(r.contains("===== LOCKS =====\n(none)\n"), r);
        assertTrue(r.contains("===== PARAMS =====\n# 2026-10-15 14:03:27\ncolor-mode=mono\nscene-mode=manual-exposure\n"), r);
    }

    @Test void theReportGoesBesideTheLogs() {
        assertEquals(new File(root, "RECIPLAB"), CardLog.reportDir(root));
        assertNull(CardLog.reportDir(null));
        assertTrue(CardLog.REPORT.matches("[A-Z0-9]{1,8}\\.[A-Z]{1,3}"));
    }

    @Test void aTailKeepsTheNewestWholeLinesAcrossTheOlderFile() throws IOException {
        File dir = CardLog.dir(root);
        CardLog.replace(dir, "LOG1.TXT", "one\ntwo\n");
        CardLog.replace(dir, CardLog.LOG, "three\n");
        assertEquals("one\ntwo\nthree\n", CardLog.tail(new File(dir, "LOG1.TXT"), new File(dir, CardLog.LOG), 100));
        assertEquals("two\nthree\n", CardLog.tail(new File(dir, "LOG1.TXT"), new File(dir, CardLog.LOG), 11));
        assertEquals("", CardLog.tail(null, new File(dir, "NONE.TXT"), 10));
    }

    @Test void timingSumsDrawsIntoOneLine() {
        CardLog.Timing t = new CardLog.Timing();
        assertTrue(t.empty());
        t.add(40, 10); t.add(130, 35); t.add(21, 12);
        assertFalse(t.empty());
        assertEquals("menu: 3 draws  ·  key to drawn avg 64 ms, max 130 ms  ·  draw avg 19 ms, max 35 ms", t.line("menu"));
        t.reset();
        assertTrue(t.empty());
        t.add(7, 3);
        assertEquals("menu: 1 draw  ·  key to drawn avg 7 ms, max 7 ms  ·  draw avg 3 ms, max 3 ms", t.line("menu"));   // nothing left from before the reset
    }

    @Test void perfTimesAKeyToTheNextDrawOfItsScreen() {
        CardLog.Perf p = new CardLog.Perf();
        assertTrue(p.changed(1000, CardLog.Perf.PANEL));
        p.drawn(1030, 1040);                                    // queued 30 ms, drawn in 10
        p.drawn(2000, 2005);                                    // a draw no key asked for: no sample
        assertEquals("panel: 1 draw  ·  key to drawn avg 40 ms, max 40 ms  ·  draw avg 10 ms, max 10 ms", p.take(CardLog.Perf.PANEL));
        assertNull(p.take(CardLog.Perf.PANEL));                 // taken: a fresh start
        assertNull(p.take(CardLog.Perf.MENU));
    }

    @Test void perfTimesKeysThatQueuedUpFromTheOldest() {
        CardLog.Perf p = new CardLog.Perf();
        p.changed(1000, CardLog.Perf.BROWSER);
        p.changed(1020, CardLog.Perf.BROWSER);
        p.drawn(1050, 1100);
        assertEquals("browser: 1 draw  ·  key to drawn avg 100 ms, max 100 ms  ·  draw avg 50 ms, max 50 ms", p.take(CardLog.Perf.BROWSER));
    }

    @Test void perfLeavesOutKeysThatAreNotTimedOrDrewNothing() {
        CardLog.Perf p = new CardLog.Perf();
        assertFalse(p.changed(1000, CardLog.Perf.NONE));         // a question, the name editor, Transfer: not timed
        assertFalse(p.changed(0, CardLog.Perf.PANEL));           // not during a key
        p.drawn(1010, 1020);
        assertNull(p.take(CardLog.Perf.PANEL));
        p.changed(2000, CardLog.Perf.PANEL);
        p.drop();                                               // it changed nothing: no draw came
        p.drawn(5000, 5010);
        assertNull(p.take(CardLog.Perf.PANEL));
        p.changed(6000, CardLog.Perf.MENU);
        p.drop(CardLog.Perf.PANEL);                             // another screen went: the menu's key is kept
        p.drawn(6010, 6020);
        assertNotNull(p.take(CardLog.Perf.MENU));
        p.changed(7000, CardLog.Perf.MENU);
        p.drop(CardLog.Perf.MENU);                              // the menu closed: its key is gone
        p.drawn(7010, 7020);
        assertNull(p.take(CardLog.Perf.MENU));
    }

    @Test void aStackHasTheExceptionAndWhereItCameFrom() {
        String s = CardLog.stack(new IllegalStateException("boom"));
        assertTrue(s.startsWith("java.lang.IllegalStateException: boom"), s);
        assertTrue(s.contains("CardLogTest"), s);
    }
}
