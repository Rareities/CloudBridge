package ca.pkay.rcloneexplorer.Database

import android.content.Context
import ca.pkay.rcloneexplorer.Items.Task

/** Public profile facade; legacy numeric tasks are only a compatibility adapter. */
class ProfileRepository(context: Context) {
    private val context = context.applicationContext
    private val engineRef = EngineIdentity.current

    fun ensureLegacyTask(task: Task): ProfileRecord {
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        db.beginTransaction()
        return try {
            val profile = ProfileStore.upsertLegacyTask(db, task, engineRef)
            db.setTransactionSuccessful()
            profile
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    fun reconcileLegacyTasks(): Int = DatabaseHandler(context).reconcileLegacyProfiles()

    fun getForLegacyTask(taskId: Long): ProfileRecord? {
        val handler = DatabaseHandler(context)
        val db = handler.readableDatabase
        return try {
            ProfileStore.getByLegacyTaskId(db, taskId)
        } finally {
            db.close()
        }
    }

    fun get(profileId: String): ProfileRecord? {
        val handler = DatabaseHandler(context)
        val db = handler.readableDatabase
        return try {
            ProfileStore.getById(db, profileId)
        } finally {
            db.close()
        }
    }
}
