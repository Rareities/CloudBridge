package ca.pkay.rcloneexplorer.Activities

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import ca.pkay.rcloneexplorer.Database.BisyncComparisonMode
import ca.pkay.rcloneexplorer.Database.BisyncFilterParser
import ca.pkay.rcloneexplorer.Database.BisyncProfileFormInput
import ca.pkay.rcloneexplorer.Database.BisyncProfileFormIssue
import ca.pkay.rcloneexplorer.Database.BisyncProfileFormPolicy
import ca.pkay.rcloneexplorer.Database.BisyncProfileFormResult
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Database.ProfileMode
import ca.pkay.rcloneexplorer.Database.ProfileRepository
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Read-only legacy-backed Bisync profile form; Preview is available only for the saved task. */
class BisyncProfileActivity : AppCompatActivity() {
    companion object {
        const val LEGACY_TASK_ID_EXTRA = "BISYNC_PROFILE_LEGACY_TASK_ID"
    }

    private lateinit var taskId: EditText
    private lateinit var title: EditText
    private lateinit var localPath: EditText
    private lateinit var remoteId: EditText
    private lateinit var remotePath: EditText
    private lateinit var filterId: EditText
    private lateinit var comparison: Spinner
    private lateinit var deletePercent: EditText
    private lateinit var deleteCount: EditText
    private lateinit var status: TextView
    private lateinit var loadButton: Button
    private lateinit var previewButton: Button

    private var loadedTask: Task? = null
    private var loadedProfileIsBisync = false
    private var loadedFilterIsValid = false
    private var remoteTypesByName: Map<String, Int> = emptyMap()
    private var busy = false

    private val modes = arrayOf(
        BisyncComparisonMode.SIZE_AND_MODTIME,
        BisyncComparisonMode.SIZE_ONLY
    )

    private data class TaskInspection(
        val task: Task?,
        val profileIsBisync: Boolean,
        val filterIsValid: Boolean,
        val remoteTypesByName: Map<String, Int>,
        val message: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ca.pkay.rcloneexplorer.R.layout.activity_bisync_profile)

        val toolbar = findViewById<Toolbar>(R.id.bisync_profile_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Bisync profile"

        taskId = findViewById(R.id.bisync_profile_task_id)
        title = findViewById(R.id.bisync_profile_title)
        localPath = findViewById(R.id.bisync_profile_local_path)
        remoteId = findViewById(R.id.bisync_profile_remote_id)
        remotePath = findViewById(R.id.bisync_profile_remote_path)
        filterId = findViewById(R.id.bisync_profile_filter_id)
        comparison = findViewById(R.id.bisync_profile_comparison)
        deletePercent = findViewById(R.id.bisync_profile_delete_percent)
        deleteCount = findViewById(R.id.bisync_profile_delete_count)
        status = findViewById(R.id.bisync_profile_status)
        loadButton = findViewById(R.id.bisync_profile_load)
        previewButton = findViewById(R.id.bisync_profile_preview)

        comparison.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Size and modification time", "Size only")
        )
        comparison.setSelection(1)
        if (savedInstanceState == null) {
            deletePercent.setText(ca.pkay.rcloneexplorer.Database.BisyncPreflightPolicy.DEFAULT_MAX_DELETE_PERCENT.toString())
            deleteCount.setText(ca.pkay.rcloneexplorer.Database.BisyncPreflightPolicy.DEFAULT_MAX_DELETE_COUNT.toString())
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateActions()
            override fun afterTextChanged(s: Editable?) = Unit
        }
        listOf(taskId, title, localPath, remoteId, remotePath, filterId, deletePercent, deleteCount)
            .forEach { it.addTextChangedListener(watcher) }
        comparison.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = updateActions()
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) = updateActions()
        })

        loadButton.setOnClickListener { loadLegacyTask() }
        previewButton.setOnClickListener { openExistingPreview() }

        status.setText("Enter a saved legacy Bisync task ID, then load it.")
        val requestedId = intent.getLongExtra(LEGACY_TASK_ID_EXTRA, 0L)
        if (requestedId > 0L) {
            taskId.setText(requestedId.toString())
            loadLegacyTask()
        } else {
            updateActions()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadLegacyTask() {
        val id = taskId.text.toString().trim().toLongOrNull()
        if (id == null || id <= 0L) {
            loadedTask = null
            loadedProfileIsBisync = false
            loadedFilterIsValid = false
            remoteTypesByName = emptyMap()
            status.setText("Enter a positive ID for an existing legacy task.")
            updateActions()
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            val inspection = try {
                withContext(Dispatchers.IO) { inspectLegacyTask(id) }
            } catch (_: Exception) {
                TaskInspection(null, false, false, emptyMap(), "The legacy task could not be verified.")
            }
            loadedTask = inspection.task
            loadedProfileIsBisync = inspection.profileIsBisync
            loadedFilterIsValid = inspection.filterIsValid
            remoteTypesByName = inspection.remoteTypesByName
            status.text = inspection.message
            inspection.task?.let(::populate)
            setBusy(false)
            updateActions()
        }
    }

    private fun inspectLegacyTask(id: Long): TaskInspection {
        val handler = DatabaseHandler(applicationContext)
        val task = try {
            handler.getTask(id)
        } finally {
            handler.close()
        } ?: return TaskInspection(null, false, false, emptyMap(), "No saved task has that ID.")

        if (!isBisyncDirection(task.direction)) {
            return TaskInspection(null, false, false, emptyMap(), "That saved task is not a legacy Bisync task.")
        }

        val filterValid = filterIsValid(task.filterId)
        val profile = try {
            ProfileRepository(applicationContext).getForLegacyTask(id)
        } catch (_: Exception) {
            return TaskInspection(null, false, filterValid, emptyMap(), "The task profile could not be read.")
        }
        if (ProfileMode.fromLegacyDirection(task.direction) != ProfileMode.BISYNC ||
            (profile != null && profile.mode != ProfileMode.BISYNC)) {
            return TaskInspection(null, false, filterValid, emptyMap(), "The saved task has no valid Bisync profile mapping.")
        }

        val remoteTypes = try {
            Rclone(applicationContext).remotes.associate { it.name to it.type }
        } catch (_: Exception) {
            emptyMap()
        }
        return TaskInspection(
            task,
            true,
            filterValid,
            remoteTypes,
            "Legacy Bisync task loaded. Preview remains subject to the existing preflight."
        )
    }

    private fun populate(task: Task) {
        taskId.setText(task.id.toString())
        title.setText(task.title)
        localPath.setText(task.localPath)
        remoteId.setText(task.remoteId)
        remotePath.setText(task.remotePath)
        filterId.setText(task.filterId?.toString().orEmpty())
    }

    private fun openExistingPreview() {
        val loaded = loadedTask ?: return
        val capturedInput = currentInput()
        val initial = BisyncProfileFormPolicy.evaluate(capturedInput)
        if (!initial.canPreview) {
            status.text = describe(initial)
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            val stillValid = try {
                withContext(Dispatchers.IO) {
                val inspection = inspectLegacyTask(loaded.id)
                val current = inspection.task ?: return@withContext false
                if (!inspection.profileIsBisync || !sameTaskSnapshot(current, loaded)) return@withContext false
                val selectedRemoteId = capturedInput.remoteId.trim()
                val freshInput = capturedInput.copy(
                    verifiedLegacyTaskId = current.id,
                    legacyTaskIsBisync = true,
                    filterExistsAndIsValid = inspection.filterIsValid,
                    remoteExists = inspection.remoteTypesByName.containsKey(selectedRemoteId),
                    remoteIsLocal = inspection.remoteTypesByName[selectedRemoteId] == RemoteItem.LOCAL,
                    formMatchesVerifiedTask = formMatchesTask(capturedInput, current)
                )
                BisyncProfileFormPolicy.evaluate(freshInput).canPreview
                }
            } catch (_: Exception) {
                false
            }
            if (!stillValid) {
                loadedTask = null
                loadedProfileIsBisync = false
                loadedFilterIsValid = false
                remoteTypesByName = emptyMap()
                setBusy(false)
                status.setText("Preview is unavailable. Reload the task and verify its saved fields and filter.")
                updateActions()
                return@launch
            }
            setBusy(false)
            startActivity(
                Intent(this@BisyncProfileActivity, BisyncPreviewActivity::class.java)
                    .putExtra(BisyncPreviewActivity.ID_EXTRA, loaded.id)
            )
        }
    }

    private fun currentInput(): BisyncProfileFormInput {
        val task = loadedTask
        val filterText = filterId.text.toString().trim()
        val parsedFilterId = filterText.toLongOrNull()
        val filterValid = when {
            filterText.isEmpty() -> true
            parsedFilterId == null || parsedFilterId <= 0L -> false
            task != null && parsedFilterId == task.filterId -> loadedFilterIsValid
            else -> false
        }
        val selectedRemoteId = remoteId.text.toString().trim()
        val selectedRemoteType = remoteTypesByName[selectedRemoteId]
        val mode = modes.getOrNull(comparison.selectedItemPosition)
        return BisyncProfileFormInput(
            legacyTaskId = taskId.text.toString(),
            verifiedLegacyTaskId = task?.id,
            legacyTaskIsBisync = loadedProfileIsBisync,
            title = title.text.toString(),
            localPath = localPath.text.toString(),
            remoteId = remoteId.text.toString(),
            remotePath = remotePath.text.toString(),
            filterId = filterText,
            filterExistsAndIsValid = filterValid,
            comparisonMode = mode,
            maxDeletePercent = deletePercent.text.toString(),
            maxDeleteCount = deleteCount.text.toString(),
            remoteExists = selectedRemoteType != null,
            remoteIsLocal = selectedRemoteType == RemoteItem.LOCAL,
            formMatchesVerifiedTask = task != null && formMatchesTask(
                title.text.toString(),
                localPath.text.toString(),
                remoteId.text.toString(),
                remotePath.text.toString(),
                filterText,
                task
            )
        )
    }

    private fun updateActions() {
        if (!::previewButton.isInitialized) return
        val result = BisyncProfileFormPolicy.evaluate(currentInput())
        loadButton.isEnabled = !busy && taskId.text.toString().trim().toLongOrNull()?.let { it > 0L } == true
        previewButton.isEnabled = !busy && result.canPreview
        if (!busy && loadedTask != null) {
            status.text = if (result.canPreview) {
                "The form matches the saved Bisync task. Existing preflight checks still apply."
            } else {
                describe(result)
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        updateActions()
    }

    private fun filterIsValid(id: Long?): Boolean {
        if (id == null) return true
        val handler = DatabaseHandler(applicationContext)
        val selected = try {
            handler.getFilter(id)
        } finally {
            handler.close()
        } ?: return false
        return BisyncFilterParser.parse(selected.getFiltersRaw()).valid
    }

    private fun formMatchesTask(input: BisyncProfileFormInput, task: Task): Boolean = formMatchesTask(
        input.title, input.localPath, input.remoteId, input.remotePath, input.filterId, task
    )

    private fun formMatchesTask(
        editedTitle: String,
        editedLocalPath: String,
        editedRemoteId: String,
        editedRemotePath: String,
        editedFilterId: String,
        task: Task
    ): Boolean {
        val editedFilter = editedFilterId.trim().ifEmpty { null }?.toLongOrNull() ?:
            if (editedFilterId.trim().isEmpty()) null else return false
        return editedTitle.trim() == task.title.trim() &&
            BisyncProfileFormPolicy.normalizePath(editedLocalPath, requireAbsolute = true) ==
                BisyncProfileFormPolicy.normalizePath(task.localPath, requireAbsolute = true) &&
            editedRemoteId.trim() == task.remoteId.trim() &&
            BisyncProfileFormPolicy.normalizePath(editedRemotePath, requireAbsolute = false) ==
                BisyncProfileFormPolicy.normalizePath(task.remotePath, requireAbsolute = false) &&
            editedFilter == task.filterId
    }

    private fun sameTaskSnapshot(left: Task, right: Task): Boolean =
        left.id == right.id && left.title == right.title &&
            left.localPath == right.localPath && left.remoteId == right.remoteId &&
            left.remoteType == right.remoteType && left.remotePath == right.remotePath &&
            left.direction == right.direction && left.md5sum == right.md5sum &&
            left.wifionly == right.wifionly && left.filterId == right.filterId &&
            left.deleteExcluded == right.deleteExcluded &&
            left.onFailFollowup == right.onFailFollowup &&
            left.onSuccessFollowup == right.onSuccessFollowup && left.transfers == right.transfers &&
            left.remoteId2 == right.remoteId2 && left.remoteType2 == right.remoteType2 &&
            left.remotePath2 == right.remotePath2

    private fun isBisyncDirection(direction: Int): Boolean =
        direction == SyncDirectionObject.SYNC_BIDIRECTIONAL_INITIAL ||
            direction == SyncDirectionObject.SYNC_BIDIRECTIONAL

    private fun describe(result: BisyncProfileFormResult): String = when {
        BisyncProfileFormIssue.LEGACY_TASK_ID_REQUIRED in result.issues ||
            BisyncProfileFormIssue.LEGACY_TASK_ID_INVALID in result.issues ||
            BisyncProfileFormIssue.LEGACY_TASK_NOT_VERIFIED in result.issues ->
            "Load an existing legacy Bisync task before Preview."
        BisyncProfileFormIssue.REMOTE_UNAVAILABLE in result.issues ->
            "Choose a remote ID that exists in the current rclone configuration."
        BisyncProfileFormIssue.FILTER_ID_INVALID in result.issues ||
            BisyncProfileFormIssue.FILTER_UNAVAILABLE in result.issues ->
            "The optional filter ID must identify an existing filter with valid rules."
        BisyncProfileFormIssue.FORM_DIFFERS_FROM_SAVED_TASK in result.issues ->
            "Preview requires fields that match the saved task. Reload it to restore its saved values."
        BisyncProfileFormIssue.ENDPOINTS_OVERLAP in result.issues ->
            "The local and remote paths overlap and cannot be used as separate Bisync endpoints."
        result.issues.isNotEmpty() -> "Review the title, paths, comparison mode, and delete limits."
        else -> "The current fields are valid."
    }
}
