package io.github.rareities.protonbridge.util;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Copyright (C) 2021  Felix Nüsse
 * Created on 20.06.21 - 15:30
 *
 * Edited by: Felix Nüsse felix.nuesse(at)t-online.de
 *
 */

public class SyncLog {

    private static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_RECORDS = 200;
    private static final Object LOCK = new Object();

    public static String TIMESTAMP = "timestamp";
    public static String TITLE = "title";
    public static String CONTENT = "content";
    public static String TYPE = "type";
    public static final int TYPE_ERROR = 0;
    public static final int TYPE_INFO = 1;

    public static ArrayList<JSONObject> getLog(Context c){
        synchronized (LOCK) {
            File log = new File(c.getFilesDir(), "sync.log");
            ArrayList<JSONObject> jsons = new ArrayList<>();
            if (!log.isFile()) return jsons;
            try (RandomAccessFile input = new RandomAccessFile(log, "r")) {
                long start = Math.max(0, input.length() - MAX_BYTES);
                input.seek(start);
                byte[] tail = new byte[(int) Math.min(MAX_BYTES, input.length() - start)];
                input.readFully(tail);
                String text = new String(tail, StandardCharsets.UTF_8);
                if (start > 0) {
                    int newline = text.indexOf('\n');
                    text = newline < 0 ? "" : text.substring(newline + 1);
                }
                String[] lines = text.split("\\r?\\n");
                for (int i = lines.length - 1; i >= 0 && jsons.size() < MAX_RECORDS; i--) {
                    if (lines[i].length() > 32768) continue;
                    try {
                        JSONObject entry = new JSONObject(lines[i]);
                        entry.put(TITLE, safe(entry.optString(TITLE), 512));
                        entry.put(CONTENT, safe(entry.optString(CONTENT), 4096));
                        jsons.add(entry);
                    } catch (JSONException malformed) { /* A partial record cannot become a result. */ }
                }
            } catch (IOException error) {
                FLog.w("SyncLog", "Could not read bounded task log");
            }
            return jsons;
        }
    }

    private static void appendLog(Context c, String entry){
        synchronized (LOCK) {
            File log = new File(c.getFilesDir(), "sync.log");
            byte[] bytes = ("\n" + entry).getBytes(StandardCharsets.UTF_8);
            try {
                if (log.length() + bytes.length > MAX_BYTES) {
                    ArrayList<JSONObject> retained = getLog(c);
                    StringBuilder compact = new StringBuilder();
                    int size = bytes.length;
                    for (JSONObject record : retained) {
                        String line = record.toString() + "\n";
                        int length = line.getBytes(StandardCharsets.UTF_8).length;
                        if (size + length > MAX_BYTES) break;
                        compact.insert(0, line);
                        size += length;
                    }
                    android.util.AtomicFile target = new android.util.AtomicFile(log);
                    FileOutputStream output = target.startWrite();
                    try {
                        output.write(compact.toString().getBytes(StandardCharsets.UTF_8));
                        target.finishWrite(output);
                    } catch (IOException error) { target.failWrite(output); throw error; }
                }
                try (FileOutputStream output = new FileOutputStream(log, true)) { output.write(bytes); }
            } catch (IOException error) { FLog.w("SyncLog", "Could not persist task log"); }
        }
    }

    private static String safe(String value, int limit) {
        String redacted = DiagnosticRedactor.redact(value);
        return redacted.length() <= limit ? redacted : redacted.substring(0, limit) + " [truncated]";
    }

    public static long log(Context c, String title, String content, int type){
        JSONObject json = new JSONObject();
        long now = System.currentTimeMillis();
        try {
            json.put(TIMESTAMP, now);
            json.put(CONTENT, safe(content, 4096));
            json.put(TITLE, safe(title, 512));
            json.put(TYPE, type);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        appendLog(c, json.toString());
        return now;
    }

    public static long error(Context c, String title, String content){
        return log(c, title, content, TYPE_ERROR);
    }

    public static long info(Context c, String title, String content){
        return log(c, title, content, TYPE_INFO);
    }

    public static void delete(Context c){
        synchronized (LOCK) {
            new android.util.AtomicFile(new File(c.getFilesDir(), "sync.log")).delete();
        }
    }

}
