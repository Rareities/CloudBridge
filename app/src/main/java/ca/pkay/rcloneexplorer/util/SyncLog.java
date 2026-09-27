package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
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

    private static final String TAG = "SyncLog";
    private static int loglength = 4;
    private static final long MAX_LOG_BYTES = 1024L * 1024L;

    public static String TIMESTAMP = "timestamp";
    public static String TITLE = "title";
    public static String CONTENT = "content";
    public static String TYPE = "type";
    public static final int TYPE_ERROR = 0;
    public static final int TYPE_INFO = 1;

    public static ArrayList<JSONObject> getLog(Context c){
        File log = new File(c.getFilesDir().getPath() + "/sync.log");
        StringBuilder file = new StringBuilder();
        try (Reader in = new InputStreamReader(new FileInputStream(log), StandardCharsets.UTF_8)) {
            file.append(BoundedTextReader.read(in, (int) MAX_LOG_BYTES));
        } catch (IOException e) {
            FLog.e(TAG, "Unable to read persisted sync log", e);
            return new ArrayList<>();
        }

        String lines[] = file.toString().split("\\r?\\n");

        ArrayList<JSONObject> jsons = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            try {
                jsons.add(new JSONObject(lines[i]));
            } catch (JSONException e) {
            }
        }
        Collections.reverse(jsons);
        return jsons;
    }

    private static void appendLog(Context c, String entry){

        File log = new File(c.getFilesDir().getPath() + "/sync.log");
        try {
            byte[] record = (System.lineSeparator() + entry).getBytes(StandardCharsets.UTF_8);
            if (!BoundedFileAppender.append(log, record, MAX_LOG_BYTES)) {
                FLog.w(TAG, "Sync log record exceeds the configured size limit");
            }
        } catch (Exception e){
            FLog.e(TAG, "Unable to append persisted sync log", e);
        }
    }

    public static long log(Context c, String title, String content, int type){
        JSONObject json = new JSONObject();
        long now = System.currentTimeMillis();
        try {
            json.put(TIMESTAMP, now);
            json.put(CONTENT, LogRedactor.redact(content));
            json.put(TITLE, LogRedactor.redact(title));
            json.put(TYPE, type);
        } catch (JSONException e) {
            FLog.e(TAG, "Unable to serialize sync log entry", e);
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
        File log = new File(c.getFilesDir().getPath() + "/sync.log");
        if (log.exists()) {
            if (log.delete()) {
                System.out.println("file Deleted");
            } else {
                System.out.println("file not Deleted");
            }
        }
    }

}
