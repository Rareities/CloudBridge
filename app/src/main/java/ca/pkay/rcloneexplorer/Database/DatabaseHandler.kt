package ca.pkay.rcloneexplorer.Database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.DATABASE_NAME
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.DATABASE_VERSION
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_CREATE_TABLES_TASKS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_CREATE_TABLE_FILTERS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_CREATE_TABLE_TRIGGER
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_UPDATE_TASK_ADD_DELETE_EXCLUDED
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_UPDATE_TASK_ADD_MD5
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_UPDATE_TASK_ADD_WIFI
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_UPDATE_TASK_ADD_FILTER_ID
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_UPDATE_TASK_ADD_FOLLOWUPS_FAIL
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_UPDATE_TASK_ADD_FOLLOWUPS_SUCCESS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_UPDATE_TASK_ADD_TRANSFERS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_UPDATE_TASK_ADD_REMOTE_ID2
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_UPDATE_TASK_ADD_REMOTE_TYPE2
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_UPDATE_TASK_ADD_REMOTE_PATH2
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_UPDATE_TRIGGER_ADD_TYPE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_CREATE_TABLE_PROFILES
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_CREATE_TABLE_RUNS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_CREATE_INDEX_ACTIVE_RUN
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_CREATE_TABLE_RESOURCE_CLAIMS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_CREATE_TABLE_BISYNC_PREFLIGHT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_UPDATE_BISYNC_PREFLIGHT_ADD_NATIVE_STATE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_UPDATE_BISYNC_PREFLIGHT_ADD_NATIVE_STATE_REASON
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_UPDATE_BISYNC_PREFLIGHT_ADD_RECOVERY_LISTINGS_VALID
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_UPDATE_BISYNC_PREFLIGHT_ADD_OBSERVATION_FINGERPRINT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_CREATE_TABLE_BISYNC_PREVIEWS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_CREATE_INDEX_ACTIVE_BISYNC_PREVIEW
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_CREATE_INDEX_BISYNC_PREVIEW_HISTORY
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_UPDATE_BISYNC_PREVIEW_ADD_INITIALIZATION_MODE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_CREATE_TABLE_BISYNC_BACKUP_MANIFESTS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_CREATE_TABLE_BISYNC_BACKUP_LOCATIONS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_CREATE_INDEX_ACTIVE_BISYNC_BACKUP_PROFILE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.SQL_CREATE_INDEX_BISYNC_BACKUP_HISTORY
import ca.pkay.rcloneexplorer.Items.Filter
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.Items.Trigger
import java.util.ArrayList
import java.util.HashMap

class DatabaseHandler(context: Context?) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(sqLiteDatabase: SQLiteDatabase) {
        super.onConfigure(sqLiteDatabase)
        sqLiteDatabase.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(sqLiteDatabase: SQLiteDatabase) {
        sqLiteDatabase.execSQL(SQL_CREATE_TABLES_TASKS)
        sqLiteDatabase.execSQL(SQL_CREATE_TABLE_TRIGGER)
        sqLiteDatabase.execSQL(SQL_CREATE_TABLE_FILTERS)
        sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_MD5)
        sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_WIFI)
        sqLiteDatabase.execSQL(SQL_UPDATE_TRIGGER_ADD_TYPE)
        sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_FILTER_ID)
        sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_DELETE_EXCLUDED)
        sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_FOLLOWUPS_FAIL)
        sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_FOLLOWUPS_SUCCESS)
        sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_TRANSFERS)
        sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_REMOTE_ID2)
        sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_REMOTE_TYPE2)
        sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_REMOTE_PATH2)
        sqLiteDatabase.execSQL(SQL_CREATE_TABLE_PROFILES)
        sqLiteDatabase.execSQL(SQL_CREATE_TABLE_RUNS)
        sqLiteDatabase.execSQL(SQL_CREATE_INDEX_ACTIVE_RUN)
        sqLiteDatabase.execSQL(SQL_CREATE_TABLE_RESOURCE_CLAIMS)
        sqLiteDatabase.execSQL(SQL_CREATE_TABLE_BISYNC_PREFLIGHT)
        sqLiteDatabase.execSQL(SQL_UPDATE_BISYNC_PREFLIGHT_ADD_NATIVE_STATE)
        sqLiteDatabase.execSQL(SQL_UPDATE_BISYNC_PREFLIGHT_ADD_NATIVE_STATE_REASON)
        sqLiteDatabase.execSQL(SQL_UPDATE_BISYNC_PREFLIGHT_ADD_RECOVERY_LISTINGS_VALID)
        sqLiteDatabase.execSQL(SQL_UPDATE_BISYNC_PREFLIGHT_ADD_OBSERVATION_FINGERPRINT)
        sqLiteDatabase.execSQL(SQL_CREATE_TABLE_BISYNC_PREVIEWS)
        sqLiteDatabase.execSQL(SQL_CREATE_INDEX_ACTIVE_BISYNC_PREVIEW)
        sqLiteDatabase.execSQL(SQL_CREATE_INDEX_BISYNC_PREVIEW_HISTORY)
        sqLiteDatabase.execSQL(SQL_CREATE_TABLE_BISYNC_BACKUP_MANIFESTS)
        sqLiteDatabase.execSQL(SQL_CREATE_TABLE_BISYNC_BACKUP_LOCATIONS)
        sqLiteDatabase.execSQL(SQL_CREATE_INDEX_ACTIVE_BISYNC_BACKUP_PROFILE)
        sqLiteDatabase.execSQL(SQL_CREATE_INDEX_BISYNC_BACKUP_HISTORY)
    }

    override fun onUpgrade(sqLiteDatabase: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            sqLiteDatabase.execSQL(SQL_CREATE_TABLE_TRIGGER)
        }
        if (oldVersion < 3) {
            sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_MD5)
            sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_WIFI)
        }
        if (oldVersion < 4) {
            sqLiteDatabase.execSQL(SQL_UPDATE_TRIGGER_ADD_TYPE)
        }
        if (oldVersion < 5) {
            sqLiteDatabase.execSQL(SQL_CREATE_TABLE_FILTERS)
            sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_FILTER_ID)
            sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_DELETE_EXCLUDED)
        }
        if (oldVersion < 6) {
            sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_FOLLOWUPS_FAIL)
            sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_FOLLOWUPS_SUCCESS)
        }
        if (oldVersion < 7) {
            sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_TRANSFERS)
        }
        if (oldVersion < 8) {
            sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_REMOTE_ID2)
            sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_REMOTE_TYPE2)
            sqLiteDatabase.execSQL(SQL_UPDATE_TASK_ADD_REMOTE_PATH2)
        }
        if (oldVersion < 9) {
            sqLiteDatabase.execSQL(SQL_CREATE_TABLE_PROFILES)
        }
        if (oldVersion < 10) {
            sqLiteDatabase.execSQL(SQL_CREATE_TABLE_RUNS)
            sqLiteDatabase.execSQL(SQL_CREATE_INDEX_ACTIVE_RUN)
        }
        if (oldVersion < 11) {
            sqLiteDatabase.execSQL(SQL_CREATE_TABLE_RESOURCE_CLAIMS)
        }
        if (oldVersion < 12) {
            sqLiteDatabase.execSQL(SQL_CREATE_TABLE_BISYNC_PREFLIGHT)
        }
        if (oldVersion < 13) {
            sqLiteDatabase.execSQL(SQL_UPDATE_BISYNC_PREFLIGHT_ADD_NATIVE_STATE)
            sqLiteDatabase.execSQL(SQL_UPDATE_BISYNC_PREFLIGHT_ADD_NATIVE_STATE_REASON)
            sqLiteDatabase.execSQL(SQL_UPDATE_BISYNC_PREFLIGHT_ADD_RECOVERY_LISTINGS_VALID)
        }
        if (oldVersion < 14) {
            sqLiteDatabase.execSQL(SQL_CREATE_TABLE_BISYNC_PREVIEWS)
            sqLiteDatabase.execSQL(SQL_CREATE_INDEX_ACTIVE_BISYNC_PREVIEW)
            sqLiteDatabase.execSQL(SQL_CREATE_INDEX_BISYNC_PREVIEW_HISTORY)
        }
        if (oldVersion == 14) {
            sqLiteDatabase.execSQL(SQL_UPDATE_BISYNC_PREVIEW_ADD_INITIALIZATION_MODE)
            // A v14 absent-state preview did not bind an explicit conflict preference. Never
            // invent path1 or keep such a result runnable. Running owners remain held and their
            // generation is invalidated so a late completion cannot clear the recovery gate.
            sqLiteDatabase.execSQL(
                "UPDATE ${DatabaseInfo.BISYNC_PREVIEW_TABLE_NAME} SET " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATUS} = 'RECOVERY_REQUIRED', " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_FAILURE_CODE} = 'INITIALIZATION_POLICY_MISSING', " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_OWNER_GENERATION} = " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_OWNER_GENERATION} + 1, " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPLETED_AT} = NULL, " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS} = NULL, " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_TRANSFERS} = NULL, " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_BYTES} = NULL, " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_FILE_DELETES} = NULL, " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_DIRECTORY_DELETES} = NULL, " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_ERROR_COUNT} = NULL " +
                    "WHERE ${DatabaseInfo.BISYNC_PREVIEW_COLUMN_NATIVE_STATE} = 'ABSENT' AND " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATUS} IN ('RUNNING','INTERRUPTED','RECOVERY_REQUIRED')"
            )
            sqLiteDatabase.execSQL(
                "UPDATE ${DatabaseInfo.BISYNC_PREVIEW_TABLE_NAME} SET " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATUS} = 'STALE', " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_FAILURE_CODE} = 'INITIALIZATION_POLICY_MISSING', " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPLETED_AT} = COALESCE(" +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPLETED_AT},${DatabaseInfo.BISYNC_PREVIEW_COLUMN_UPDATED_AT}), " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS} = NULL, " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_TRANSFERS} = NULL, " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_BYTES} = NULL, " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_FILE_DELETES} = NULL, " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_DIRECTORY_DELETES} = NULL, " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_ERROR_COUNT} = NULL " +
                    "WHERE ${DatabaseInfo.BISYNC_PREVIEW_COLUMN_NATIVE_STATE} = 'ABSENT' AND " +
                    "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATUS} NOT IN ('RUNNING','INTERRUPTED','RECOVERY_REQUIRED')"
            )
        }
        if (oldVersion < 16) {
            sqLiteDatabase.execSQL(SQL_UPDATE_BISYNC_PREFLIGHT_ADD_OBSERVATION_FINGERPRINT)
        }
        if (oldVersion < 16) {
            sqLiteDatabase.execSQL(SQL_CREATE_TABLE_BISYNC_BACKUP_MANIFESTS)
            sqLiteDatabase.execSQL(SQL_CREATE_TABLE_BISYNC_BACKUP_LOCATIONS)
            sqLiteDatabase.execSQL(SQL_CREATE_INDEX_ACTIVE_BISYNC_BACKUP_PROFILE)
            sqLiteDatabase.execSQL(SQL_CREATE_INDEX_BISYNC_BACKUP_HISTORY)
        }
    }

    val allTasks: List<Task>
        get() {
            val db = readableDatabase
            val selection = ""
            val selectionArgs = arrayOf<String>()
            val sortOrder = Task.COLUMN_NAME_ID + " ASC"
            val cursor = db.query(
                Task.TABLE_NAME,
                taskProjection,
                selection,
                selectionArgs,
                null,
                null,
                sortOrder
            )
            val results: MutableList<Task> = ArrayList()
            while (cursor.moveToNext()) {
                results.add(taskFromCursor(cursor))
            }
            cursor.close()
            db.close()
            return results
        }

    fun getTask(id: Long): Task? {
        val db = readableDatabase
        val selection = Task.COLUMN_NAME_ID + " LIKE ?"
        val selectionArgs = arrayOf(id.toString())
        val sortOrder = Task.COLUMN_NAME_ID + " ASC"
        val cursor = db.query(
            Task.TABLE_NAME,
            taskProjection,
            selection,
            selectionArgs,
            null,
            null,
            sortOrder
        )
        val results: MutableList<Task> = ArrayList()
        while (cursor.moveToNext()) {
            results.add(taskFromCursor(cursor))
        }
        cursor.close()
        db.close()
        return if (results.size == 0) {
            null
        } else results[0]
    }

    /** Read a task using a caller-owned transaction so the profile/run snapshot cannot race an edit. */
    internal fun getTaskInTransaction(db: SQLiteDatabase, id: Long): Task? {
        val cursor = db.query(
            Task.TABLE_NAME,
            taskProjection,
            Task.COLUMN_NAME_ID + " = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1"
        )
        return try {
            if (cursor.moveToFirst()) taskFromCursor(cursor) else null
        } finally {
            cursor.close()
        }
    }

    fun createTask(taskToStore: Task, withId: Boolean = false): Task {
        val db = writableDatabase
        val newRowId: Long
        db.beginTransaction()
        try {
            newRowId = db.insertOrThrow(
                Task.TABLE_NAME,
                null,
                if (withId) getTaskContentValuesWithID(taskToStore) else getTaskContentValues(taskToStore)
            )
            taskToStore.id = newRowId
            ProfileStore.upsertLegacyTask(db, taskToStore, EngineIdentity.current)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        return taskToStore
    }

    fun updateTask(taskToUpdate: Task) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val updated = db.update(
                Task.TABLE_NAME,
                getTaskContentValues(taskToUpdate),
                Task.COLUMN_NAME_ID + " = ?",
                arrayOf(taskToUpdate.id.toString())
            )
            if (updated == 0) {
                throw IllegalArgumentException("Task no longer exists")
            }
            ProfileStore.upsertLegacyTask(db, taskToUpdate, EngineIdentity.current)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    private val taskProjection: Array<String>
        get() = arrayOf(
            Task.COLUMN_NAME_ID,
            Task.COLUMN_NAME_TITLE,
            Task.COLUMN_NAME_REMOTE_ID,
            Task.COLUMN_NAME_REMOTE_TYPE,
            Task.COLUMN_NAME_REMOTE_PATH,
            Task.COLUMN_NAME_LOCAL_PATH,
            Task.COLUMN_NAME_SYNC_DIRECTION,
            Task.COLUMN_NAME_MD5SUM,
            Task.COLUMN_NAME_WIFI_ONLY,
            Task.COLUMN_NAME_FILTER_ID,
            Task.COLUMN_NAME_DELETE_EXCLUDED,
            Task.COLUMN_NAME_ONFAIL_FOLLOWUP,
            Task.COLUMN_NAME_ONSUCCESS_FOLLOWUP,
            Task.COLUMN_NAME_TRANSFERS,
            Task.COLUMN_NAME_REMOTE_ID2,
            Task.COLUMN_NAME_REMOTE_TYPE2,
            Task.COLUMN_NAME_REMOTE_PATH2
        )

    private fun taskFromCursor(cursor: Cursor): Task {
        val task = Task(cursor.getLong(0))
        task.title = cursor.getString(1) ?: ""
        task.remoteId = cursor.getString(2) ?: ""
        task.remoteType = cursor.getInt(3)
        // A SQL NULL endpoint is malformed, not a request to synchronize a provider root.
        // NUL is rejected by endpoint validation and preserves that distinction from "".
        task.remotePath = cursor.getString(4) ?: "\u0000"
        task.localPath = cursor.getString(5) ?: "\u0000"
        task.direction = cursor.getInt(6)
        task.md5sum = getBoolean(cursor, 7)
        task.wifionly = getBoolean(cursor, 8)
        task.filterId = if (cursor.isNull(9)) null else cursor.getLong(9)
        task.deleteExcluded = getBoolean(cursor, 10)
        task.onFailFollowup = if (cursor.isNull(11)) null else cursor.getLong(11)
        task.onSuccessFollowup = if (cursor.isNull(12)) null else cursor.getLong(12)
        task.transfers = if (cursor.isNull(13)) null else cursor.getInt(13)
        // Columns added in v8 are NULL for rows created under older schema versions; coalesce to defaults.
        task.remoteId2 = cursor.getString(14) ?: ""
        task.remoteType2 = cursor.getInt(15)
        task.remotePath2 = cursor.getString(16) ?: ""
        return task
    }

    private fun getTaskContentValuesWithID(task: Task): ContentValues {
        val values = getTaskContentValues(task)
        values.put(Task.COLUMN_NAME_ID, task.id)
        return values
    }

    fun deleteTask(id: Long): Int {
        val db = writableDatabase
        var retcode = 0
        db.beginTransaction()
        try {
            val selection = Task.COLUMN_NAME_ID + " = ?"
            val selectionArgs = arrayOf(id.toString())
            retcode = db.delete(Task.TABLE_NAME, selection, selectionArgs)
            if (retcode > 0) {
                ProfileStore.retireLegacyTask(db, id)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        return retcode
    }

    private fun getTaskContentValues(task: Task): ContentValues {
        val values = ContentValues()
        values.put(Task.COLUMN_NAME_TITLE, task.title)
        values.put(Task.COLUMN_NAME_LOCAL_PATH, task.localPath)
        values.put(Task.COLUMN_NAME_REMOTE_ID, task.remoteId)
        values.put(Task.COLUMN_NAME_REMOTE_PATH, task.remotePath)
        values.put(Task.COLUMN_NAME_REMOTE_TYPE, task.remoteType)
        values.put(Task.COLUMN_NAME_SYNC_DIRECTION, task.direction)
        values.put(Task.COLUMN_NAME_MD5SUM, task.md5sum)
        values.put(Task.COLUMN_NAME_WIFI_ONLY, task.wifionly)
        values.put(Task.COLUMN_NAME_FILTER_ID, task.filterId)
        values.put(Task.COLUMN_NAME_DELETE_EXCLUDED, task.deleteExcluded)
        values.put(Task.COLUMN_NAME_ONFAIL_FOLLOWUP, task.onFailFollowup)
        values.put(Task.COLUMN_NAME_ONSUCCESS_FOLLOWUP, task.onSuccessFollowup)
        values.put(Task.COLUMN_NAME_TRANSFERS, task.transfers)
        values.put(Task.COLUMN_NAME_REMOTE_ID2, task.remoteId2)
        values.put(Task.COLUMN_NAME_REMOTE_TYPE2, task.remoteType2)
        values.put(Task.COLUMN_NAME_REMOTE_PATH2, task.remotePath2)
        return values
    }

    val allTrigger: List<Trigger>
        get() {
            val db = readableDatabase
            val projection = triggerProjection
            val selection = ""
            val selectionArgs = arrayOf<String>()
            val sortOrder = Trigger.COLUMN_NAME_ID + " ASC"
            val cursor = db.query(
                    Trigger.TABLE_NAME,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                    null,
                    sortOrder
            )
            val results: MutableList<Trigger> = ArrayList()
            while (cursor.moveToNext()) {
                results.add(triggerFromCursor(cursor))
            }
            cursor.close()
            db.close()
            return results
        }

    fun getTrigger(id: Long): Trigger? {
        val db = readableDatabase
        val projection = triggerProjection
        val selection = Trigger.COLUMN_NAME_ID + " LIKE ?"
        val selectionArgs = arrayOf(id.toString())
        val sortOrder = Trigger.COLUMN_NAME_ID + " ASC"
        val cursor = db.query(
                Trigger.TABLE_NAME,
                projection,
                selection,
                selectionArgs,
                null,
                null,
                sortOrder
        )
        val results: MutableList<Trigger> = ArrayList()
        while (cursor.moveToNext()) {
            results.add(triggerFromCursor(cursor))
        }
        cursor.close()
        db.close()
        return if (results.size == 0) {
            null
        } else results[0]
    }

    fun createTrigger(triggerToStore: Trigger, withId: Boolean = false): Trigger {
        synchronized(TriggerStateLock.MONITOR) {
            val db = writableDatabase
            try {
                val newRowId = db.insert(
                    Trigger.TABLE_NAME,
                    null,
                    if (withId) getTriggerContentValuesWithID(triggerToStore) else getTriggerContentValues(triggerToStore)
                )
                triggerToStore.id = newRowId
                return triggerToStore
            } finally {
                db.close()
            }
        }
    }

    fun updateTrigger(triggerToUpdate: Trigger) {
        synchronized(TriggerStateLock.MONITOR) {
            val db = writableDatabase
            try {
                db.update(
                    Trigger.TABLE_NAME,
                    getTriggerContentValuesWithID(triggerToUpdate),
                    Trigger.COLUMN_NAME_ID + " = ?",
                    arrayOf(triggerToUpdate.id.toString())
                )
            } finally {
                db.close()
            }
        }
    }

    fun deleteTrigger(id: Long): Int {
        synchronized(TriggerStateLock.MONITOR) {
            val db = writableDatabase
            try {
                val selection = Trigger.COLUMN_NAME_ID + " LIKE ?"
                val selectionArgs = arrayOf(id.toString())
                return db.delete(Trigger.TABLE_NAME, selection, selectionArgs)
            } finally {
                db.close()
            }
        }
    }

    private fun getTriggerContentValuesWithID(t: Trigger): ContentValues {
        val values = getTriggerContentValues(t)
        values.put(Trigger.COLUMN_NAME_ID, t.id)
        return values
    }

    private fun getTriggerContentValues(t: Trigger): ContentValues {
        val values = ContentValues()
        if(t.id != Trigger.TRIGGER_ID_DOESNTEXIST) {
            values.put(Trigger.COLUMN_NAME_ID, t.id)
        }
        values.put(Trigger.COLUMN_NAME_TITLE, t.title)
        values.put(Trigger.COLUMN_NAME_ENABLED, t.isEnabled)
        values.put(Trigger.COLUMN_NAME_TIME, t.time)
        values.put(Trigger.COLUMN_NAME_WEEKDAY, t.getWeekdays())
        values.put(Trigger.COLUMN_NAME_TARGET, t.triggerTarget)
        values.put(Trigger.COLUMN_NAME_TYPE, t.type)
        return values
    }

    private val triggerProjection: Array<String>
        private get() = arrayOf(
                Trigger.COLUMN_NAME_ID,
                Trigger.COLUMN_NAME_TITLE,
                Trigger.COLUMN_NAME_ENABLED,
                Trigger.COLUMN_NAME_TIME,
                Trigger.COLUMN_NAME_WEEKDAY,
                Trigger.COLUMN_NAME_TARGET,
                Trigger.COLUMN_NAME_TYPE
        )

    private fun triggerFromCursor(cursor: Cursor): Trigger {
        val trigger = Trigger(cursor.getLong(0))
        trigger.title = cursor.getString(1)
        trigger.isEnabled = cursor.getInt(2) == 1
        trigger.time = cursor.getInt(3)
        val weekdays = cursor.getInt(4)
        trigger.setWeekdays(weekdays.toByte())
        trigger.triggerTarget = cursor.getLong(5)
        trigger.type = cursor.getInt(6)
        return trigger
    }

    val allFilters: List<Filter>
        get() {
            val db = readableDatabase
            val projection = filterProjection
            val selection = ""
            val selectionArgs = arrayOf<String>()
            val sortOrder = Filter.COLUMN_NAME_ID + " ASC"
            val cursor = db.query(
                    Filter.TABLE_NAME,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                    null,
                    sortOrder
            )
            val results: MutableList<Filter> = ArrayList()
            while (cursor.moveToNext()) {
                results.add(filterFromCursor(cursor))
            }
            cursor.close()
            db.close()
            return results
        }

    fun getFilter(id: Long): Filter? {
        val db = readableDatabase
        val projection = filterProjection
        val selection = Filter.COLUMN_NAME_ID + " LIKE ?"
        val selectionArgs = arrayOf(id.toString())
        val sortOrder = Filter.COLUMN_NAME_ID + " ASC"
        val cursor = db.query(
                Filter.TABLE_NAME,
                projection,
                selection,
                selectionArgs,
                null,
                null,
                sortOrder
        )
        val results: MutableList<Filter> = ArrayList()
        while (cursor.moveToNext()) {
            results.add(filterFromCursor(cursor))
        }
        cursor.close()
        db.close()
        return if (results.size == 0) {
            null
        } else results[0]
    }

    fun createFilter(filterToStore: Filter, withId: Boolean = false): Filter {
        val db = writableDatabase
        val newRowId = db.insert(Filter.TABLE_NAME, null, if(withId) getFilterContentValuesWithID(filterToStore) else getFilterContentValues(filterToStore))
        db.close()
        filterToStore.id = newRowId
        return filterToStore
    }

    fun updateFilter(filterToUpdate: Filter) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val updated = db.update(
                Filter.TABLE_NAME,
                getFilterContentValuesWithID(filterToUpdate),
                Filter.COLUMN_NAME_ID + " = ?",
                arrayOf(filterToUpdate.id.toString())
            )
            if (updated != 1) throw IllegalArgumentException("Filter no longer exists")
            refreshProfilesForTasks(db, tasksUsingFilter(db, filterToUpdate.id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    fun deleteFilter(id: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val affectedTasks = tasksUsingFilter(db, id)
            val deleted = db.delete(
                Filter.TABLE_NAME,
                Filter.COLUMN_NAME_ID + " = ?",
                arrayOf(id.toString())
            )
            if (deleted > 0) {
                // Enforce the documented ON DELETE SET NULL behavior even on databases where
                // SQLite foreign-key enforcement was disabled by an older helper instance.
                val unlink = ContentValues().apply { putNull(Task.COLUMN_NAME_FILTER_ID) }
                db.update(
                    Task.TABLE_NAME,
                    unlink,
                    Task.COLUMN_NAME_FILTER_ID + " = ?",
                    arrayOf(id.toString())
                )
                affectedTasks.forEach { it.filterId = null }
                refreshProfilesForTasks(db, affectedTasks)
            }
            db.setTransactionSuccessful()
            return deleted
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    /**
     * Replaces the user-owned task configuration as one SQLite transaction.
     *
     * Imported row ids are intentionally not reused.  This keeps an import from
     * accidentally colliding with a partially migrated database and lets the
     * references between filters, tasks and triggers be remapped after all rows
     * have been validated by the importer.
     */
    fun replaceAll(
        importedTriggers: List<Trigger>,
        importedFilters: List<Filter>,
        importedTasks: List<Task>
    ) {
        synchronized(TriggerStateLock.MONITOR) {
            replaceAllUnderTriggerLock(importedTriggers, importedFilters, importedTasks)
        }
    }

    private fun replaceAllUnderTriggerLock(
        importedTriggers: List<Trigger>,
        importedFilters: List<Filter>,
        importedTasks: List<Task>
    ) {
        val db = writableDatabase
        val filterIds = HashMap<Long, Long>()
        val taskIds = HashMap<Long, Long>()
        val insertedTasks = ArrayList<Pair<Long, Task>>()

        db.beginTransaction()
        try {
            db.delete(Trigger.TABLE_NAME, null, null)
            db.delete(Task.TABLE_NAME, null, null)
            db.delete(Filter.TABLE_NAME, null, null)

            for (filter in importedFilters) {
                val rowId = db.insertOrThrow(Filter.TABLE_NAME, null, getFilterContentValues(filter))
                filterIds[filter.id] = rowId
            }

            for (task in importedTasks) {
                val values = getTaskContentValues(task)
                val importedFilterId = task.filterId
                if (importedFilterId == null) {
                    values.putNull(Task.COLUMN_NAME_FILTER_ID)
                } else {
                    val filterId = filterIds[importedFilterId]
                        ?: throw IllegalArgumentException("Task references an unknown filter")
                    values.put(Task.COLUMN_NAME_FILTER_ID, filterId)
                }
                // Follow-up tasks are filled in after every task has a fresh id.
                values.putNull(Task.COLUMN_NAME_ONFAIL_FOLLOWUP)
                values.putNull(Task.COLUMN_NAME_ONSUCCESS_FOLLOWUP)
                val rowId = db.insertOrThrow(Task.TABLE_NAME, null, values)
                taskIds[task.id] = rowId
                insertedTasks.add(Pair(rowId, task))
            }

            for ((rowId, task) in insertedTasks) {
                val values = ContentValues()
                putMappedTaskReference(values, Task.COLUMN_NAME_ONFAIL_FOLLOWUP, task.onFailFollowup, taskIds)
                putMappedTaskReference(values, Task.COLUMN_NAME_ONSUCCESS_FOLLOWUP, task.onSuccessFollowup, taskIds)
                db.update(
                    Task.TABLE_NAME,
                    values,
                    Task.COLUMN_NAME_ID + " = ?",
                    arrayOf(rowId.toString())
                )
            }

            for (trigger in importedTriggers) {
                val targetId = taskIds[trigger.triggerTarget]
                    ?: throw IllegalArgumentException("Trigger references an unknown task")
                val values = getTriggerContentValues(trigger)
                values.put(Trigger.COLUMN_NAME_TARGET, targetId)
                db.insertOrThrow(Trigger.TABLE_NAME, null, values)
            }

            // Keep the UUID profile ledger in the same SQLite transaction as the legacy import.
            // Imported numeric IDs are the fresh database row IDs, never the source IDs.
            ProfileStore.reconcileImportedTasks(
                db,
                insertedTasks.map { (rowId, task) -> task.copy(id = rowId) },
                EngineIdentity.current
            )

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    private fun putMappedTaskReference(
        values: ContentValues,
        column: String,
        importedId: Long?,
        taskIds: HashMap<Long, Long>
    ) {
        if (importedId == null) {
            values.putNull(column)
        } else {
            values.put(column, taskIds[importedId]
                ?: throw IllegalArgumentException("Task references an unknown follow-up task"))
        }
    }

    private fun getFilterContentValuesWithID(t: Filter): ContentValues {
        val values = getFilterContentValues(t)
        values.put(Filter.COLUMN_NAME_ID, t.id)
        return values
    }

    private fun getFilterContentValues(t: Filter): ContentValues {
        val values = ContentValues()
        values.put(Filter.COLUMN_NAME_TITLE, t.title)
        values.put(Filter.COLUMN_NAME_FILTERS, t.getFiltersRaw())
        return values
    }

    /**
     * Create or refresh the durable UUID profile for every legacy numeric task. This is
     * idempotent and intentionally does not delete retired profile history.
     */
    fun reconcileLegacyProfiles(): Int {
        val tasks = allTasks
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (task in tasks) {
                ProfileStore.upsertLegacyTask(db, task, EngineIdentity.current)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        return tasks.size
    }

    private val filterProjection: Array<String>
        private get() = arrayOf(
                Filter.COLUMN_NAME_ID,
                Filter.COLUMN_NAME_TITLE,
                Filter.COLUMN_NAME_FILTERS,
        )

    private fun filterFromCursor(cursor: Cursor): Filter {
        val filter = Filter(cursor.getLong(0))
        filter.title = cursor.getString(1)
        filter.setFiltersRaw(cursor.getString(2))
        return filter
    }

    private fun tasksUsingFilter(db: SQLiteDatabase, filterId: Long): List<Task> {
        val cursor = db.query(
            Task.TABLE_NAME,
            taskProjection,
            Task.COLUMN_NAME_FILTER_ID + " = ?",
            arrayOf(filterId.toString()),
            null,
            null,
            Task.COLUMN_NAME_ID + " ASC"
        )
        return try {
            buildList {
                while (cursor.moveToNext()) add(taskFromCursor(cursor))
            }
        } finally {
            cursor.close()
        }
    }

    private fun refreshProfilesForTasks(db: SQLiteDatabase, tasks: List<Task>) {
        for (task in tasks) {
            ProfileStore.upsertLegacyTask(db, task, EngineIdentity.current)
        }
    }

    fun deleteEveryting() {
        for (trigger in allTrigger) {
            deleteTrigger(trigger.id)
        }
        for (task in allTasks) {
            deleteTask(task.id)
        }
        for (filter in allFilters) {
            deleteFilter(filter.id)
        }
    }

    private fun getBoolean(cursor: Cursor, cursorid: Int): Boolean {
        return when(cursor.getInt(cursorid)){
            0 -> false
            1 -> true
            else -> true
        }
    }
}
