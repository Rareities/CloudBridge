package ca.pkay.rcloneexplorer.Activities

import android.content.Intent
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.util.AdvancedOptions
import ca.pkay.rcloneexplorer.util.BisyncPolicy
import ca.pkay.rcloneexplorer.util.BisyncStateStore
import ca.pkay.rcloneexplorer.util.ProcessRunner
import ca.pkay.rcloneexplorer.util.RunHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ca.pkay.rcloneexplorer.workmanager.SyncManager
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/** Extends the existing task screen without replacing its remote and filter pickers. */
class TaskEditorControls(
    private val activity: AppCompatActivity,
    private val rclone: Rclone,
    private val taskProvider: () -> Task?,
    private val persist: () -> Task?,
    private val draftMatchesSaved: (Task) -> Boolean
) {
    private val panel = activity.findViewById<View>(R.id.bisync_options_panel)
    private val status = activity.findViewById<TextView>(R.id.bisync_status)
    private val preference = activity.findViewById<Spinner>(R.id.bisync_precedence)
    private val maxDelete = activity.findViewById<EditText>(R.id.bisync_max_delete)
    private val flags = activity.findViewById<EditText>(R.id.bisync_advanced_flags)
    private val checkAccess = activity.findViewById<Switch>(R.id.bisync_check_access)
    private val backupLocal = activity.findViewById<EditText>(R.id.bisync_backup_local)
    private val backupRemote = activity.findViewById<EditText>(R.id.bisync_backup_remote)
    private val preview = activity.findViewById<Button>(R.id.bisync_preview_button)
    private val previewReport = activity.findViewById<Button>(R.id.bisync_preview_report_button)
    private val approve = activity.findViewById<Button>(R.id.bisync_initialize_button)
    private val charging = activity.findViewById<Switch>(R.id.task_charging_only)
    private val battery = activity.findViewById<Switch>(R.id.task_battery_not_low)
    private val quietStart = activity.findViewById<EditText>(R.id.task_quiet_start)
    private val quietEnd = activity.findViewById<EditText>(R.id.task_quiet_end)
    private val run = activity.findViewById<Button>(R.id.task_run_button)
    private var observed: LiveData<List<WorkInfo>>? = null
    private var observedId: Long? = null
    private var active = false
    private var verificationKey: String? = null
    private var verifiedFingerprint: String? = null
    private var verifiedAt = 0L
    private var checkingKey: String? = null
    private var verificationRevision = 0

    init {
        preview.setOnClickListener {
            val task = persist() ?: return@setOnClickListener
            observe(task)
            SyncManager(activity).previewBisync(task.id)
        }
        approve.setOnClickListener { review() }
        previewReport.setOnClickListener { showLastPreview() }
        run.setOnClickListener {
            val task = persist() ?: return@setOnClickListener
            observe(task)
            SyncManager(activity).queue(task.id)
        }
        activity.findViewById<Button>(R.id.task_cancel_button).setOnClickListener {
            taskProvider()?.let { SyncManager(activity).cancel(it.id.toString()) }
        }
        activity.findViewById<Button>(R.id.task_schedule_button).setOnClickListener { schedules() }
        activity.findViewById<Button>(R.id.task_history_button).setOnClickListener { history() }
        activity.findViewById<Button>(R.id.bisync_add_option).setOnClickListener { addOption() }
        activity.findViewById<Button>(R.id.bisync_help_button).setOnClickListener { help() }
        activity.findViewById<Button>(R.id.bisync_preset_button).setOnClickListener { tuningPreset() }
        activity.findViewById<Button>(R.id.bisync_command_button).setOnClickListener { effectiveCommand() }
        activity.findViewById<Button>(R.id.task_proton_connection_button).setOnClickListener { protonConnection() }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onDraftChanged() }
            override fun afterTextChanged(s: Editable?) {}
        }
        listOf(maxDelete, flags, backupLocal, backupRemote).forEach { it.addTextChangedListener(watcher) }
        checkAccess.setOnCheckedChangeListener { _, _ -> onDraftChanged() }
        preference.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { onDraftChanged() }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    fun populate(task: Task?) {
        maxDelete.setText((task?.bisyncMaxDelete ?: 10).toString())
        preference.setSelection(if (task?.bisyncResyncMode == "path2") 1 else 0)
        flags.setText(task?.bisyncAdvancedOptions ?: "")
        checkAccess.isChecked = task?.bisyncCheckAccess ?: false
        backupLocal.setText(task?.bisyncBackupDir1 ?: "")
        backupRemote.setText(task?.bisyncBackupDir2 ?: "")
        charging.isChecked = task?.chargingOnly ?: false
        battery.isChecked = task?.batteryNotLow ?: false
        quietStart.setText(formatTime(task?.quietHoursStart ?: -1))
        quietEnd.setText(formatTime(task?.quietHoursEnd ?: -1))
        task?.let { observe(it) }
    }

    fun applyTo(task: Task) {
        task.chargingOnly = charging.isChecked
        task.batteryNotLow = battery.isChecked
        task.quietHoursStart = parseTime(quietStart.text.toString())
        task.quietHoursEnd = parseTime(quietEnd.text.toString())
        require((task.quietHoursStart == -1 && task.quietHoursEnd == -1) ||
            (task.quietHoursStart >= 0 && task.quietHoursEnd >= 0 && task.quietHoursStart != task.quietHoursEnd)) {
            "Enter two different quiet-hour times, or leave both blank"
        }
        if (!BisyncPolicy.isBisync(task)) return
        task.bisyncMaxDelete = maxDelete.text.toString().toIntOrNull()
            ?.takeIf { it in 0..100 } ?: throw IllegalArgumentException("Deletion limit must be 0 to 100 percent")
        task.bisyncResyncMode = if (preference.selectedItemPosition == 1) "path2" else "path1"
        task.bisyncCheckAccess = checkAccess.isChecked
        task.bisyncBackupDir1 = backupLocal.text.toString().trim()
        task.bisyncBackupDir2 = backupRemote.text.toString().trim()
        task.bisyncAdvancedOptions = flags.text.toString()
        AdvancedOptions.parse(task.bisyncAdvancedOptions)
        AdvancedOptions.validateConnectionBudget(task.bisyncAdvancedOptions, rclone.taskTransfers(task))
    }

    fun mode(direction: Int) {
        panel.visibility = if (direction == 5 || direction == 6) View.VISIBLE else View.GONE
        refresh()
    }

    fun matchesSavedOptions(task: Task): Boolean =
        maxDelete.text.toString().toIntOrNull() == task.bisyncMaxDelete &&
            (if (preference.selectedItemPosition == 1) "path2" else "path1") == task.bisyncResyncMode &&
            flags.text.toString() == task.bisyncAdvancedOptions &&
            checkAccess.isChecked == task.bisyncCheckAccess &&
            backupLocal.text.toString().trim() == task.bisyncBackupDir1 &&
            backupRemote.text.toString().trim() == task.bisyncBackupDir2

    /** Editing immediately withdraws authorisation, even before the next worker starts. */
    fun onDraftChanged() {
        verificationRevision++
        verificationKey = null
        checkingKey = null
        verifiedFingerprint = null
        run.isEnabled = false
        approve.isEnabled = false
        previewReport.isEnabled = false
        if (panel.visibility == View.VISIBLE) refresh()
    }

    fun refreshAfterResume() {
        verificationRevision++
        verificationKey = null
        checkingKey = null
        verifiedFingerprint = null
        refresh()
    }

    private fun currentFingerprint(task: Task, stopped: () -> Boolean): String {
        val filters = task.filterId?.takeIf { it >= 0 }?.let { id ->
            requireNotNull(DatabaseHandler(activity).getFilter(id)) { "The selected filter no longer exists" }.getFilters()
        } ?: arrayListOf()
        val effective = BisyncPolicy.effectiveTask(task, rclone.taskTransfers(task))
        return BisyncPolicy.fingerprint(effective, filters, rclone.configurationIdentity { stopped() })
    }

    private fun verifyInBackground(task: Task, state: JSONObject, key: String) {
        if (checkingKey == key) return
        checkingKey = key
        val revision = ++verificationRevision
        activity.lifecycleScope.launch {
            val job = currentCoroutineContext()
            val fingerprint = withContext(Dispatchers.IO) {
                runCatching { currentFingerprint(task) { !job.isActive } }.getOrNull()
            }
            if (checkingKey != key || verificationRevision != revision || activity.isFinishing) return@launch
            // A file can change while the engine identity is being checked.
            val latest = runCatching { BisyncStateStore(BisyncPolicy.workdir(activity, task)).read() }.getOrNull()
            checkingKey = null
            if (latest?.toString() != state.toString()) {
                refresh()
                return@launch
            }
            verificationKey = key
            verifiedFingerprint = fingerprint
            verifiedAt = SystemClock.elapsedRealtime()
            refresh()
        }
    }

    private fun observe(task: Task) {
        if (observedId == task.id) { refresh(); return }
        observed?.removeObservers(activity)
        observedId = task.id
        observed = WorkManager.getInstance(activity).getWorkInfosByTagLiveData(task.id.toString())
        observed?.observe(activity) { work ->
            active = work.any { !it.state.isFinished }
            refresh()
        }
    }

    fun refresh() {
        val task = taskProvider()
        var reportEnabled = false
        var runEnabled = !active && panel.visibility != View.VISIBLE
        var approvalEnabled = false
        preview.isEnabled = !active
        if (task != null && BisyncPolicy.isBisync(task) && task.bisyncUUID.isNotEmpty()) {
            try {
                val state = BisyncStateStore(BisyncPolicy.workdir(activity, task)).read()
                reportEnabled = !active && state.optLong("previewAt") > 0 && state.optString("preview").isNotBlank()
                val code = state.optString("status", BisyncStateStore.NEEDS_INITIALISATION)
                val current = draftMatchesSaved(task)
                val checkable = current && !active &&
                    (code == BisyncStateStore.READY || code == BisyncStateStore.PREVIEW_READY)
                val key = "${task.asJSON()}|${state}"
                val verified = checkable && verificationKey == key &&
                    SystemClock.elapsedRealtime() - verifiedAt in 0..15_000L
                val fingerprint = if (verified) verifiedFingerprint else null
                val canRun = fingerprint != null && BisyncStateStore.isReady(state, fingerprint)
                val canApprove = fingerprint != null && BisyncStateStore.isPreviewApproved(state, fingerprint)
                val label = when (code) {
                    BisyncStateStore.READY, BisyncStateStore.PREVIEW_READY -> when {
                        !current -> "Unsaved baseline settings. Save and preview again before running."
                        !verified -> "Verifying saved task and engine configuration"
                        canRun -> "Ready"
                        canApprove -> "Preview ready for review"
                        code == BisyncStateStore.PREVIEW_READY && !BisyncStateStore.isPreviewApproved(
                            state, state.optString("previewFingerprint")) -> "Preview expired. Run a new preview."
                        else -> "Settings or engine changed. Save and preview a new baseline."
                    }
                    BisyncStateStore.NEEDS_RECOVERY -> "Recovery review required"
                    "RUNNING", "INITIALISING", "PREVIEWING" -> if (active) code.lowercase().replaceFirstChar { it.titlecase() }
                        else "Interrupted. Review a new preview before continuing."
                    else -> "Needs initialisation"
                }
                val lastSuccess = state.optLong("lastSuccessfulRun")
                val last = if (lastSuccess > 0) activity.getString(R.string.bisync_last_success, date(lastSuccess))
                    else activity.getString(R.string.bisync_never_run)
                status.text = activity.getString(R.string.bisync_status_format, label, last) +
                    state.optString("message").takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                approvalEnabled = !active && current && canApprove
                runEnabled = !active && current && canRun
                if (checkable && !verified) verifyInBackground(task, state, key)
            } catch (e: Exception) { status.text = "Review and save this task before initialising bisync"; runEnabled = false }
        } else {
            status.setText(R.string.bisync_status_initial)
            if (panel.visibility == View.VISIBLE) runEnabled = false
        }
        // Temporarily disabling an unchanged action cancels Android's queued
        // click callback. Apply only the final availability once per refresh.
        previewReport.isEnabled = reportEnabled
        run.isEnabled = runEnabled
        approve.isEnabled = approvalEnabled
        val schedules = task?.let { saved -> DatabaseHandler(activity).allTrigger.filter { it.triggerTarget == saved.id } }.orEmpty()
        activity.findViewById<TextView>(R.id.task_schedule_summary).text = if (schedules.isEmpty())
            activity.getString(R.string.schedule_none) else schedules.joinToString("\n") {
                "${it.title}: ${if (!it.isEnabled) "disabled" else if (it.nextDue > 0) date(it.nextDue) else "scheduled"}"
            }
    }

    private fun review() {
        val task = persist() ?: return
        approve.isEnabled = false
        activity.lifecycleScope.launch {
            val job = currentCoroutineContext()
            val checked = withContext(Dispatchers.IO) {
                runCatching {
                    val state = BisyncStateStore(BisyncPolicy.workdir(activity, task)).read()
                    val fingerprint = currentFingerprint(task) { !job.isActive }
                    require(BisyncStateStore.isPreviewApproved(state, fingerprint)) {
                        "Preview expired or task, filter, engine or remote configuration changed. Run a new preview."
                    }
                    state to fingerprint
                }
            }
            if (!job.isActive || activity.isFinishing) return@launch
            refresh()
            val (state, approval) = checked.getOrElse { error(it.message); return@launch }
            val summary = "Local: ${task.localPath}\nRemote: ${task.remoteId}:${task.remotePath}\n" +
                "Preference: ${if (task.bisyncResyncMode == "path1") "local" else "remote"}\n\n" + state.optString("preview")
            val content = selectableText(activity.getString(R.string.bisync_review_warning, summary))
            AlertDialog.Builder(activity).setTitle(R.string.bisync_review_title).setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.bisync_approve) { _, _ ->
                    SyncManager(activity).initialiseBisync(task.id, approval)
                    observe(task)
                }.show()
        }
    }

    private fun showLastPreview() {
        val task = taskProvider() ?: return
        try {
            val state = BisyncStateStore(BisyncPolicy.workdir(activity, task)).read()
            val report = state.optString("preview")
            if (report.isBlank()) { error("Run a preview first"); return }
            val summary = activity.getString(R.string.bisync_saved_preview, date(state.optLong("previewAt")), report)
            AlertDialog.Builder(activity).setTitle(R.string.bisync_view_preview)
                .setView(selectableText(summary)).setPositiveButton(android.R.string.ok, null).show()
        } catch (failure: Exception) { error(failure.message ?: "Could not read the saved preview") }
    }

    private fun schedules() {
        val task = persist() ?: return
        val items = DatabaseHandler(activity).allTrigger.filter { it.triggerTarget == task.id }
        val labels = items.map { it.title }.toMutableList().apply { add(activity.getString(R.string.schedule_add)) }
        AlertDialog.Builder(activity).setTitle(R.string.bisync_schedule).setItems(labels.toTypedArray()) { _, index ->
            val intent = Intent(activity, TriggerActivity::class.java)
            if (index < items.size) intent.putExtra(TriggerActivity.ID_EXTRA, items[index].id)
            else intent.putExtra("TASK_TARGET_ID", task.id)
            activity.startActivity(intent)
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun history() {
        val task = taskProvider() ?: run { error("Save the task first"); return }
        val records = RunHistory.read(activity, task)
        val text = (records.length() - 1 downTo 0).joinToString("\n\n") { i ->
            val record = records.getJSONObject(i)
            val finished = record.optLong("finished")
            val time = if (finished > 0) finished else record.optLong("started")
            val outcome = if (record.optString("status") == "IN_PROGRESS")
                "Completion unconfirmed" else record.optString("status")
            "${date(time)}: ${record.optString("operation")} $outcome\n${record.optString("message")}"
        }.ifBlank { activity.getString(R.string.bisync_no_history) }
        AlertDialog.Builder(activity).setTitle(R.string.bisync_history).setView(selectableText(text))
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun addOption() {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 12, 32, 12) }
        val name = EditText(activity).apply { hint = "Option name, for example bwlimit"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        val value = EditText(activity).apply { hint = "Value, for example 8M (blank for a switch)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        box.addView(name); box.addView(value)
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.bisync_add_flag).setView(box)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val token = "--" + name.text.toString().trim().removePrefix("--") +
                    value.text.toString().takeIf { it.isNotEmpty() }?.let { "=$it" }.orEmpty()
                val combined = flags.text.toString().trimEnd() + "\n" + token
                try { AdvancedOptions.parse(combined); flags.setText(combined.trimStart()); dialog.dismiss() }
                catch (e: IllegalArgumentException) { name.error = e.message }
            }
        }
        dialog.show()
    }

    private fun help() {
        activity.lifecycleScope.launch {
            val jobContext = currentCoroutineContext()
            val output = withContext(Dispatchers.IO) {
                val text = StringBuilder()
                try {
                    val started = System.currentTimeMillis()
                    val result = ProcessRunner().run(rclone.bisyncHelp(),
                        { !jobContext.isActive || System.currentTimeMillis() - started > 20000 },
                        { line -> if (text.length < 160000) text.append(line).append('\n') }, null)
                    if (result.success()) text.toString() else "Unable to load option help from the bundled engine"
                } catch (e: Exception) { "Unable to load engine help: ${e.javaClass.simpleName}" }
            }
            if (!activity.isFinishing) AlertDialog.Builder(activity).setTitle(R.string.bisync_engine_help)
                .setView(selectableText(output)).setPositiveButton(android.R.string.ok, null).show()
        }
    }

    private fun tuningPreset() {
        val templates = ca.pkay.rcloneexplorer.util.BisyncTuning.templates
        AlertDialog.Builder(activity).setTitle(R.string.bisync_tuning_title)
            .setItems(templates.map { it.title }.toTypedArray()) { _, position ->
                val chosen = templates[position]
                AlertDialog.Builder(activity).setTitle(chosen.title).setMessage(R.string.bisync_tuning_explanation)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        try {
                            val updated = ca.pkay.rcloneexplorer.util.BisyncTuning.apply(chosen, flags.text.toString())
                            val values = activity.resources.getStringArray(R.array.task_transfers_values)
                            val index = values.indexOfFirst { it.toIntOrNull() == chosen.transfers }
                            require(index >= 0) { "The selected transfer count is unavailable" }
                            activity.findViewById<Spinner>(R.id.task_transfers_spinner).setSelection(index)
                            flags.setText(updated)
                        } catch (invalid: IllegalArgumentException) { error(invalid.message) }
                    }.show()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun effectiveCommand() {
        val task = persist() ?: return
        activity.lifecycleScope.launch {
            val description = withContext(Dispatchers.IO) {
                runCatching {
                    val filters = task.filterId?.takeIf { it >= 0 }?.let {
                        requireNotNull(DatabaseHandler(activity).getFilter(it)).getFilters()
                    } ?: arrayListOf()
                    val working = java.io.File(BisyncPolicy.workdir(activity, task), "baselines/<current-baseline>")
                    val args = rclone.bisyncArguments(task, working, BisyncStateStore.RUN, filters, false)
                    args.joinToString("\n") { JSONObject.quote(ca.pkay.rcloneexplorer.util.DiagnosticRedactor.redact(it)) }
                }
            }
            if (!isActive || activity.isFinishing) return@launch
            description.fold({
                AlertDialog.Builder(activity).setTitle(R.string.bisync_command_title)
                    .setView(selectableText(activity.getString(R.string.bisync_command_explanation) + "\n\n" + it))
                    .setPositiveButton(android.R.string.ok, null).show()
            }, { error(it.message) })
        }
    }

    private fun protonConnection() {
        val task = persist() ?: return
        val code = EditText(activity).apply {
            setHint(R.string.proton_one_time_code)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSaveEnabled = false
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        AlertDialog.Builder(activity).setTitle(R.string.proton_connection_title)
            .setMessage(R.string.proton_connection_explanation).setView(code)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.edit) { _, _ ->
                code.text.clear()
                activity.startActivity(Intent(activity, ca.pkay.rcloneexplorer.RemoteConfig.RemoteConfig::class.java)
                    .putExtra(ca.pkay.rcloneexplorer.RemoteConfig.RemoteConfig.CONFIG_EDIT_TARGET, task.remoteId))
            }
            .setPositiveButton(R.string.proton_verify_connection) { _, _ ->
                val otp = code.text.toString()
                code.text.clear()
                val cancel = java.util.concurrent.atomic.AtomicBoolean()
                val progress = AlertDialog.Builder(activity).setMessage(R.string.proton_verifying)
                    .setNegativeButton(android.R.string.cancel) { _, _ -> cancel.set(true) }
                    .setOnCancelListener { cancel.set(true) }.create()
                progress.show()
                activity.lifecycleScope.launch {
                    val coroutine = currentCoroutineContext()
                    try {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { rclone.reconnectProtonAccount(task.remoteId, otp) { cancel.get() || !coroutine.isActive } }
                        }
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            result.fold({ Toast.makeText(activity, R.string.proton_verified, Toast.LENGTH_LONG).show() },
                                { error(it.message) })
                            refreshAfterResume()
                        }
                    } finally {
                        cancel.set(true)
                        progress.dismiss()
                    }
                }
            }.setOnDismissListener { code.text.clear() }.show()
    }

    private fun selectableText(text: String): ScrollView = ScrollView(activity).apply {
        addView(TextView(activity).apply { this.text = text; setPadding(32, 16, 32, 16); setTextIsSelectable(true) })
    }
    private fun error(message: String?) { Toast.makeText(activity, message ?: "Invalid task settings", Toast.LENGTH_LONG).show() }
    private fun date(time: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
    private fun formatTime(minutes: Int) = if (minutes < 0) "" else String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)
    private fun parseTime(value: String): Int {
        if (value.isBlank()) return -1
        val parts = value.trim().split(':')
        require(parts.size == 2) { "Use HH:mm for quiet hours" }
        val hour = parts[0].toIntOrNull()
        val minute = parts[1].toIntOrNull()
        require(hour != null && minute != null && hour in 0..23 && minute in 0..59) { "Use a valid time from 00:00 to 23:59" }
        return hour * 60 + minute
    }
}
