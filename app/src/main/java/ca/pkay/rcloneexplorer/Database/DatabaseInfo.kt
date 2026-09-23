package ca.pkay.rcloneexplorer.Database

import ca.pkay.rcloneexplorer.Items.Filter
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.Items.Trigger

class DatabaseInfo {

    companion object {

        // If you change the database schema, you must increment the database version.
        const val DATABASE_VERSION = 14
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
        const val BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE = "bisync_preflight_native_state"
        const val BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE_REASON = "bisync_preflight_native_state_reason"
        const val BISYNC_PREFLIGHT_COLUMN_RECOVERY_LISTINGS_VALID = "bisync_preflight_recovery_listings_valid"

        const val BISYNC_PREVIEW_TABLE_NAME = "bisync_preview_table"
        const val BISYNC_PREVIEW_COLUMN_ID = "bisync_preview_id"
        const val BISYNC_PREVIEW_COLUMN_PROFILE_ID = "bisync_preview_profile_id"
        const val BISYNC_PREVIEW_COLUMN_PROFILE_REVISION = "bisync_preview_profile_revision"
        const val BISYNC_PREVIEW_COLUMN_PROFILE_FINGERPRINT = "bisync_preview_profile_fingerprint"
        const val BISYNC_PREVIEW_COLUMN_ENGINE_REF = "bisync_preview_engine_ref"
        const val BISYNC_PREVIEW_COLUMN_STATE_VERSION = "bisync_preview_state_version"
        const val BISYNC_PREVIEW_COLUMN_LEFT_ACCOUNT = "bisync_preview_left_account_fingerprint"
        const val BISYNC_PREVIEW_COLUMN_LEFT_SCOPE = "bisync_preview_left_scope_fingerprint"
        const val BISYNC_PREVIEW_COLUMN_RIGHT_ACCOUNT = "bisync_preview_right_account_fingerprint"
        const val BISYNC_PREVIEW_COLUMN_RIGHT_SCOPE = "bisync_preview_right_scope_fingerprint"
        const val BISYNC_PREVIEW_COLUMN_FILTER = "bisync_preview_filter_fingerprint"
        const val BISYNC_PREVIEW_COLUMN_COMPARISON = "bisync_preview_comparison_mode"
        const val BISYNC_PREVIEW_COLUMN_MAX_DELETE_PERCENT = "bisync_preview_max_delete_percent"
        const val BISYNC_PREVIEW_COLUMN_MAX_DELETE_COUNT = "bisync_preview_max_delete_count"
        const val BISYNC_PREVIEW_COLUMN_NATIVE_STATE = "bisync_preview_native_state"
        const val BISYNC_PREVIEW_COLUMN_ACCEPTED_BASELINE = "bisync_preview_accepted_baseline_fingerprint"
        const val BISYNC_PREVIEW_COLUMN_IDENTITY_FINGERPRINT = "bisync_preview_identity_fingerprint"
        const val BISYNC_PREVIEW_COLUMN_STATUS = "bisync_preview_status"
        const val BISYNC_PREVIEW_COLUMN_OWNER_TOKEN = "bisync_preview_owner_token"
        const val BISYNC_PREVIEW_COLUMN_OWNER_GENERATION = "bisync_preview_owner_generation"
        const val BISYNC_PREVIEW_COLUMN_REQUESTED_AT = "bisync_preview_requested_at"
        const val BISYNC_PREVIEW_COLUMN_STARTED_AT = "bisync_preview_started_at"
        const val BISYNC_PREVIEW_COLUMN_COMPLETED_AT = "bisync_preview_completed_at"
        const val BISYNC_PREVIEW_COLUMN_UPDATED_AT = "bisync_preview_updated_at"
        const val BISYNC_PREVIEW_COLUMN_FAILURE_CODE = "bisync_preview_failure_code"
        const val BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS = "bisync_preview_summary_status"
        const val BISYNC_PREVIEW_COLUMN_PLANNED_TRANSFERS = "bisync_preview_planned_transfers"
        const val BISYNC_PREVIEW_COLUMN_PLANNED_BYTES = "bisync_preview_planned_bytes"
        const val BISYNC_PREVIEW_COLUMN_PLANNED_FILE_DELETES = "bisync_preview_planned_file_deletes"
        const val BISYNC_PREVIEW_COLUMN_PLANNED_DIRECTORY_DELETES = "bisync_preview_planned_directory_deletes"
        const val BISYNC_PREVIEW_COLUMN_ERROR_COUNT = "bisync_preview_error_count"
        const val BISYNC_PREVIEW_COLUMN_CONFLICTS_KNOWN = "bisync_preview_conflicts_known"


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

        val SQL_UPDATE_BISYNC_PREFLIGHT_ADD_NATIVE_STATE =
            "ALTER TABLE $BISYNC_PREFLIGHT_TABLE_NAME ADD COLUMN $BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE TEXT NOT NULL DEFAULT 'UNKNOWN'"
        val SQL_UPDATE_BISYNC_PREFLIGHT_ADD_NATIVE_STATE_REASON =
            "ALTER TABLE $BISYNC_PREFLIGHT_TABLE_NAME ADD COLUMN $BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE_REASON TEXT NOT NULL DEFAULT 'STATE_NOT_RECORDED'"
        val SQL_UPDATE_BISYNC_PREFLIGHT_ADD_RECOVERY_LISTINGS_VALID =
            "ALTER TABLE $BISYNC_PREFLIGHT_TABLE_NAME ADD COLUMN $BISYNC_PREFLIGHT_COLUMN_RECOVERY_LISTINGS_VALID INTEGER NOT NULL DEFAULT 0"

        val SQL_CREATE_TABLE_BISYNC_PREVIEWS = "CREATE TABLE IF NOT EXISTS $BISYNC_PREVIEW_TABLE_NAME (" +
                "$BISYNC_PREVIEW_COLUMN_ID TEXT PRIMARY KEY NOT NULL," +
                "$BISYNC_PREVIEW_COLUMN_PROFILE_ID TEXT NOT NULL REFERENCES $PROFILE_TABLE_NAME($PROFILE_COLUMN_ID) ON DELETE CASCADE," +
                "$BISYNC_PREVIEW_COLUMN_PROFILE_REVISION INTEGER NOT NULL CHECK ($BISYNC_PREVIEW_COLUMN_PROFILE_REVISION > 0)," +
                "$BISYNC_PREVIEW_COLUMN_PROFILE_FINGERPRINT TEXT NOT NULL," +
                "$BISYNC_PREVIEW_COLUMN_ENGINE_REF TEXT NOT NULL," +
                "$BISYNC_PREVIEW_COLUMN_STATE_VERSION INTEGER NOT NULL CHECK ($BISYNC_PREVIEW_COLUMN_STATE_VERSION > 0)," +
                "$BISYNC_PREVIEW_COLUMN_LEFT_ACCOUNT TEXT NOT NULL," +
                "$BISYNC_PREVIEW_COLUMN_LEFT_SCOPE TEXT NOT NULL," +
                "$BISYNC_PREVIEW_COLUMN_RIGHT_ACCOUNT TEXT NOT NULL," +
                "$BISYNC_PREVIEW_COLUMN_RIGHT_SCOPE TEXT NOT NULL," +
                "$BISYNC_PREVIEW_COLUMN_FILTER TEXT NOT NULL," +
                "$BISYNC_PREVIEW_COLUMN_COMPARISON TEXT NOT NULL," +
                "$BISYNC_PREVIEW_COLUMN_MAX_DELETE_PERCENT INTEGER NOT NULL CHECK ($BISYNC_PREVIEW_COLUMN_MAX_DELETE_PERCENT BETWEEN 1 AND 100)," +
                "$BISYNC_PREVIEW_COLUMN_MAX_DELETE_COUNT INTEGER NOT NULL CHECK ($BISYNC_PREVIEW_COLUMN_MAX_DELETE_COUNT > 0)," +
                "$BISYNC_PREVIEW_COLUMN_NATIVE_STATE TEXT NOT NULL CHECK ($BISYNC_PREVIEW_COLUMN_NATIVE_STATE IN ('ABSENT','COMPATIBLE'))," +
                "$BISYNC_PREVIEW_COLUMN_ACCEPTED_BASELINE TEXT," +
                "$BISYNC_PREVIEW_COLUMN_IDENTITY_FINGERPRINT TEXT NOT NULL," +
                "$BISYNC_PREVIEW_COLUMN_STATUS TEXT NOT NULL CHECK ($BISYNC_PREVIEW_COLUMN_STATUS IN ('QUEUED','RUNNING','COMPLETE','INCOMPLETE','UNAVAILABLE','CANCELLED','STALE','INTERRUPTED','RECOVERY_REQUIRED'))," +
                "$BISYNC_PREVIEW_COLUMN_OWNER_TOKEN TEXT NOT NULL," +
                "$BISYNC_PREVIEW_COLUMN_OWNER_GENERATION INTEGER NOT NULL DEFAULT 0 CHECK ($BISYNC_PREVIEW_COLUMN_OWNER_GENERATION >= 0)," +
                "$BISYNC_PREVIEW_COLUMN_REQUESTED_AT INTEGER NOT NULL CHECK ($BISYNC_PREVIEW_COLUMN_REQUESTED_AT > 0)," +
                "$BISYNC_PREVIEW_COLUMN_STARTED_AT INTEGER," +
                "$BISYNC_PREVIEW_COLUMN_COMPLETED_AT INTEGER," +
                "$BISYNC_PREVIEW_COLUMN_FAILURE_CODE TEXT," +
                "$BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS TEXT CHECK ($BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS IN ('COMPLETE','INCOMPLETE'))," +
                "$BISYNC_PREVIEW_COLUMN_PLANNED_TRANSFERS INTEGER CHECK ($BISYNC_PREVIEW_COLUMN_PLANNED_TRANSFERS >= 0)," +
                "$BISYNC_PREVIEW_COLUMN_PLANNED_BYTES INTEGER CHECK ($BISYNC_PREVIEW_COLUMN_PLANNED_BYTES >= 0)," +
                "$BISYNC_PREVIEW_COLUMN_PLANNED_FILE_DELETES INTEGER CHECK ($BISYNC_PREVIEW_COLUMN_PLANNED_FILE_DELETES >= 0)," +
                "$BISYNC_PREVIEW_COLUMN_PLANNED_DIRECTORY_DELETES INTEGER CHECK ($BISYNC_PREVIEW_COLUMN_PLANNED_DIRECTORY_DELETES >= 0)," +
                "$BISYNC_PREVIEW_COLUMN_ERROR_COUNT INTEGER CHECK ($BISYNC_PREVIEW_COLUMN_ERROR_COUNT >= 0)," +
                "$BISYNC_PREVIEW_COLUMN_CONFLICTS_KNOWN INTEGER NOT NULL DEFAULT 0 CHECK ($BISYNC_PREVIEW_COLUMN_CONFLICTS_KNOWN = 0)," +
                "$BISYNC_PREVIEW_COLUMN_UPDATED_AT INTEGER NOT NULL CHECK ($BISYNC_PREVIEW_COLUMN_UPDATED_AT > 0)," +
                "CHECK (($BISYNC_PREVIEW_COLUMN_NATIVE_STATE = 'ABSENT' AND $BISYNC_PREVIEW_COLUMN_ACCEPTED_BASELINE IS NULL) OR " +
                "($BISYNC_PREVIEW_COLUMN_NATIVE_STATE = 'COMPATIBLE' AND $BISYNC_PREVIEW_COLUMN_ACCEPTED_BASELINE IS NOT NULL))," +
                "CHECK (($BISYNC_PREVIEW_COLUMN_STATUS = 'COMPLETE' AND $BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS = 'COMPLETE' " +
                "AND $BISYNC_PREVIEW_COLUMN_PLANNED_TRANSFERS IS NOT NULL AND $BISYNC_PREVIEW_COLUMN_PLANNED_BYTES IS NOT NULL " +
                "AND $BISYNC_PREVIEW_COLUMN_PLANNED_FILE_DELETES IS NOT NULL AND $BISYNC_PREVIEW_COLUMN_PLANNED_DIRECTORY_DELETES IS NOT NULL " +
                "AND $BISYNC_PREVIEW_COLUMN_ERROR_COUNT = 0 AND $BISYNC_PREVIEW_COLUMN_COMPLETED_AT IS NOT NULL) OR " +
                "($BISYNC_PREVIEW_COLUMN_STATUS = 'INCOMPLETE' AND $BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS = 'INCOMPLETE' " +
                "AND $BISYNC_PREVIEW_COLUMN_PLANNED_TRANSFERS IS NOT NULL AND $BISYNC_PREVIEW_COLUMN_PLANNED_BYTES IS NOT NULL " +
                "AND $BISYNC_PREVIEW_COLUMN_PLANNED_FILE_DELETES IS NOT NULL AND $BISYNC_PREVIEW_COLUMN_PLANNED_DIRECTORY_DELETES IS NOT NULL " +
                "AND $BISYNC_PREVIEW_COLUMN_ERROR_COUNT IS NOT NULL AND $BISYNC_PREVIEW_COLUMN_COMPLETED_AT IS NOT NULL) OR " +
                "($BISYNC_PREVIEW_COLUMN_STATUS = 'UNAVAILABLE' AND $BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS IS NULL " +
                "AND $BISYNC_PREVIEW_COLUMN_FAILURE_CODE IS NOT NULL AND $BISYNC_PREVIEW_COLUMN_COMPLETED_AT IS NOT NULL) OR " +
                "($BISYNC_PREVIEW_COLUMN_STATUS IN ('STALE','CANCELLED') AND $BISYNC_PREVIEW_COLUMN_COMPLETED_AT IS NOT NULL) OR " +
                "($BISYNC_PREVIEW_COLUMN_STATUS IN ('QUEUED','RUNNING','INTERRUPTED','RECOVERY_REQUIRED') " +
                "AND $BISYNC_PREVIEW_COLUMN_COMPLETED_AT IS NULL)))"

        val SQL_CREATE_INDEX_ACTIVE_BISYNC_PREVIEW =
            "CREATE UNIQUE INDEX IF NOT EXISTS bisync_preview_one_owner " +
                    "ON $BISYNC_PREVIEW_TABLE_NAME($BISYNC_PREVIEW_COLUMN_PROFILE_ID) " +
                    "WHERE $BISYNC_PREVIEW_COLUMN_STATUS IN ('QUEUED','RUNNING','INTERRUPTED','RECOVERY_REQUIRED')"

        val SQL_CREATE_INDEX_BISYNC_PREVIEW_HISTORY =
            "CREATE INDEX IF NOT EXISTS bisync_preview_history " +
                    "ON $BISYNC_PREVIEW_TABLE_NAME($BISYNC_PREVIEW_COLUMN_PROFILE_ID,$BISYNC_PREVIEW_COLUMN_REQUESTED_AT DESC)"

    }
}
