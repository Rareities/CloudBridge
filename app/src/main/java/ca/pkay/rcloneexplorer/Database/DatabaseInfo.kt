package ca.pkay.rcloneexplorer.Database

import ca.pkay.rcloneexplorer.Items.Filter
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.Items.Trigger

class DatabaseInfo {

    companion object {

        // If you change the database schema, you must increment the database version.
        const val DATABASE_VERSION = 12
        const val DATABASE_NAME = "rcloneExplorer.db"

        const val PROFILE_TABLE_NAME = "profile_table"
        const val PROFILE_COLUMN_ID = "profile_id"
        const val PROFILE_COLUMN_LEGACY_TASK_ID = "profile_legacy_task_id"
        const val PROFILE_COLUMN_REVISION = "profile_revision"
        const val PROFILE_COLUMN_TITLE = "profile_title"
        const val PROFILE_COLUMN_MODE = "profile_mode"
        const val PROFILE_COLUMN_ENDPOINT = "profile_endpoint_identity"
        const val PROFILE_COLUMN_SETTINGS = "profile_settings"
        const val PROFILE_COLUMN_FINGERPRINT = "profile_fingerprint"
        const val PROFILE_COLUMN_ENGINE = "profile_engine_ref"
        const val PROFILE_COLUMN_READINESS = "profile_readiness"
        const val PROFILE_COLUMN_REASON = "profile_reason"
        const val PROFILE_COLUMN_CREATED_AT = "profile_created_at"
        const val PROFILE_COLUMN_UPDATED_AT = "profile_updated_at"

        const val RUN_TABLE_NAME = "run_table"
        const val RUN_COLUMN_ID = "run_id"
        const val RUN_COLUMN_PROFILE_ID = "run_profile_id"
        const val RUN_COLUMN_PROFILE_REVISION = "run_profile_revision"
        const val RUN_COLUMN_PROFILE_FINGERPRINT = "run_profile_fingerprint"
        const val RUN_COLUMN_REQUESTED_MODE = "run_requested_mode"
        const val RUN_COLUMN_ENDPOINT = "run_endpoint_identity"
        const val RUN_COLUMN_SETTINGS = "run_settings"
        const val RUN_COLUMN_ENGINE = "run_engine_ref"
        const val RUN_COLUMN_STATE = "run_state"
        const val RUN_COLUMN_REASON = "run_reason"
        const val RUN_COLUMN_REQUESTED_AT = "run_requested_at"
        const val RUN_COLUMN_DUE_AT = "run_due_at"
        const val RUN_COLUMN_STARTED_AT = "run_started_at"
        const val RUN_COLUMN_FINISHED_AT = "run_finished_at"
        const val RUN_COLUMN_OWNER_TOKEN = "run_owner_token"
        const val RUN_COLUMN_OWNER_GENERATION = "run_owner_generation"
        const val RUN_COLUMN_CANCEL_REQUESTED = "run_cancel_requested"
        const val RUN_COLUMN_SUCCESSFUL_ITEMS = "run_successful_items"
        const val RUN_COLUMN_FAILED_ITEMS = "run_failed_items"
        const val RUN_COLUMN_CONFLICT_ITEMS = "run_conflict_items"
        const val RUN_COLUMN_UNKNOWN_ITEMS = "run_unknown_items"
        const val RUN_COLUMN_CREATED_AT = "run_created_at"
        const val RUN_COLUMN_UPDATED_AT = "run_updated_at"

        const val CLAIM_TABLE_NAME = "resource_claim_table"
        const val CLAIM_COLUMN_ID = "claim_id"
        const val CLAIM_COLUMN_OPERATION = "claim_operation"
        const val CLAIM_COLUMN_RESOURCES = "claim_resources"
        const val CLAIM_COLUMN_CREATED_AT = "claim_created_at"

        const val BISYNC_PREFLIGHT_TABLE_NAME = "bisync_preflight_table"
        const val BISYNC_PREFLIGHT_COLUMN_PROFILE_ID = "bisync_preflight_profile_id"
        const val BISYNC_PREFLIGHT_COLUMN_PROFILE_REVISION = "bisync_preflight_profile_revision"
        const val BISYNC_PREFLIGHT_COLUMN_PROFILE_FINGERPRINT = "bisync_preflight_profile_fingerprint"
        const val BISYNC_PREFLIGHT_COLUMN_ENGINE_REF = "bisync_preflight_engine_ref"
        const val BISYNC_PREFLIGHT_COLUMN_STATE_VERSION = "bisync_preflight_state_version"
        const val BISYNC_PREFLIGHT_COLUMN_LEFT_ACCOUNT = "bisync_preflight_left_account_fingerprint"
        const val BISYNC_PREFLIGHT_COLUMN_LEFT_SCOPE = "bisync_preflight_left_scope_fingerprint"
        const val BISYNC_PREFLIGHT_COLUMN_RIGHT_ACCOUNT = "bisync_preflight_right_account_fingerprint"
        const val BISYNC_PREFLIGHT_COLUMN_RIGHT_SCOPE = "bisync_preflight_right_scope_fingerprint"
        const val BISYNC_PREFLIGHT_COLUMN_FILTER = "bisync_preflight_filter_fingerprint"
        const val BISYNC_PREFLIGHT_COLUMN_COMPARISON = "bisync_preflight_comparison_mode"
        const val BISYNC_PREFLIGHT_COLUMN_FINGERPRINT = "bisync_preflight_fingerprint"
        const val BISYNC_PREFLIGHT_COLUMN_READINESS = "bisync_preflight_readiness"
        const val BISYNC_PREFLIGHT_COLUMN_REASON = "bisync_preflight_reason_code"
        const val BISYNC_PREFLIGHT_COLUMN_CHECKED_AT = "bisync_preflight_checked_at"


        val SQL_CREATE_TABLES_TASKS = "CREATE TABLE " + Task.TABLE_NAME + " (" +
                Task.COLUMN_NAME_ID + " INTEGER PRIMARY KEY," +
                Task.COLUMN_NAME_TITLE + " TEXT," +
                Task.COLUMN_NAME_REMOTE_ID + " TEXT," +
                Task.COLUMN_NAME_REMOTE_TYPE + " INTEGER," +
                Task.COLUMN_NAME_REMOTE_PATH + " TEXT," +
                Task.COLUMN_NAME_LOCAL_PATH + " TEXT," +
                Task.COLUMN_NAME_SYNC_DIRECTION + " INTEGER)"

        val SQL_CREATE_TABLE_TRIGGER = "CREATE TABLE " + Trigger.TABLE_NAME + " (" +
                Trigger.COLUMN_NAME_ID + " INTEGER PRIMARY KEY," +
                Trigger.COLUMN_NAME_TITLE + " TEXT," +
                Trigger.COLUMN_NAME_ENABLED + " INTEGER," +
                Trigger.COLUMN_NAME_TIME + " INTEGER," +
                Trigger.COLUMN_NAME_WEEKDAY + " INTEGER," +
                Trigger.COLUMN_NAME_TARGET + " INTEGER)"

        val SQL_CREATE_TABLE_FILTERS = "CREATE TABLE " + Filter.TABLE_NAME + " (" +
                Filter.COLUMN_NAME_ID + " INTEGER PRIMARY KEY," +
                Filter.COLUMN_NAME_TITLE + " TEXT," +
                Filter.COLUMN_NAME_FILTERS + " TEXT)"


        val SQL_UPDATE_TASK_ADD_MD5 = "ALTER TABLE ${Task.TABLE_NAME} ADD COLUMN ${Task.COLUMN_NAME_MD5SUM} INTEGER"
        val SQL_UPDATE_TASK_ADD_WIFI = "ALTER TABLE ${Task.TABLE_NAME} ADD COLUMN ${Task.COLUMN_NAME_WIFI_ONLY} INTEGER"
        val SQL_UPDATE_TRIGGER_ADD_TYPE = "ALTER TABLE ${Trigger.TABLE_NAME} ADD COLUMN ${Trigger.COLUMN_NAME_TYPE} INTEGER DEFAULT ${Trigger.TRIGGER_TYPE_SCHEDULE}"
        val SQL_UPDATE_TASK_ADD_FILTER_ID = "ALTER TABLE ${Task.TABLE_NAME} ADD COLUMN ${Task.COLUMN_NAME_FILTER_ID} INTEGER REFERENCES ${Filter.TABLE_NAME}(${Filter.COLUMN_NAME_ID}) ON DELETE SET NULL"
        val SQL_UPDATE_TASK_ADD_DELETE_EXCLUDED = "ALTER TABLE ${Task.TABLE_NAME} ADD COLUMN ${Task.COLUMN_NAME_DELETE_EXCLUDED} INTEGER"
        val SQL_UPDATE_TASK_ADD_FOLLOWUPS_FAIL = "ALTER TABLE ${Task.TABLE_NAME} ADD COLUMN ${Task.COLUMN_NAME_ONFAIL_FOLLOWUP} INTEGER"
        val SQL_UPDATE_TASK_ADD_FOLLOWUPS_SUCCESS = "ALTER TABLE ${Task.TABLE_NAME} ADD COLUMN ${Task.COLUMN_NAME_ONSUCCESS_FOLLOWUP} INTEGER"
        val SQL_UPDATE_TASK_ADD_TRANSFERS = "ALTER TABLE ${Task.TABLE_NAME} ADD COLUMN ${Task.COLUMN_NAME_TRANSFERS} INTEGER"
        val SQL_UPDATE_TASK_ADD_REMOTE_ID2 = "ALTER TABLE ${Task.TABLE_NAME} ADD COLUMN ${Task.COLUMN_NAME_REMOTE_ID2} TEXT"
        val SQL_UPDATE_TASK_ADD_REMOTE_TYPE2 = "ALTER TABLE ${Task.TABLE_NAME} ADD COLUMN ${Task.COLUMN_NAME_REMOTE_TYPE2} INTEGER"
        val SQL_UPDATE_TASK_ADD_REMOTE_PATH2 = "ALTER TABLE ${Task.TABLE_NAME} ADD COLUMN ${Task.COLUMN_NAME_REMOTE_PATH2} TEXT"

        val SQL_CREATE_TABLE_PROFILES = "CREATE TABLE IF NOT EXISTS $PROFILE_TABLE_NAME (" +
                "$PROFILE_COLUMN_ID TEXT PRIMARY KEY NOT NULL," +
                "$PROFILE_COLUMN_LEGACY_TASK_ID INTEGER UNIQUE," +
                "$PROFILE_COLUMN_REVISION INTEGER NOT NULL," +
                "$PROFILE_COLUMN_TITLE TEXT NOT NULL," +
                "$PROFILE_COLUMN_MODE TEXT NOT NULL," +
                "$PROFILE_COLUMN_ENDPOINT TEXT NOT NULL," +
                "$PROFILE_COLUMN_SETTINGS TEXT NOT NULL," +
                "$PROFILE_COLUMN_FINGERPRINT TEXT NOT NULL," +
                "$PROFILE_COLUMN_ENGINE TEXT NOT NULL," +
                "$PROFILE_COLUMN_READINESS TEXT NOT NULL," +
                "$PROFILE_COLUMN_REASON TEXT," +
                "$PROFILE_COLUMN_CREATED_AT INTEGER NOT NULL," +
                "$PROFILE_COLUMN_UPDATED_AT INTEGER NOT NULL)"

        val SQL_CREATE_TABLE_RUNS = "CREATE TABLE IF NOT EXISTS $RUN_TABLE_NAME (" +
                "$RUN_COLUMN_ID TEXT PRIMARY KEY NOT NULL," +
                "$RUN_COLUMN_PROFILE_ID TEXT NOT NULL REFERENCES $PROFILE_TABLE_NAME($PROFILE_COLUMN_ID)," +
                "$RUN_COLUMN_PROFILE_REVISION INTEGER NOT NULL," +
                "$RUN_COLUMN_PROFILE_FINGERPRINT TEXT NOT NULL," +
                "$RUN_COLUMN_REQUESTED_MODE TEXT NOT NULL," +
                "$RUN_COLUMN_ENDPOINT TEXT NOT NULL," +
                "$RUN_COLUMN_SETTINGS TEXT NOT NULL," +
                "$RUN_COLUMN_ENGINE TEXT NOT NULL," +
                "$RUN_COLUMN_STATE TEXT NOT NULL," +
                "$RUN_COLUMN_REASON TEXT," +
                "$RUN_COLUMN_REQUESTED_AT INTEGER NOT NULL," +
                "$RUN_COLUMN_DUE_AT INTEGER," +
                "$RUN_COLUMN_STARTED_AT INTEGER," +
                "$RUN_COLUMN_FINISHED_AT INTEGER," +
                "$RUN_COLUMN_OWNER_TOKEN TEXT NOT NULL," +
                "$RUN_COLUMN_OWNER_GENERATION INTEGER NOT NULL DEFAULT 0," +
                "$RUN_COLUMN_CANCEL_REQUESTED INTEGER NOT NULL DEFAULT 0," +
                "$RUN_COLUMN_SUCCESSFUL_ITEMS INTEGER," +
                "$RUN_COLUMN_FAILED_ITEMS INTEGER," +
                "$RUN_COLUMN_CONFLICT_ITEMS INTEGER," +
                "$RUN_COLUMN_UNKNOWN_ITEMS INTEGER," +
                "$RUN_COLUMN_CREATED_AT INTEGER NOT NULL," +
                "$RUN_COLUMN_UPDATED_AT INTEGER NOT NULL)"

        val SQL_CREATE_INDEX_ACTIVE_RUN = "CREATE UNIQUE INDEX IF NOT EXISTS run_one_active_profile " +
                "ON $RUN_TABLE_NAME($RUN_COLUMN_PROFILE_ID) " +
                "WHERE $RUN_COLUMN_STATE IN ('QUEUED','PREFLIGHT','RUNNING')"

        val SQL_CREATE_TABLE_RESOURCE_CLAIMS = "CREATE TABLE IF NOT EXISTS $CLAIM_TABLE_NAME (" +
                "$CLAIM_COLUMN_ID TEXT PRIMARY KEY NOT NULL," +
                "$CLAIM_COLUMN_OPERATION TEXT NOT NULL," +
                "$CLAIM_COLUMN_RESOURCES TEXT NOT NULL," +
                "$CLAIM_COLUMN_CREATED_AT INTEGER NOT NULL)"

        val SQL_CREATE_TABLE_BISYNC_PREFLIGHT = "CREATE TABLE IF NOT EXISTS $BISYNC_PREFLIGHT_TABLE_NAME (" +
                "$BISYNC_PREFLIGHT_COLUMN_PROFILE_ID TEXT PRIMARY KEY NOT NULL REFERENCES $PROFILE_TABLE_NAME($PROFILE_COLUMN_ID) ON DELETE CASCADE," +
                "$BISYNC_PREFLIGHT_COLUMN_PROFILE_REVISION INTEGER NOT NULL," +
                "$BISYNC_PREFLIGHT_COLUMN_PROFILE_FINGERPRINT TEXT NOT NULL," +
                "$BISYNC_PREFLIGHT_COLUMN_ENGINE_REF TEXT NOT NULL," +
                "$BISYNC_PREFLIGHT_COLUMN_STATE_VERSION INTEGER NOT NULL," +
                "$BISYNC_PREFLIGHT_COLUMN_LEFT_ACCOUNT TEXT," +
                "$BISYNC_PREFLIGHT_COLUMN_LEFT_SCOPE TEXT," +
                "$BISYNC_PREFLIGHT_COLUMN_RIGHT_ACCOUNT TEXT," +
                "$BISYNC_PREFLIGHT_COLUMN_RIGHT_SCOPE TEXT," +
                "$BISYNC_PREFLIGHT_COLUMN_FILTER TEXT," +
                "$BISYNC_PREFLIGHT_COLUMN_COMPARISON TEXT," +
                "$BISYNC_PREFLIGHT_COLUMN_FINGERPRINT TEXT," +
                "$BISYNC_PREFLIGHT_COLUMN_READINESS TEXT NOT NULL," +
                "$BISYNC_PREFLIGHT_COLUMN_REASON TEXT," +
                "$BISYNC_PREFLIGHT_COLUMN_CHECKED_AT INTEGER NOT NULL)"

    }
}
