package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
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

    private static int loglength = 4;

    public static String TIMESTAMP = "timestamp";
    public static String TITLE = "title";
    public static String CONTENT = "content";
    public static String TYPE = "type";
    public static final int TYPE_ERROR = 0;
    public static final int TYPE_INFO = 1;

    public static ArrayList<JSONObject> getLog(Context c){
        File log = new File(c.getFilesDir().getPath() + "/sync.log");
        String contents = "";
        try {
            contents = BoundedLogFile.read(log);
        } catch (IOException e) {
            FLog.e("SyncLog", "Could not read history");
        }
        String lines[] = contents.split("\\r?\\n");

        ArrayList<JSONObject> jsons = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            try {
                JSONObject record = new JSONObject(lines[i]);
                record.put(TITLE, SecretRedactor.redact(record.optString(TITLE)));
                record.put(CONTENT, SecretRedactor.redact(record.optString(CONTENT)));
                jsons.add(record);
            } catch (JSONException e) {
            }
        }
        Collections.reverse(jsons);
        return jsons;
    }

    private static void appendLog(Context c, String entry){

        File log = new File(c.getFilesDir().getPath() + "/sync.log");
        try {
            BoundedLogFile.append(log, entry);
        } catch (IOException e) {
            FLog.e("SyncLog", "Could not write history");
        }
    }

    public static long log(Context c, String title, String content, int type){
        JSONObject json = new JSONObject();
        long now = System.currentTimeMillis();
        try {
            json.put(TIMESTAMP, now);
            json.put(CONTENT, SecretRedactor.redact(content));
            json.put(TITLE, SecretRedactor.redact(title));
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
        File log = new File(c.getFilesDir().getPath() + "/sync.log");
        BoundedLogFile.delete(log);
    }

}
