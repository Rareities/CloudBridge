package ca.pkay.rcloneexplorer.Activities

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ca.pkay.rcloneexplorer.Database.BisyncComparisonMode
import ca.pkay.rcloneexplorer.Database.BisyncPreflightPolicy
import ca.pkay.rcloneexplorer.Database.BisyncPreflightRepository
import ca.pkay.rcloneexplorer.Database.BisyncPreviewFailureCode
import ca.pkay.rcloneexplorer.Database.BisyncPreviewFreshness
import ca.pkay.rcloneexplorer.Database.BisyncPreviewFreshnessPolicy
import ca.pkay.rcloneexplorer.Database.BisyncPreviewOperation
import ca.pkay.rcloneexplorer.Database.BisyncPreviewOperationState
import ca.pkay.rcloneexplorer.Database.BisyncPreviewRepository
import ca.pkay.rcloneexplorer.Database.BisyncPreviewResyncMode
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Database.ProfileMode
import ca.pkay.rcloneexplorer.Database.ProfileRecord
import ca.pkay.rcloneexplorer.Database.ProfileRepository
import ca.pkay.rcloneexplorer.Database.BisyncPreflightStatus
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.workmanager.BisyncPreviewAdmissionScheduler
import ca.pkay.rcloneexplorer.workmanager.BisyncPreviewAdmissionWorker
import ca.pkay.rcloneexplorer.workmanager.BisyncPreviewWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Read-only preflight and preview launch/review surface; it exposes no apply or recovery action. */
class BisyncPreviewActivity : AppCompatActivity() {
    companion object {
        const val ID_EXTRA = "BISYNC_PREVIEW_TASK_ID"
    }

    private data class Snapshot(
        val task: Task,
        val profile: ProfileRecord,
        val active: BisyncPreviewOperation?,
        val history: List<BisyncPreviewOperation>,
        val preflight: BisyncPreflightStatus?
    )

    private lateinit var taskTitle: TextView
    private lateinit var status: TextView
    private lateinit var historyView: TextView
    private lateinit var comparison: Spinner
    private lateinit var maxDeletePercent: EditText
    private lateinit var maxDeleteCount: EditText
    private lateinit var startButton: Button
    private lateinit var cancelButton: Button

    private var taskId = 0L
    private var snapshot: Snapshot? = null
    private var admissionInfos: List<WorkInfo> = emptyList()
    private var previewInfos: List<WorkInfo> = emptyList()
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bisync_preview)
        val toolbar = findViewById<Toolbar>(R.id.bisync_preview_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.bisync_preview_title)

        taskTitle = findViewById(R.id.bisync_preview_task_title)
        status = findViewById(R.id.bisync_preview_status)
        historyView = findViewById(R.id.bisync_preview_history)
        comparison = findViewById(R.id.bisync_preview_comparison)
        maxDeletePercent = findViewById(R.id.bisync_preview_delete_percent)
        maxDeleteCount = findViewById(R.id.bisync_preview_delete_count)
        startButton = findViewById(R.id.bisync_preview_start)
        cancelButton = findViewById(R.id.bisync_preview_cancel)

        comparison.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf(
                getString(R.string.bisync_preview_comparison_size_modtime),
                getString(R.string.bisync_preview_comparison_size)
            )
        )
        comparison.setSelection(1)
        if (savedInstanceState == null) {
            maxDeletePercent.setText(BisyncPreflightPolicy.DEFAULT_MAX_DELETE_PERCENT.toString())
            maxDeleteCount.setText(BisyncPreflightPolicy.DEFAULT_MAX_DELETE_COUNT.toString())
        }

        taskId = intent.getLongExtra(ID_EXTRA, 0L)
        if (taskId <= 0L) {
            Toast.makeText(this, R.string.bisync_preview_not_bisync, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        startButton.setOnClickListener { requestPreview() }
        cancelButton.setOnClickListener { cancelCurrentRequest() }
        startButton.isEnabled = false
        cancelButton.isEnabled = false
        status.setText(R.string.bisync_preview_status_loading)
        refreshSnapshot()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        if (taskId > 0L) refreshSnapshot()
    }

    private fun observeWork(profileId: String) {
        val manager = WorkManager.getInstance(applicationContext)
        manager.getWorkInfosForUniqueWorkLiveData(BisyncPreviewAdmissionScheduler.workName(profileId))
            .observe(this) { infos ->
                admissionInfos = infos.orEmpty()
                render()
                refreshSnapshot()
            }
        manager.getWorkInfosByTagLiveData(BisyncPreviewWorkScheduler.profileTag(profileId))
            .observe(this) { infos ->
                previewInfos = infos.orEmpty().filter { it.tags.contains(BisyncPreviewWorkScheduler.TAG) }
                render()
                refreshSnapshot()
            }
    }

    private fun refreshSnapshot() {
        if (taskId <= 0L) return
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) {
                    val handler = DatabaseHandler(applicationContext)
                    val task = try {
                        handler.getTask(taskId)
                    } finally {
                        handler.close()
                    } ?: return@withContext null
                    if (task.direction != SyncDirectionObject.SYNC_BIDIRECTIONAL_INITIAL &&
                        task.direction != SyncDirectionObject.SYNC_BIDIRECTIONAL) return@withContext null
                    val profileRepository = ProfileRepository(applicationContext)
                    val profile = profileRepository.ensureLegacyTask(task)
                    if (profile.mode != ProfileMode.BISYNC) return@withContext null
                    val previews = BisyncPreviewRepository(applicationContext)
                    Snapshot(
                        task,
                        profile,
                        previews.active(profile.profileId),
                        previews.history(profile.profileId),
                        BisyncPreflightRepository(applicationContext).get(profile.profileId)
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status.setText(R.string.bisync_preview_failed_status)
                startButton.isEnabled = false
                cancelButton.isEnabled = false
                return@launch
            }
            if (loaded == null) {
                status.setText(R.string.bisync_preview_not_bisync)
                startButton.isEnabled = false
                cancelButton.isEnabled = false
                return@launch
            }
            val previousProfileId = snapshot?.profile?.profileId
            snapshot = loaded
            taskTitle.text = loaded.task.title.ifBlank { getString(R.string.bisync_preview_title) }
            if (previousProfileId != loaded.profile.profileId) observeWork(loaded.profile.profileId)
            render()
        }
    }

    private fun requestPreview() {
        val current = snapshot ?: return
        val percent = maxDeletePercent.text.toString().toIntOrNull()
        val count = maxDeleteCount.text.toString().toIntOrNull()
        if (percent == null || percent !in 1..100 || count == null || count <= 0) {
            Toast.makeText(this, R.string.bisync_preview_unavailable_invalid_limit, Toast.LENGTH_LONG).show()
            return
        }
        val comparisonMode = when (comparison.selectedItemPosition) {
            0 -> BisyncComparisonMode.SIZE_AND_MODTIME
            1 -> BisyncComparisonMode.SIZE_ONLY
            else -> {
                Toast.makeText(this, R.string.bisync_preview_request_error, Toast.LENGTH_LONG).show()
                return
            }
        }
        showAbsentStateChoice(current, comparisonMode, percent, count)
    }

    private fun showAbsentStateChoice(
        current: Snapshot,
        comparisonMode: BisyncComparisonMode,
        percent: Int,
        count: Int
    ) {
        val modes = BisyncPreviewResyncMode.values()
        val labels = modes.map { mode ->
            getString(when (mode) {
                BisyncPreviewResyncMode.PATH1 -> R.string.bisync_preview_mode_path1
                BisyncPreviewResyncMode.PATH2 -> R.string.bisync_preview_mode_path2
                BisyncPreviewResyncMode.NEWER -> R.string.bisync_preview_mode_newer
                BisyncPreviewResyncMode.OLDER -> R.string.bisync_preview_mode_older
                BisyncPreviewResyncMode.LARGER -> R.string.bisync_preview_mode_larger
                BisyncPreviewResyncMode.SMALLER -> R.string.bisync_preview_mode_smaller
            })
        }.toTypedArray()
        var selected = -1
        var positiveButton: Button? = null
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.bisync_preview_confirmation_title)
            .setMessage(R.string.bisync_preview_confirmation_message)
            .setSingleChoiceItems(labels, -1) { _, which ->
                selected = which
                positiveButton?.isEnabled = which in modes.indices
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.bisync_preview_start, null)
            .create()
        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton = positive
            positive.isEnabled = false
            positive.setOnClickListener {
                val mode = modes.getOrNull(selected) ?: return@setOnClickListener
                dialog.dismiss()
                enqueuePreview(current, comparisonMode, percent, count, mode)
            }
        }
        dialog.show()
    }

    private fun enqueuePreview(
        current: Snapshot,
        comparisonMode: BisyncComparisonMode,
        percent: Int,
        count: Int,
        absentStateMode: BisyncPreviewResyncMode
    ) {
        try {
            BisyncPreviewAdmissionScheduler(applicationContext).enqueue(
                current.profile,
                comparisonMode,
                percent,
                count,
                absentStateMode,
                legacyMigrationConfirmed = true
            )
            status.setText(R.string.bisync_preview_status_preflight)
            startButton.isEnabled = false
            cancelButton.isEnabled = true
        } catch (_: Exception) {
            Toast.makeText(this, R.string.bisync_preview_request_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun cancelCurrentRequest() {
        val profileId = snapshot?.profile?.profileId ?: return
        cancelButton.isEnabled = false
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                BisyncPreviewAdmissionScheduler(applicationContext).cancel(profileId)
                val active = BisyncPreviewRepository(applicationContext).active(profileId)
                if (active != null && (active.state == BisyncPreviewOperationState.QUEUED ||
                        active.state == BisyncPreviewOperationState.RUNNING)) {
                    BisyncPreviewWorkScheduler(applicationContext).cancel(active.previewId, active.ownerToken)
                }
            }
            refreshSnapshot()
        }
    }

    private fun render() {
        val current = snapshot
        if (current == null) {
            startButton.isEnabled = false
            cancelButton.isEnabled = false
            return
        }
        val admissionActive = admissionInfos.any { it.state.isActive() }
        val previewActive = previewInfos.any { it.state.isActive() }
        val blockingOwner = current.active != null
        val latest = current.history.firstOrNull()
        val latestAdmission = admissionInfos.lastOrNull()
        status.text = when {
            admissionActive && admissionInfos.any { it.state == WorkInfo.State.ENQUEUED } ->
                getString(R.string.bisync_preview_status_waiting_network)
            admissionActive -> getString(R.string.bisync_preview_status_preflight)
            previewActive && previewInfos.any { it.state == WorkInfo.State.ENQUEUED } ->
                getString(R.string.bisync_preview_status_queued)
            previewActive -> getString(R.string.bisync_preview_status_running)
            latestAdmission?.state == WorkInfo.State.CANCELLED ->
                getString(R.string.bisync_preview_status_cancelled)
            latestAdmission?.state == WorkInfo.State.FAILED -> {
                val outcome = latestAdmission.outputData.getString(BisyncPreviewAdmissionWorker.OUTCOME)
                    ?.takeIf { Regex("^[A-Z0-9_]{1,64}$").matches(it) } ?: "PREVIEW_UNAVAILABLE"
                getString(R.string.bisync_preview_status_blocked, outcome)
            }
            current.active?.state == BisyncPreviewOperationState.INTERRUPTED ||
                current.active?.state == BisyncPreviewOperationState.RECOVERY_REQUIRED ->
                getString(R.string.bisync_preview_status_interrupted)
            current.active?.state == BisyncPreviewOperationState.QUEUED ->
                getString(R.string.bisync_preview_status_queued)
            current.active?.state == BisyncPreviewOperationState.RUNNING ->
                getString(R.string.bisync_preview_status_running)
            latest?.state == BisyncPreviewOperationState.COMPLETE ->
                getString(R.string.bisync_preview_status_complete)
            latest?.state == BisyncPreviewOperationState.INCOMPLETE ->
                getString(R.string.bisync_preview_status_incomplete)
            latest?.state == BisyncPreviewOperationState.CANCELLED ->
                getString(R.string.bisync_preview_status_cancelled)
            latest?.failureCode == BisyncPreviewFailureCode.ENGINE_UNSUPPORTED ->
                getString(R.string.bisync_preview_status_unavailable, "ENGINE_UNSUPPORTED")
            latest?.failureCode != null ->
                getString(R.string.bisync_preview_status_unavailable, latest.failureCode.wireValue)
            current.preflight?.reasonCode != null ->
                getString(R.string.bisync_preview_status_blocked, current.preflight.reasonCode)
            else -> getString(R.string.bisync_preview_status_idle)
        }

        val admissionCanCancel = admissionActive
        val previewCanCancel = current.active?.state == BisyncPreviewOperationState.QUEUED ||
            current.active?.state == BisyncPreviewOperationState.RUNNING
        cancelButton.isEnabled = admissionCanCancel || previewCanCancel
        startButton.isEnabled = !blockingOwner && !admissionActive && !previewActive
        historyView.text = formatHistory(current)
    }

    private fun formatHistory(current: Snapshot): String {
        if (current.history.isEmpty()) return getString(R.string.bisync_preview_history_empty)
        val dateFormat = DateFormat.getDateTimeInstance()
        return current.history.joinToString("\n\n") { operation ->
            val at = dateFormat.format(Date(operation.completedAt ?: operation.requestedAt))
            val currentIdentity = operation.identity.copy(
                profileId = current.profile.profileId,
                profileRevision = current.profile.revision,
                profileFingerprint = current.profile.fingerprint,
                engineRef = current.profile.engineRef
            )
            val freshness = BisyncPreviewFreshnessPolicy.evaluate(
                operation,
                currentIdentity,
                current.preflight,
                System.currentTimeMillis()
            )
            val freshnessLabel = getString(when (freshness) {
                BisyncPreviewFreshness.FRESH_FOR_DISPLAY -> R.string.bisync_preview_fresh_display
                BisyncPreviewFreshness.EXPIRED -> R.string.bisync_preview_expired
                BisyncPreviewFreshness.IDENTITY_CHANGED -> R.string.bisync_preview_profile_changed
                BisyncPreviewFreshness.NATIVE_STATE_CHANGED -> R.string.bisync_preview_native_state_changed
                BisyncPreviewFreshness.NATIVE_STATE_UNVERIFIED -> R.string.bisync_preview_native_state_unverified
                BisyncPreviewFreshness.CLOCK_INVALID -> R.string.bisync_preview_clock_invalid
                BisyncPreviewFreshness.NOT_COMPLETE -> R.string.bisync_preview_not_complete
                BisyncPreviewFreshness.INCOMPLETE -> R.string.bisync_preview_status_incomplete
                BisyncPreviewFreshness.RESULT_UNAVAILABLE -> R.string.bisync_preview_result_unavailable
            })
            val state = getString(stateLabel(operation.state))
            val lines = ArrayList<String>()
            lines.add(getString(R.string.bisync_preview_history_entry, at, state, freshnessLabel))
            operation.summary?.let { summary ->
                lines.add(getString(
                    R.string.bisync_preview_history_summary,
                    summary.plannedTransfers,
                    summary.plannedBytes,
                    summary.plannedFileDeletes,
                    summary.plannedDirectoryDeletes,
                    summary.errorCount
                ))
                if (!summary.conflictsKnown) lines.add(getString(R.string.bisync_preview_conflicts_unknown))
            }
            operation.failureCode?.let {
                lines.add(getString(R.string.bisync_preview_status_unavailable, it.wireValue))
            }
            lines.joinToString("\n")
        }
    }

    private fun stateLabel(state: BisyncPreviewOperationState): Int = when (state) {
        BisyncPreviewOperationState.QUEUED -> R.string.bisync_preview_status_queued
        BisyncPreviewOperationState.RUNNING -> R.string.bisync_preview_status_running
        BisyncPreviewOperationState.COMPLETE -> R.string.bisync_preview_status_complete
        BisyncPreviewOperationState.INCOMPLETE -> R.string.bisync_preview_status_incomplete
        BisyncPreviewOperationState.UNAVAILABLE -> R.string.bisync_preview_result_unavailable
        BisyncPreviewOperationState.CANCELLED -> R.string.bisync_preview_status_cancelled
        BisyncPreviewOperationState.STALE -> R.string.bisync_preview_profile_changed
        BisyncPreviewOperationState.INTERRUPTED,
        BisyncPreviewOperationState.RECOVERY_REQUIRED -> R.string.bisync_preview_status_interrupted
    }

    private fun WorkInfo.State.isActive(): Boolean =
        this == WorkInfo.State.ENQUEUED || this == WorkInfo.State.RUNNING || this == WorkInfo.State.BLOCKED
}
