package ca.pkay.rcloneexplorer.Database.json;


import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import ca.pkay.rcloneexplorer.Database.DatabaseHandler;
import ca.pkay.rcloneexplorer.Items.Filter;
import ca.pkay.rcloneexplorer.Items.Task;
import ca.pkay.rcloneexplorer.Items.Trigger;

public class Importer {


    public static final class PreparedImport {
        public final ArrayList<Task> tasks;
        public final ArrayList<Trigger> triggers;
        public final ArrayList<Filter> filters;

        private PreparedImport(ArrayList<Task> tasks, ArrayList<Trigger> triggers,
                               ArrayList<Filter> filters) {
            this.tasks = tasks;
            this.triggers = triggers;
            this.filters = filters;
        }

        public void apply(Context context) {
            try (DatabaseHandler handler = new DatabaseHandler(context)) {
                handler.replaceAll(tasks, triggers, filters);
            }
        }
    }

    public static void importJson(String json, Context context) throws JSONException {
        prepareJson(json).apply(context);
    }

    /** Parse every table and validate identity/references before obtaining a writable database. */
    public static PreparedImport prepareJson(String json) throws JSONException {
        try {
            ArrayList<Task> tasks = createTasklist(json);
            ArrayList<Trigger> triggers = createTriggerlist(json);
            ArrayList<Filter> filters = createFilterList(json);
            Set<Long> taskIds = new HashSet<>();
            Set<Long> triggerIds = new HashSet<>();
            Set<Long> filterIds = new HashSet<>();
            Set<String> jobIds = new HashSet<>();
            for (Task task : tasks) {
                requireId(taskIds, task.getId());
                String uuid = java.util.UUID.fromString(task.getStableUUID()).toString();
                if (!uuid.equals(task.getStableUUID()) || !jobIds.add(uuid)) {
                    throw new JSONException("Invalid or duplicate job UUID");
                }
                if (task.getBisyncMaxDelete() < 0 || task.getBisyncMaxDelete() > 100) {
                    throw new JSONException("Invalid deletion percentage");
                }
            }
            for (Filter filter : filters) requireId(filterIds, filter.getId());
            for (Trigger trigger : triggers) {
                requireId(triggerIds, trigger.getId());
                if (!taskIds.contains(trigger.getTriggerTarget())) {
                    throw new JSONException("Trigger references a missing task");
                }
                if (trigger.getType() != Trigger.TRIGGER_TYPE_SCHEDULE
                        && trigger.getType() != Trigger.TRIGGER_TYPE_INTERVAL) {
                    throw new JSONException("Unsupported trigger type");
                }
                if (trigger.getType() == Trigger.TRIGGER_TYPE_SCHEDULE
                        ? trigger.getTime() < 0 || trigger.getTime() >= 24 * 60
                        : trigger.getTime() <= 0) {
                    throw new JSONException("Invalid trigger time");
                }
                if ((trigger.getWeekdays() & ~127) != 0) {
                    throw new JSONException("Invalid weekday mask");
                }
            }
            for (Task task : tasks) {
                requireReference(filterIds, task.getFilterId());
                requireReference(taskIds, task.getOnFailFollowup());
                requireReference(taskIds, task.getOnSuccessFollowup());
                if (task.getTransfers() != null && task.getTransfers() <= 0) {
                    throw new JSONException("Invalid transfer count");
                }
            }
            return new PreparedImport(tasks, triggers, filters);
        } catch (IllegalArgumentException e) {
            throw new JSONException("Invalid backup values");
        }
    }

    private static void requireId(Set<Long> ids, long id) throws JSONException {
        if (id < 0 || !ids.add(id)) throw new JSONException("Invalid or duplicate backup ID");
    }

    private static void requireReference(Set<Long> ids, Long id) throws JSONException {
        if (id != null && !ids.contains(id)) throw new JSONException("Missing backup reference");
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
