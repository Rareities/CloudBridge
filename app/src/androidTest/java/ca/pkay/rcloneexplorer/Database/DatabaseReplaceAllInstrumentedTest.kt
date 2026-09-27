package ca.pkay.rcloneexplorer.Database

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.platform.app.InstrumentationRegistry
import ca.pkay.rcloneexplorer.Database.json.Importer
import ca.pkay.rcloneexplorer.Items.Filter
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.Items.Trigger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class DatabaseReplaceAllInstrumentedTest {
    private lateinit var instrumentationContext: Context
    private lateinit var isolatedContext: Context
    private lateinit var databaseName: String
    private lateinit var databaseHandler: DatabaseHandler

    @Before
    fun setUp() {
        instrumentationContext = InstrumentationRegistry.getInstrumentation().context
        databaseName = "replace-all-${UUID.randomUUID()}.db"
        isolatedContext = object : ContextWrapper(instrumentationContext) {
            override fun getApplicationContext(): Context = this

            private fun requireApplicationDatabase(name: String?) {
                require(name == DatabaseInfo.DATABASE_NAME) {
                    "DatabaseHandler requested an unexpected database: $name"
                }
            }

            override fun getDatabasePath(name: String?): File =
                instrumentationContext.getDatabasePath(databaseName).also {
                    requireApplicationDatabase(name)
                }

            override fun openOrCreateDatabase(
                name: String?,
                mode: Int,
                factory: SQLiteDatabase.CursorFactory?
            ): SQLiteDatabase {
                requireApplicationDatabase(name)
                return instrumentationContext.openOrCreateDatabase(databaseName, mode, factory)
            }

            override fun openOrCreateDatabase(
                name: String?,
                mode: Int,
                factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ): SQLiteDatabase {
                requireApplicationDatabase(name)
                return instrumentationContext.openOrCreateDatabase(databaseName, mode, factory, errorHandler)
            }
        }
        databaseHandler = DatabaseHandler(isolatedContext)
    }

    @After
    fun tearDown() {
        if (::databaseHandler.isInitialized) {
            databaseHandler.close()
        }
        if (::instrumentationContext.isInitialized && ::databaseName.isInitialized) {
            // This UUID-owned file is isolated from the app database and other instrumentation tests.
            instrumentationContext.deleteDatabase(databaseName)
        }
    }

    @Test
    fun profileFailureRollsBackLegacyRowsProfilesAndActiveRuns() {
        val originalFilter = Filter(11L).apply {
            title = "preserve this filter"
            setFiltersRaw("+/disposable/import-rollback")
        }
        val original = Task(21L).apply {
            title = "preserve this task"
            remoteId = "test-account"
            remotePath = "disposable/import-rollback"
            localPath = "/disposable/import-rollback"
            direction = 2
            filterId = null
        }
        val originalTrigger = Trigger(31L).apply {
            title = "preserve this trigger"
            triggerTarget = original.id
        }
        databaseHandler.replaceAll(listOf(originalTrigger), listOf(originalFilter), listOf(original))

        val taskIdDatabase = databaseHandler.readableDatabase
        val originalTaskId = try {
            DatabaseUtils.longForQuery(
                taskIdDatabase,
                "SELECT ${Task.COLUMN_NAME_ID} FROM ${Task.TABLE_NAME} LIMIT 1",
                null
            )
        } finally {
            taskIdDatabase.close()
        }
        val runRepository = RunRepository(isolatedContext)
        val originalRun = runRepository.queueLegacyTask(originalTaskId)
        assertEquals(RunState.QUEUED, originalRun.state)
        val before = snapshotDatabase()

        val database = databaseHandler.writableDatabase
        try {
            database.execSQL(
                "CREATE TRIGGER fail_import_profile_insert BEFORE INSERT ON ${DatabaseInfo.PROFILE_TABLE_NAME} " +
                    "WHEN NEW.${DatabaseInfo.PROFILE_COLUMN_TITLE} = 'force profile rollback' " +
                    "BEGIN SELECT RAISE(ABORT, 'injected profile failure'); END"
            )
            database.execSQL(
                "CREATE TRIGGER fail_import_profile_update BEFORE UPDATE ON ${DatabaseInfo.PROFILE_TABLE_NAME} " +
                    "WHEN NEW.${DatabaseInfo.PROFILE_COLUMN_TITLE} = 'force profile rollback' " +
                    "BEGIN SELECT RAISE(ABORT, 'injected profile failure'); END"
            )
        } finally {
            database.close()
        }

        val importedTask = Task(101L).apply {
            title = "force profile rollback"
            remoteId = "test-account"
            remotePath = "disposable/import-replacement"
            localPath = "/disposable/import-replacement"
            direction = 2
        }
        val import = JSONObject()
            .put("trigger", JSONArray())
            .put("filters", JSONArray())
            .put("tasks", JSONArray().put(importedTask.asJSON()))
        assertThrows(SQLiteException::class.java) {
            Importer.importJson(import.toString(), isolatedContext)
        }

        assertEquals("all configuration and ownership rows must roll back", before, snapshotDatabase())
        assertEquals("the active run must not be invalidated by a failed import", originalRun,
            runRepository.get(originalRun.runId))
    }

    private fun snapshotDatabase(): Map<String, List<List<String?>>> {
        val database = databaseHandler.readableDatabase
        try {
            val tables = listOf(
                Filter.TABLE_NAME,
                Task.TABLE_NAME,
                Trigger.TABLE_NAME,
                DatabaseInfo.PROFILE_TABLE_NAME,
                DatabaseInfo.RUN_TABLE_NAME
            )
            val snapshot = LinkedHashMap<String, List<List<String?>>>()
            for (table in tables) {
                val cursor = database.query(table, null, null, null, null, null, null)
                try {
                    val rows = ArrayList<List<String?>>()
                    while (cursor.moveToNext()) {
                        val values = ArrayList<String?>(cursor.columnCount)
                        for (column in 0 until cursor.columnCount) {
                            values.add(if (cursor.isNull(column)) null else cursor.getString(column))
                        }
                        rows.add(values)
                    }
                    snapshot[table] = rows
                } finally {
                    cursor.close()
                }
            }
            return snapshot
        } finally {
            database.close()
        }
    }
}
