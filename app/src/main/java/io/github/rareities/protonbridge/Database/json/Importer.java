package io.github.rareities.protonbridge.Database.json;


import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;

import io.github.rareities.protonbridge.Database.DatabaseHandler;
import io.github.rareities.protonbridge.Items.Filter;
import io.github.rareities.protonbridge.Items.Task;
import io.github.rareities.protonbridge.Items.Trigger;

public class Importer {


    public static void importJson(String json, Context context) throws JSONException {
        ImportPlan plan = ImportPlan.parse(json);
        io.github.rareities.protonbridge.util.BisyncPolicy.Admission admission =
                io.github.rareities.protonbridge.util.BisyncPolicy.Admission.Companion.acquire(context);
        if (admission == null) throw new JSONException("A transfer is active. Finish or cancel it before restoring tasks.");
        try {
            new DatabaseHandler(context).replaceAll(plan.getTasks(), plan.getFilters(), plan.getTriggers());
        } finally {
            admission.close();
        }
    }

    public static ArrayList<Trigger> createTriggerlist(String content) throws JSONException {
        ArrayList<Trigger> result = new ArrayList<>();
        JSONObject reader = new JSONObject(content);
        JSONArray array = reader.getJSONArray("trigger");
        for (int i = 0; i < array.length(); i++) {
            JSONObject triggerObject = array.getJSONObject(i);
            result.add(Trigger.Companion.fromString(triggerObject.toString()));
        }
        return result;
    }

    public static ArrayList<Task> createTasklist(String content) throws JSONException {
        ArrayList<Task> result = new ArrayList<>();
        JSONObject reader = new JSONObject(content);
        JSONArray array = reader.getJSONArray("tasks");
        for (int i = 0; i < array.length(); i++) {
            JSONObject taskObject = array.getJSONObject(i);
            result.add(Task.Companion.fromString(taskObject.toString()));
        }
        return result;
    }
    public static ArrayList<Filter> createFilterList(String content) throws JSONException {
        ArrayList<Filter> result = new ArrayList<>();
        JSONObject reader = new JSONObject(content);
        JSONArray array = reader.getJSONArray("filters");
        for (int i = 0; i < array.length(); i++) {
            JSONObject filterObject = array.getJSONObject(i);
            result.add(Filter.Companion.fromString(filterObject.toString()));
        }
        return result;
    }
}
