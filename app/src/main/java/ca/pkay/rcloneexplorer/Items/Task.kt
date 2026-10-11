package ca.pkay.rcloneexplorer.Items

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import org.json.JSONObject

@Serializable
data class Task(var id: Long) {
    // Alternatives are kept for backwards compatibility with old, manual parser
    @JsonNames("name") var title = ""
    var remoteId = ""
    var remoteType = 0
    var remotePath = ""
    var localPath = ""
    // Destination remote for cloud-to-cloud sync/copy (directions 7/8); empty for local<->remote tasks.
    var remoteId2 = ""
    var remoteType2 = 0
    var remotePath2 = ""
    @JsonNames("syncDirection") var direction = 0
    var md5sum = TASK_MD5SUM_DEFAULT
    var wifionly = TASK_WIFIONLY_DEFAULT
    var filterId: Long? = null
    var deleteExcluded = false
    var onFailFollowup: Long? = null
    var onSuccessFollowup: Long? = null
    var transfers: Int? = null
    // Stable identity prevents reuse of another job's bisync listings if an ID is recycled.
    var bisyncUUID = ""
    var bisyncAdvancedOptions = ""
    var bisyncMaxDelete = 10
    var bisyncResyncMode = "path1"
    var bisyncCheckAccess = false
    var bisyncBackupDir1 = ""
    var bisyncBackupDir2 = ""
    var chargingOnly = false
    var batteryNotLow = false
    var quietHoursStart = -1
    var quietHoursEnd = -1
    @kotlinx.serialization.Transient var validationError: String? = null

    override fun toString(): String {
        return "$title: $remoteId: $remoteType: $remotePath: $localPath: $remoteId2: $remoteType2: $remotePath2: $direction"
    }

    companion object {
        var TABLE_NAME = "task_table"
        var COLUMN_NAME_ID = "task_id"
        var COLUMN_NAME_TITLE = "task_title"
        var COLUMN_NAME_REMOTE_ID = "task_remote_id"
        var COLUMN_NAME_REMOTE_TYPE = "task_remote_type"
        var COLUMN_NAME_REMOTE_PATH = "task_remote_path"
        var COLUMN_NAME_LOCAL_PATH = "task_local_path"
        var COLUMN_NAME_REMOTE_ID2 = "task_remote_id2"
        var COLUMN_NAME_REMOTE_TYPE2 = "task_remote_type2"
        var COLUMN_NAME_REMOTE_PATH2 = "task_remote_path2"
        var COLUMN_NAME_SYNC_DIRECTION = "task_direction"
        var COLUMN_NAME_MD5SUM = "task_use_md5sum"
        var COLUMN_NAME_WIFI_ONLY = "task_use_only_wifi"
        var COLUMN_NAME_FILTER_ID = "task_filter_id"
        var COLUMN_NAME_DELETE_EXCLUDED = "task_delete_excluded"
        var COLUMN_NAME_ONFAIL_FOLLOWUP = "task_onFailFollowupTask"
        var COLUMN_NAME_ONSUCCESS_FOLLOWUP = "task_onSuccessFollowupTask"
        var COLUMN_NAME_TRANSFERS = "task_transfers"
        const val COLUMN_NAME_BISYNC_UUID = "task_bisync_uuid"
        const val COLUMN_NAME_BISYNC_ADVANCED = "task_bisync_advanced"
        const val COLUMN_NAME_BISYNC_MAX_DELETE = "task_bisync_max_delete"
        const val COLUMN_NAME_POLICY = "task_policy"

        const val TASK_MD5SUM_DEFAULT = false
        const val TASK_WIFIONLY_DEFAULT = false

        fun fromString(json: String): Task {
            return Json.decodeFromString(json)
        }
    }

    fun asJSON(): JSONObject {
        return JSONObject(Json.encodeToString(this))
    }

    fun policyJSON(): String = JSONObject().apply {
        put("version", 1)
        put("resyncMode", bisyncResyncMode)
        put("checkAccess", bisyncCheckAccess)
        put("backup1", bisyncBackupDir1)
        put("backup2", bisyncBackupDir2)
        put("chargingOnly", chargingOnly)
        put("batteryNotLow", batteryNotLow)
        put("quietStart", quietHoursStart)
        put("quietEnd", quietHoursEnd)
    }.toString()

    fun readPolicyJSON(value: String?) {
        if (value.isNullOrBlank()) return
        try {
            val json = JSONObject(value)
            require(json.getInt("version") == 1) { "Unsupported task policy version" }
            bisyncResyncMode = json.getString("resyncMode")
            bisyncCheckAccess = json.getBoolean("checkAccess")
            bisyncBackupDir1 = json.getString("backup1")
            bisyncBackupDir2 = json.getString("backup2")
            chargingOnly = json.getBoolean("chargingOnly")
            batteryNotLow = json.getBoolean("batteryNotLow")
            quietHoursStart = json.getInt("quietStart")
            quietHoursEnd = json.getInt("quietEnd")
        } catch (_: Exception) {
            validationError = "Task settings are invalid or from an unsupported version. Review this task before running it."
        }
    }
}
