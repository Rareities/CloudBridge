package ca.pkay.rcloneexplorer.Database.json;

import android.content.Context;

import org.json.JSONException;

import ca.pkay.rcloneexplorer.Database.DatabaseHandler;

public class Exporter {

    public static String create(Context context) throws JSONException {
        return new DatabaseHandler(context).exportSnapshot().toString();
    }
}
