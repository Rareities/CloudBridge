package io.github.rareities.protonbridge.Database.json;

import android.content.Context;

import org.json.JSONException;

import io.github.rareities.protonbridge.Database.DatabaseHandler;

public class Exporter {

    public static String create(Context context) throws JSONException {
        return new DatabaseHandler(context).exportSnapshot().toString();
    }
}
