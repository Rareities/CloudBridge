package ca.pkay.rcloneexplorer.Database.json;


import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ca.pkay.rcloneexplorer.Database.DatabaseHandler;
import ca.pkay.rcloneexplorer.Database.TriggerStateLock;
import ca.pkay.rcloneexplorer.Items.Filter;
import ca.pkay.rcloneexplorer.Items.Task;
import ca.pkay.rcloneexplorer.Items.Trigger;
import ca.pkay.rcloneexplorer.Services.TriggerService;

public class Importer {

    /** Keep malformed or hostile backup input from consuming unbounded parser memory. */
    public static final int MAX_IMPORT_CHARS = 1024 * 1024;
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_TITLE_CHARS = 16 * 1024;
    private static final int MAX_PATH_CHARS = 64 * 1024;

    public static final class ParsedImport {
        private final ArrayList<Trigger> triggers;
        private final ArrayList<Filter> filters;
        private final ArrayList<Task> tasks;

        private ParsedImport(ArrayList<Trigger> triggers, ArrayList<Filter> filters,
                             ArrayList<Task> tasks) {
            this.triggers = triggers;
            this.filters = filters;
            this.tasks = tasks;
        }

        public List<Trigger> getTriggers() {
            return triggers;
        }

        public List<Filter> getFilters() {
            return filters;
        }

        public List<Task> getTasks() {
            return tasks;
        }
    }


    public static void importJson(String json, Context context) throws JSONException {
        ParsedImport parsed = parse(json);
        DatabaseHandler dbHandler = new DatabaseHandler(context);
        try {
            synchronized (TriggerStateLock.MONITOR) {
                ArrayList<Long> previousTriggerIds = new ArrayList<>();
                for (Trigger trigger : dbHandler.getAllTrigger()) {
                    previousTriggerIds.add(trigger.getId());
                }
                dbHandler.replaceAll(parsed.triggers, parsed.filters, parsed.tasks);

                TriggerService triggerService = new TriggerService(context);
                try {
                    for (Long previousTriggerId : previousTriggerIds) {
                        triggerService.cancelTrigger(previousTriggerId);
                    }
                    triggerService.queueTrigger();
                } finally {
                    triggerService.close();
                }
            }
        } finally {
            dbHandler.close();
        }
    }

    public static ArrayList<Trigger> createTriggerlist(String content) throws JSONException {
        return parse(content).triggers;
    }

    public static ArrayList<Task> createTasklist(String content) throws JSONException {
        return parse(content).tasks;
    }
    public static ArrayList<Filter> createFilterList(String content) throws JSONException {
        return parse(content).filters;
    }

    /** Parse and validate the entire import before any database mutation occurs. */
    public static ParsedImport parse(String content) throws JSONException {
        if (content == null || content.trim().isEmpty()) {
            throw new JSONException("Import is empty");
        }
        if (content.length() > MAX_IMPORT_CHARS) {
            throw new JSONException("Import exceeds the maximum size");
        }

        final JSONObject reader;
        try {
            reader = new JSONObject(content);
        } catch (JSONException e) {
            throw new JSONException("Import JSON is malformed");
        }

        ArrayList<Trigger> triggers = parseTriggers(array(reader, "trigger"));
        ArrayList<Filter> filters = parseFilters(array(reader, "filters"));
        // Tasks are the authoritative replacement set. A missing array must not be
        // interpreted as an intentional empty set, otherwise a partial/old backup can
        // erase every existing task during replaceAll(). An explicit [] remains valid.
        ArrayList<Task> tasks = parseTasks(requiredArray(reader, "tasks"));
        validateReferences(triggers, filters, tasks);
        return new ParsedImport(triggers, filters, tasks);
    }

    public static void validate(String content) throws JSONException {
        parse(content);
    }

    private static JSONArray array(JSONObject reader, String name) throws JSONException {
        if (!reader.has(name) || reader.isNull(name)) {
            return new JSONArray();
        }
        Object value = reader.get(name);
        if (!(value instanceof JSONArray)) {
            throw new JSONException("Import field is not an array: " + name);
        }
        JSONArray result = (JSONArray) value;
        if (result.length() > MAX_ENTRIES) {
            throw new JSONException("Import array exceeds the maximum size: " + name);
        }
        return result;
    }

    private static JSONArray requiredArray(JSONObject reader, String name) throws JSONException {
        if (!reader.has(name) || reader.isNull(name)) {
            throw new JSONException("Import is missing required array: " + name);
        }
        return array(reader, name);
    }

    private static ArrayList<Trigger> parseTriggers(JSONArray array) throws JSONException {
        ArrayList<Trigger> result = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = objectAt(array, i, "trigger");
            Trigger trigger = decodeTrigger(object);
            requireNewImportId(trigger.getId(), ids, "trigger", i);
            requireLength(trigger.getTitle(), MAX_TITLE_CHARS, "trigger title");
            result.add(trigger);
        }
        return result;
    }

    private static ArrayList<Filter> parseFilters(JSONArray array) throws JSONException {
        ArrayList<Filter> result = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = objectAt(array, i, "filter");
            Filter filter = decodeFilter(object);
            requireNewImportId(filter.getId(), ids, "filter", i);
            requireLength(filter.getTitle(), MAX_TITLE_CHARS, "filter title");
            requireLength(filter.getFiltersRaw(), MAX_PATH_CHARS, "filter rules");
            result.add(filter);
        }
        return result;
    }

    private static ArrayList<Task> parseTasks(JSONArray array) throws JSONException {
        ArrayList<Task> result = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = objectAt(array, i, "task");
            Task task = decodeTask(object);
            requireNewImportId(task.getId(), ids, "task", i);
            requireLength(task.getTitle(), MAX_TITLE_CHARS, "task title");
            requireLength(task.getRemoteId(), MAX_TITLE_CHARS, "remote name");
            requireLength(task.getRemotePath(), MAX_PATH_CHARS, "remote path");
            requireLength(task.getLocalPath(), MAX_PATH_CHARS, "local path");
            requireLength(task.getRemoteId2(), MAX_TITLE_CHARS, "destination remote name");
            requireLength(task.getRemotePath2(), MAX_PATH_CHARS, "destination remote path");
            result.add(task);
        }
        return result;
    }

    private static JSONObject objectAt(JSONArray array, int index, String kind) throws JSONException {
        try {
            return array.getJSONObject(index);
        } catch (JSONException e) {
            throw new JSONException("Import " + kind + " entry is not an object");
        }
    }

    private static Trigger decodeTrigger(JSONObject object) throws JSONException {
        try {
            return Trigger.Companion.fromString(object.toString());
        } catch (RuntimeException e) {
            throw new JSONException("Import trigger entry is invalid");
        }
    }

    private static Filter decodeFilter(JSONObject object) throws JSONException {
        try {
            return Filter.Companion.fromString(object.toString());
        } catch (RuntimeException e) {
            throw new JSONException("Import filter entry is invalid");
        }
    }

    private static Task decodeTask(JSONObject object) throws JSONException {
        try {
            return Task.Companion.fromString(object.toString());
        } catch (RuntimeException e) {
            throw new JSONException("Import task entry is invalid");
        }
    }

    private static void requireNewImportId(long id, Set<Long> ids, String kind, int index)
            throws JSONException {
        if (id <= 0 || !ids.add(id)) {
            throw new JSONException("Import " + kind + " entry has an invalid or duplicate id at index " + index);
        }
    }

    private static void requireLength(String value, int max, String label) throws JSONException {
        if (value != null && value.length() > max) {
            throw new JSONException("Import field exceeds the maximum size: " + label);
        }
    }

    private static void validateReferences(ArrayList<Trigger> triggers, ArrayList<Filter> filters,
                                           ArrayList<Task> tasks) throws JSONException {
        Set<Long> filterIds = new HashSet<>();
        for (Filter filter : filters) {
            filterIds.add(filter.getId());
        }
        Set<Long> taskIds = new HashSet<>();
        for (Task task : tasks) {
            taskIds.add(task.getId());
        }
        for (Task task : tasks) {
            requireReference(task.getFilterId(), filterIds, "filter", task.getId());
            requireReference(task.getOnFailFollowup(), taskIds, "failure follow-up task", task.getId());
            requireReference(task.getOnSuccessFollowup(), taskIds, "success follow-up task", task.getId());
        }
        for (Trigger trigger : triggers) {
            requireReference(trigger.getTriggerTarget(), taskIds, "trigger task", trigger.getId());
        }
    }

    private static void requireReference(Long reference, Set<Long> ids, String kind, long ownerId)
            throws JSONException {
        if (reference != null && !ids.contains(reference)) {
            throw new JSONException("Import entry " + ownerId + " references an unknown " + kind);
        }
    }
}
