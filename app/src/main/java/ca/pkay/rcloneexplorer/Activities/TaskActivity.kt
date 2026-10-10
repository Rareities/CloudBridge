package ca.pkay.rcloneexplorer.Activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.CheckBox
import android.text.InputType
import android.provider.DocumentsContract
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.FilePicker
import ca.pkay.rcloneexplorer.Fragments.FolderSelectorCallback
import ca.pkay.rcloneexplorer.Fragments.RemoteFolderPickerFragment
import ca.pkay.rcloneexplorer.Items.Filter
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.SpinnerAdapters.FilterSpinnerAdapter
import ca.pkay.rcloneexplorer.util.ActivityHelper
import com.google.android.material.floatingactionbutton.FloatingActionButton
import es.dmoral.toasty.Toasty
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import androidx.appcompat.app.AlertDialog
import ca.pkay.rcloneexplorer.bisync.BisyncSession
import ca.pkay.rcloneexplorer.bisync.BisyncPolicy
import ca.pkay.rcloneexplorer.bisync.BisyncOptions
import ca.pkay.rcloneexplorer.bisync.BisyncCapabilities
import ca.pkay.rcloneexplorer.bisync.BisyncAdvancedOptions
import ca.pkay.rcloneexplorer.BuildConfig
import ca.pkay.rcloneexplorer.workmanager.ProcessMonitor

class TaskActivity : AppCompatActivity(), FolderSelectorCallback{


    private lateinit var rcloneInstance: Rclone
    private lateinit var dbHandler: DatabaseHandler

    private lateinit var syncDescription: TextView
    private lateinit var remotePath: EditText
    private lateinit var localPath: EditText
    private lateinit var remoteDropdown: Spinner
    private lateinit var syncDirection: Spinner
    private lateinit var transfersDropdown: Spinner
    private lateinit var fab: FloatingActionButton

    // Destination remote for cloud-to-cloud directions (7/8).
    private lateinit var remotePath2: EditText
    private lateinit var remoteDropdown2: Spinner
    private lateinit var taskLocalCard: View
    private lateinit var taskRemote2Card: View

    private lateinit var switchWifi: Switch
    private lateinit var switchMD5sum: Switch


    private lateinit var filterDropdown: Spinner
    private lateinit var createNewFilterButton: Button
    private lateinit var switchDeleteExcluded: Switch


    private lateinit var onFailDropdown: Spinner
    private lateinit var onSuccessDropdown: Spinner


    private lateinit var filterOptionsButton: ImageButton


    private var existingTask: Task? = null
    private var remotePathHolder = ""
    private var remotePathHolder2 = ""
    // Tracks which remote field the RemoteFolderPickerFragment is choosing a path for.
    private var pickingDestinationRemote = false
    private var selectedFilter: Filter? = null
    @Volatile private var previewProcess: Process? = null
    @Volatile private var previewCancelled = false
    private lateinit var bisyncDeleteLimit: EditText
    private lateinit var bisyncControls: View
    private var bisyncOptions = BisyncOptions()



    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_FP_LOCAL -> {
                if (data != null) {
                    localPath.setText(data.getStringExtra(FilePicker.FILE_PICKER_RESULT))
                }
                localPath.clearFocus()
            }
            REQUEST_CODE_FP_REMOTE -> if (data != null) {
                try {
                    val uri = data.data ?: error("No folder selected")
                    require(uri.scheme == "content" && uri.authority == BuildConfig.VCP_AUTHORITY) { "Choose a CloudBridge remote" }
                    val document = if (uri.pathSegments.firstOrNull() == "tree") DocumentsContract.getTreeDocumentId(uri)
                        else DocumentsContract.getDocumentId(uri)
                    val endpoint = document.removePrefix("rclone/remotes/").removePrefix("remotes/")
                    val separator = endpoint.indexOf(':')
                    require(separator > 0) { "Invalid folder identifier" }
                    val name = endpoint.substring(0, separator)
                    val index = remoteItems.indexOf(name)
                    require(index >= 0) { "Selected remote is no longer configured" }
                    remoteDropdown.setSelection(index)
                    remotePathHolder = endpoint.substring(separator + 1)
                    remotePath.setText(remotePathHolder)
                } catch (e: Exception) {
                    Toasty.error(this, "Invalid CloudBridge folder selection").show()
                }
            }
            REQUEST_CODE_FILTER -> if (data != null) {
                val filterId = data.getLongExtra(FilterActivity.SAVED_FILTER_ID_EXTRA, -1)
                if(filterId >= 0) {
                    selectFilter(filterId)
                }
            }
        }
        fab.visibility = View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        ActivityHelper.applyTheme(this)
        setContentView(R.layout.activity_task)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)

        remotePath = findViewById(R.id.task_remote_path_textfield)
        localPath = findViewById(R.id.task_local_path_textfield)
        remoteDropdown = findViewById(R.id.task_remote_spinner)
        remotePath2 = findViewById(R.id.task_remote_path_textfield2)
        remoteDropdown2 = findViewById(R.id.task_remote_spinner2)
        taskLocalCard = findViewById(R.id.task_local_card)
        taskRemote2Card = findViewById(R.id.task_remote2_card)
        syncDirection = findViewById(R.id.task_direction_spinner)
        syncDescription = findViewById(R.id.descriptionSyncDirection)
        bisyncDeleteLimit = findViewById(R.id.task_bisync_max_delete)
        bisyncControls = findViewById(R.id.task_bisync_controls)
        transfersDropdown = findViewById(R.id.task_transfers_spinner)
        filterDropdown = findViewById(R.id.task_filter_spinner)
        switchDeleteExcluded = findViewById(R.id.task_exclude_delete_toggle)
        onFailDropdown = findViewById(R.id.task_onFail_spinner)
        onSuccessDropdown = findViewById(R.id.task_onSuccess_spinner)
        fab = findViewById(R.id.saveButton)
        switchWifi = findViewById(R.id.task_wifionly)
        switchMD5sum = findViewById(R.id.task_md5sum)

        rcloneInstance = Rclone(this)
        dbHandler = DatabaseHandler(this)
        val extras = intent.extras
        val taskId: Long
        if (extras != null) {
            taskId = extras.getLong(ID_EXTRA)
            if (taskId != 0L) {
                existingTask = dbHandler.getTask(taskId)
                if (existingTask == null) {
                    Toasty.error(
                        this,
                        this.resources.getString(R.string.taskactivity_task_not_found)
                    ).show()
                    finish()
                }
            }
        }
        val fab = findViewById<FloatingActionButton>(R.id.saveButton)
        fab.setOnClickListener {
            //Todo fix error when no remotes are available
            if (existingTask == null) {
                saveTask()
            } else {
                persistTaskChanges()
            }
        }

        filterOptionsButton = findViewById(R.id.task_edit_filter_options_button)
        filterOptionsButton.setOnClickListener {
            val filter = if(filterDropdown.selectedItemPosition > 0 && filterDropdown.selectedItemPosition < filterDropdown.count) filterItems[filterDropdown.selectedItemPosition - 1] else null
            showFilterMenu(filterOptionsButton, filter)
        }
        createNewFilterButton = findViewById(R.id.task_edit_filter_add_button)
        createNewFilterButton.setOnClickListener {
            openFilterActivity()
        }

        findViewById<TextView>(R.id.task_title_textfield).text = existingTask?.title
        switchWifi.isChecked = existingTask?.wifionly ?: false
        switchMD5sum.isChecked = existingTask?.md5sum ?: false
        switchDeleteExcluded.isChecked = existingTask?.deleteExcluded ?: false
        bisyncDeleteLimit.setText((existingTask?.bisyncMaxDelete ?: 10).toString())
        bisyncOptions = existingTask?.bisyncOptions ?: BisyncOptions()
        findViewById<Button>(R.id.task_bisync_advanced).setOnClickListener { editBisyncOptions() }
        findViewById<Button>(R.id.task_bisync_history).setOnClickListener { showBisyncHistory() }
        findViewById<Button>(R.id.task_bisync_access).setOnClickListener { setupAccessMarkers() }
        prepareSyncDirectionDropdown()
        prepareTransfersDropdown()
        prepareLocal()
        prepareRemote()
        prepareRemote2()
        prepareFilterDropdown()
        prepareOnFailDropdown()
        prepareOnSuccessDropdown()
    }

    private val remoteItems: Array<String?>
        get() {
            val remotes = arrayOfNulls<String>(rcloneInstance.remotes.size)
            for (i in rcloneInstance.remotes.indices) {
                remotes[i] = rcloneInstance.remotes[i].name
            }
            return remotes
        }

    private val taskListWithNone: ArrayList<TaskNameIdPair>
        get() {
            val tasks = dbHandler.allTasks
            val list = ArrayList<TaskNameIdPair>()
            list.add(TaskNameIdPair(-1, "None"))
            tasks.forEach{
                list.add(TaskNameIdPair(it.id, it.title))
            }
            return list
        }



    private val filterItems: List<Filter>
        get() {
            return dbHandler.allFilters
        }


    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun persistTaskChanges() {
        val updatedTask = getTaskValues(existingTask!!.id)
        if (updatedTask != null) {
            saveWithBisyncReview(updatedTask) { dbHandler.updateTask(updatedTask) }
        }
    }

    private fun saveTask() {
        val newTask = getTaskValues(0)
        if (newTask != null) {
            saveWithBisyncReview(newTask) { dbHandler.createTask(newTask) }
        }
    }

    private fun saveWithBisyncReview(task: Task, save: () -> Unit) {
        if (task.direction != SyncDirectionObject.SYNC_BIDIRECTIONAL) {
            save(); finish(); return
        }
        try {
            BisyncPolicy.validate(task)
            if (!BisyncSession.needsReview(this, task)) { save(); finish(); return }
        } catch (e: Exception) {
            Toasty.error(this, e.message ?: "Invalid bisync configuration").show(); return
        }
        AlertDialog.Builder(this).setTitle(R.string.bisync_review_title)
            .setMessage(R.string.bisync_review_explanation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.bisync_preview) { _, _ -> previewBisync(task, save) }.show()
    }

    private fun previewBisync(task: Task, save: () -> Unit) {
        previewCancelled = false
        fab.isEnabled = false
        val dialog = AlertDialog.Builder(this).setTitle(R.string.bisync_preview)
            .setMessage(R.string.bisync_preview_running).setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> previewCancelled = true; previewProcess?.destroy() }.show()
        Thread {
            val output = StringBuilder()
            var succeeded = false
            var truncated = false
            var approval: BisyncSession.PreviewApproval? = null
            try {
                BisyncSession.previewLease(this).use {
                    if (previewCancelled) error("Preview canceled")
                    approval = BisyncSession.previewApproval(this, task)
                    val process = rcloneInstance.bisyncPreview(task)
                        ?: error("Cannot start rclone preview")
                    previewProcess = process
                    if (previewCancelled) process.destroy()
                    succeeded = ProcessMonitor.await(process) { line ->
                        if (output.length + line.length < 24000) output.append(ca.pkay.rcloneexplorer.util.SecretRedactor.redact(line)).append('\n')
                        else truncated = true
                    } == 0 && !previewCancelled && !truncated
                }
                if (truncated) output.append("\nPreview exceeded the review limit. Initialization is blocked; use a smaller scope.")
            } catch (e: Exception) { output.append(e.message ?: "Preview failed") }
            finally { previewProcess = null }
            runOnUiThread {
                dialog.dismiss(); fab.isEnabled = true
                if (isFinishing || isDestroyed) return@runOnUiThread
                val review = AlertDialog.Builder(this).setTitle(if (succeeded)
                    R.string.bisync_approve_title else R.string.bisync_preview_failed)
                    .setMessage(getString(R.string.bisync_approval_warning) + "\n\n" + output)
                    .setNegativeButton(android.R.string.cancel, null)
                if (succeeded) review.setPositiveButton(R.string.bisync_approve) { _, _ ->
                    try { BisyncSession.approve(this, task, checkNotNull(approval)); save(); finish() }
                    catch (e: Exception) { Toasty.error(this, e.message ?: "Cannot save job").show() }
                }
                review.show()
            }
        }.start()
    }

    override fun onDestroy() {
        previewCancelled = true
        previewProcess?.destroy()
        super.onDestroy()
    }

    private fun getTaskValues(id: Long): Task? {
        // Preserve constraints/options introduced outside this editor when changing basic fields.
        val taskToPopulate = existingTask?.let { Task.fromString(it.asJSON().toString()).apply { this.id = id } } ?: Task(id)
        existingTask?.let {
            taskToPopulate.stableUUID = it.stableUUID
            taskToPopulate.bisyncMaxDelete = it.bisyncMaxDelete
        }
        taskToPopulate.bisyncOptions = bisyncOptions
        taskToPopulate.title = findViewById<EditText>(R.id.task_title_textfield).text.toString()
        val remotename = remoteDropdown.selectedItem.toString()
        taskToPopulate.remoteId = remotename
        val direction = SyncDirectionObject.directionForSpinnerPosition(syncDirection.selectedItemPosition)
        if (direction < 0 || remoteDropdown.selectedItem == null) {
            Toasty.error(this, "Choose a supported mode and remote before saving").show()
            return null
        }
        for (ri in rcloneInstance.remotes) {
            if (ri.name == taskToPopulate.remoteId) {
                taskToPopulate.remoteType = ri.type
            }
        }
        taskToPopulate.remotePath = remotePath.text.toString()
        taskToPopulate.localPath = localPath.text.toString()
        taskToPopulate.direction = direction

        // Destination remote is only meaningful for cloud-to-cloud directions (7/8).
        if (direction == SyncDirectionObject.SYNC_REMOTE_TO_REMOTE
            || direction == SyncDirectionObject.COPY_REMOTE_TO_REMOTE) {
            taskToPopulate.remoteId2 = remoteDropdown2.selectedItem.toString()
            for (ri in rcloneInstance.remotes) {
                if (ri.name == taskToPopulate.remoteId2) {
                    taskToPopulate.remoteType2 = ri.type
                }
            }
            taskToPopulate.remotePath2 = remotePath2.text.toString()
        }

        taskToPopulate.wifionly = switchWifi.isChecked
        taskToPopulate.md5sum = switchMD5sum.isChecked
        taskToPopulate.deleteExcluded = switchDeleteExcluded.isChecked
        taskToPopulate.filterId = if(filterDropdown.selectedItemPosition == 0 || filterDropdown.selectedItemPosition == -1) null else filterItems[filterDropdown.selectedItemPosition - 1].id
        taskToPopulate.onFailFollowup = (onFailDropdown.selectedItem as TaskNameIdPair).id
        taskToPopulate.onSuccessFollowup = (onSuccessDropdown.selectedItem as TaskNameIdPair).id
        val transfersValue = resources.getStringArray(R.array.task_transfers_values)
            .getOrNull(transfersDropdown.selectedItemPosition)?.toIntOrNull() ?: -1
        taskToPopulate.transfers = if (transfersValue <= 0) null else transfersValue
        if (direction == SyncDirectionObject.SYNC_BIDIRECTIONAL) {
            val percent = bisyncDeleteLimit.text.toString().toIntOrNull()
            if (percent == null || percent !in 0..25) {
                bisyncDeleteLimit.error = getString(R.string.bisync_delete_limit)
                return null
            }
            taskToPopulate.bisyncMaxDelete = percent
            taskToPopulate.md5sum = true
            if (taskToPopulate.filterId != null || taskToPopulate.deleteExcluded) {
                Toasty.error(this, "Bisync filters are not yet supported; remove the filter before saving").show()
                return null
            }
        }

        // Verify if data is completed
        val isCloudToCloud = direction == SyncDirectionObject.SYNC_REMOTE_TO_REMOTE
                || direction == SyncDirectionObject.COPY_REMOTE_TO_REMOTE
        if (!isCloudToCloud && localPath.text.toString() == "") {
            Toasty.error(
                this.applicationContext,
                getString(R.string.task_data_validation_error_no_local_path),
                Toast.LENGTH_SHORT,
                true
            ).show()
            return null
        }
        if (remotePath.text.toString() == "") {
            Toasty.error(
                this.applicationContext,
                getString(R.string.task_data_validation_error_no_remote_path),
                Toast.LENGTH_SHORT,
                true
            ).show()
            return null
        }
        if (isCloudToCloud) {
            if (taskToPopulate.remoteId2.isEmpty() || remotePath2.text.toString() == "") {
                Toasty.error(
                    this.applicationContext,
                    getString(R.string.task_data_validation_error_no_remote_path),
                    Toast.LENGTH_SHORT,
                    true
                ).show()
                return null
            }
            if (taskToPopulate.remoteId2 == taskToPopulate.remoteId
                && remotePath2.text.toString() == remotePath.text.toString()) {
                Toasty.error(
                    this.applicationContext,
                    getString(R.string.task_data_validation_error_same_remote),
                    Toast.LENGTH_SHORT,
                    true
                ).show()
                return null
            }
        }
        return taskToPopulate
    }

    private fun editBisyncOptions() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        val scroll = ScrollView(this).apply { addView(form) }
        fun field(label: Int, value: String, numeric: Boolean = false): EditText {
            val text = TextView(this).apply { setText(label) }
            val input = EditText(this).apply {
                setText(value); hint = getString(label); minHeight = (48 * resources.displayMetrics.density).toInt()
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
                contentDescription = getString(label)
            }
            form.addView(text); form.addView(input)
            text.labelFor = View.generateViewId().also { input.id = it }
            return input
        }
        val checkers = field(R.string.bisync_checkers, bisyncOptions.checkers.toString(), true)
        val retries = field(R.string.bisync_retries, bisyncOptions.retries.toString(), true)
        val lowRetries = field(R.string.bisync_low_retries, bisyncOptions.lowLevelRetries.toString(), true)
        val bandwidth = field(R.string.bisync_bandwidth, bisyncOptions.bandwidthMiB?.toString() ?: "", true)
        val rate = field(R.string.bisync_tps, bisyncOptions.tpsLimit?.toString() ?: "", true)
        val timeout = field(R.string.bisync_timeout, bisyncOptions.timeoutSeconds.toString(), true)
        val connectTimeout = field(R.string.bisync_connect_timeout, bisyncOptions.connectTimeoutSeconds.toString(), true)
        val suffix = field(R.string.bisync_conflict_suffix, bisyncOptions.conflictSuffix)
        val backupLocal = field(R.string.bisync_backup_local, bisyncOptions.backupLocalPath)
        val backupRemote = field(R.string.bisync_backup_remote, bisyncOptions.backupRemotePath)
        val access = CheckBox(this).apply { setText(R.string.bisync_require_access); isChecked = bisyncOptions.requireAccess }
        form.addView(access)
        val custom = field(R.string.bisync_custom_rows, bisyncOptions.customOptions.entries.joinToString("\n") { "${it.key}=${it.value}" })
        custom.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        custom.minLines = 3
        form.addView(TextView(this).apply { text = BisyncAdvancedOptions.supportedNames.sorted().joinToString(", ") })
        form.addView(TextView(this).apply { setText(R.string.bisync_advanced_policy) })
        val dialog = AlertDialog.Builder(this).setTitle(R.string.bisync_advanced).setView(scroll)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val proposed = BisyncOptions(checkers = checkers.text.toString().toInt(),
                        retries = retries.text.toString().toInt(), lowLevelRetries = lowRetries.text.toString().toInt(),
                        bandwidthMiB = bandwidth.text.toString().takeIf { it.isNotBlank() }?.toInt(),
                        tpsLimit = rate.text.toString().takeIf { it.isNotBlank() }?.toInt(),
                        timeoutSeconds = timeout.text.toString().toInt(), connectTimeoutSeconds = connectTimeout.text.toString().toInt(),
                        conflictSuffix = suffix.text.toString(), backupLocalPath = backupLocal.text.toString(),
                        backupRemotePath = backupRemote.text.toString(), requireAccess = access.isChecked,
                        customOptions = BisyncAdvancedOptions.parseRows(custom.text.toString()))
                    proposed.validate()
                    val old = bisyncOptions
                    bisyncOptions = proposed
                    val task = getTaskValues(existingTask?.id ?: 0)
                    if (task == null) { bisyncOptions = old; return@setOnClickListener }
                    try { BisyncPolicy.validate(task) } catch (e: Exception) { bisyncOptions = old; throw e }
                    dialog.dismiss()
                } catch (e: Exception) { Toasty.error(this, e.message ?: "Invalid advanced options").show() }
            }
        }
        dialog.show()
    }

    private fun showBisyncHistory() {
        val task = getTaskValues(existingTask?.id ?: 0) ?: return
        try {
            val records = BisyncSession.history(this, task).reversed()
            val text = buildString {
                append("State: ").append(BisyncSession.status(this@TaskActivity, task)).append('\n')
                append(BisyncCapabilities.describe(this@TaskActivity)).append("\n\n")
                if (records.isEmpty()) append(getString(R.string.bisync_history_empty))
                records.forEach { record ->
                    append(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(record.startedAt)))
                    append(" — ").append(if (record.endedAt == null) "interrupted or active" else record.phase)
                    append("\n").append(record.bytes).append(" bytes; ").append(record.transfers)
                    append(" transfers; ").append(record.errors).append(" errors\n\n")
                }
            }
            AlertDialog.Builder(this).setTitle(R.string.bisync_history).setMessage(text)
                .setPositiveButton(android.R.string.ok, null).setNeutralButton(R.string.bisync_command) { _, _ ->
                    try {
                        AlertDialog.Builder(this).setTitle(R.string.bisync_command)
                            .setMessage(ca.pkay.rcloneexplorer.util.SecretRedactor.redact(BisyncSession.effectiveCommand(this, task)))
                            .setPositiveButton(android.R.string.ok, null).show()
                    } catch (e: Exception) { Toasty.error(this, "Cannot generate validated command").show() }
                }.show()
        } catch (e: Exception) { Toasty.error(this, e.message ?: "Cannot read history").show() }
    }

    private fun setupAccessMarkers() {
        val task = getTaskValues(existingTask?.id ?: 0) ?: return
        AlertDialog.Builder(this).setTitle(R.string.bisync_access_setup).setMessage(R.string.bisync_access_warning)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.bisync_access_setup) { _, _ ->
                fab.isEnabled = false
                Thread {
                    var error: String? = null
                    try { BisyncSession.previewLease(this).use { rcloneInstance.createBisyncAccessMarkers(task) } }
                    catch (e: Exception) { error = e.message ?: "Access marker setup failed" }
                    runOnUiThread {
                        fab.isEnabled = true
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (error == null) {
                            bisyncOptions = bisyncOptions.copy(requireAccess = true)
                            Toasty.success(this, getString(R.string.bisync_access_ready)).show()
                        } else Toasty.error(this, ca.pkay.rcloneexplorer.util.SecretRedactor.redact(error!!)).show()
                    }
                }.start()
            }.show()
    }

    private fun startRemotePicker(remote: RemoteItem, initialPath: String, destination: Boolean) {
        pickingDestinationRemote = destination
        val fragment: Fragment = RemoteFolderPickerFragment.newInstance(remote, this, initialPath)
        val transaction = supportFragmentManager.beginTransaction()
        transaction.replace(R.id.create_task_layout, fragment, "FILE_EXPLORER_FRAGMENT_TAG")
        transaction.addToBackStack("FILE_EXPLORER_FRAGMENT_TAG")
        transaction.commit()
        fab.visibility = View.GONE
    }

    override fun selectFolder(path: String) {
        if (pickingDestinationRemote) {
            remotePathHolder2 = path
            remotePath2.setText(remotePathHolder2)
        } else {
            remotePathHolder = path
            remotePath.setText(remotePathHolder)
        }
        fab.visibility = View.VISIBLE
    }

    private fun selectFilter(filterId: Long) {
        prepareFilterDropdown()

        for ((i, filter) in filterItems.withIndex()) {
            if (filter.id == filterId) {
                filterDropdown.setSelection(i + 1)
            }
        }
    }

    private fun prepareLocal() {
        existingTask.let {
            localPath.setText(it?.localPath ?: "")
        }
        localPath.onFocusChangeListener =
            View.OnFocusChangeListener { v: View?, hasFocus: Boolean ->
                if (hasFocus) {
                    val intent = Intent(this.applicationContext, FilePicker::class.java)
                    intent.putExtra(FilePicker.FILE_PICKER_PICK_DESTINATION_TYPE, true)
                    startActivityForResult(intent, REQUEST_CODE_FP_LOCAL)
                }
            }
    }

    private fun prepareRemote() {

        remotePathHolder = existingTask?.remotePath.toString()
        remoteDropdown.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, remoteItems)

        if (existingTask != null) {
            for ((i, remote) in remoteItems.withIndex()) {
                if (remote == existingTask!!.remoteId) {
                    remoteDropdown.setSelection(i)
                }
            }
        }

        remoteDropdown.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parentView: AdapterView<*>?, selectedItemView: View, position: Int, id: Long) {
                remotePath.setText("")
                val remotename = remoteDropdown.selectedItem.toString()
                if(existingTask?.remoteId.equals(remotename)) {
                    remotePath.setText(remotePathHolder)
                }

            }

            override fun onNothingSelected(parentView: AdapterView<*>?) {}
        }

        // Todo: This will break if the remote changed, but the path did not.
        //       Catch this issue by forcing the path to be emtpy
        remotePath.onFocusChangeListener = object : View.OnFocusChangeListener {
            override fun onFocusChange(p0: View?, p1: Boolean) {
                startRemotePicker(
                    rcloneInstance.getRemoteItemFromName(remoteDropdown.selectedItem.toString()), "/", false
                )
                remotePath.clearFocus()
            }
        }
    }

    private fun prepareRemote2() {
        remotePathHolder2 = existingTask?.remotePath2.toString()
        remoteDropdown2.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, remoteItems)

        if (existingTask != null) {
            for ((i, remote) in remoteItems.withIndex()) {
                if (remote == existingTask!!.remoteId2) {
                    remoteDropdown2.setSelection(i)
                }
            }
        }

        remoteDropdown2.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parentView: AdapterView<*>?, selectedItemView: View, position: Int, id: Long) {
                remotePath2.setText("")
                val remotename = remoteDropdown2.selectedItem.toString()
                if (existingTask?.remoteId2.equals(remotename)) {
                    remotePath2.setText(remotePathHolder2)
                }
            }

            override fun onNothingSelected(parentView: AdapterView<*>?) {}
        }

        remotePath2.onFocusChangeListener = object : View.OnFocusChangeListener {
            override fun onFocusChange(p0: View?, p1: Boolean) {
                startRemotePicker(
                    rcloneInstance.getRemoteItemFromName(remoteDropdown2.selectedItem.toString()), "/", true
                )
                remotePath2.clearFocus()
            }
        }
    }

    private fun updateRemoteFieldVisibility(direction: Int) {
        bisyncControls.visibility = if (direction == SyncDirectionObject.SYNC_BIDIRECTIONAL) View.VISIBLE else View.GONE
        bisyncDeleteLimit.visibility = if (direction == SyncDirectionObject.SYNC_BIDIRECTIONAL) View.VISIBLE else View.GONE
        val cloudToCloud = direction == SyncDirectionObject.SYNC_REMOTE_TO_REMOTE
                || direction == SyncDirectionObject.COPY_REMOTE_TO_REMOTE
        taskRemote2Card.visibility = if (cloudToCloud) View.VISIBLE else View.GONE
        taskLocalCard.visibility = if (cloudToCloud) View.GONE else View.VISIBLE
    }

    private fun prepareOnFailDropdown() {
        onFailDropdown.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, taskListWithNone)
        var i = 0
        var found = 0
        taskListWithNone.forEach{
            if(it.id == (existingTask?.onFailFollowup ?: -1)) {
                found = i
            }
            i++
        }
        onFailDropdown.setSelection(found)

        onFailDropdown.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parentView: AdapterView<*>?, selectedItemView: View, position: Int, id: Long) {
                val pair = parentView?.selectedItem as TaskNameIdPair
                existingTask?.onFailFollowup = pair.id
            }

            override fun onNothingSelected(parentView: AdapterView<*>?) {}
        }
    }

    private fun prepareOnSuccessDropdown() {
        onSuccessDropdown.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, taskListWithNone)
        var i = 0
        var found = 0
        taskListWithNone.forEach{
            if(it.id == (existingTask?.onSuccessFollowup ?: -1)) {
                found = i
            }
            i++
        }
        onSuccessDropdown.setSelection(found)


        onSuccessDropdown.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parentView: AdapterView<*>?, selectedItemView: View, position: Int, id: Long) {
                val pair = parentView?.selectedItem as TaskNameIdPair
                existingTask?.onSuccessFollowup = pair.id
            }

            override fun onNothingSelected(parentView: AdapterView<*>?) {}
        }
    }



    private fun prepareFilterDropdown() {
        val filterList = filterItems.toMutableList()

        if(filterList.isEmpty()) {
            createNewFilterButton.visibility = View.VISIBLE
            filterDropdown.visibility = View.INVISIBLE
        }
        else {
            val titles = mutableListOf<String?>().apply {
                add(getString(R.string.task_edit_filter_nofilter))
                addAll(filterList.map { it.title })
            }

            val adapter = FilterSpinnerAdapter(this, android.R.layout.simple_spinner_dropdown_item, titles)
            filterDropdown.adapter = adapter
            createNewFilterButton.visibility = View.INVISIBLE
            filterDropdown.visibility = View.VISIBLE

            if (existingTask != null) {
                for ((i, filter) in filterList.withIndex()) {
                    if (filter.id == existingTask!!.filterId) {
                        filterDropdown.setSelection(i + 1)
                    }
                }
            }

            filterDropdown.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parentView: AdapterView<*>?, selectedItemView: View, position: Int, id: Long) {
                    selectedFilter = if (position > 0 && position < titles.size) filterItems[position - 1] else null
                }
                override fun onNothingSelected(parentView: AdapterView<*>?) {}
            }
        }
    }

    private fun showFilterMenu(view: View, filter: Filter?) {
        val popupMenu = PopupMenu(this, view)
        popupMenu.menuInflater.inflate(R.menu.filter_item_menu, popupMenu.menu)
        val menu = popupMenu.menu
        for (i in 0 until menu.size()) {
            val menuItem = menu.getItem(i)
            if (menuItem.itemId == R.id.action_edit_filter ||
                    menuItem.itemId == R.id.action_delete_filter) {
                menuItem.isVisible = filter != null
            }
        }
        popupMenu.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.action_create_new_filter -> {
                    openFilterActivity()
                }
                R.id.action_edit_filter -> {
                    openFilterActivity(filter)
                }
                R.id.action_delete_filter -> {
                    dbHandler.deleteFilter(filter!!.id)
                    filterDropdown.setSelection(0)
                    prepareFilterDropdown()
                }
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popupMenu.show()
    }
    private fun openFilterActivity(filter: Filter? = null) {
        val intent = Intent(this, FilterActivity::class.java)
        if(filter != null) {
            intent.putExtra(FilterActivity.ID_EXTRA, filter.id)
        }
        startActivityForResult(intent, REQUEST_CODE_FILTER)
    }

    private fun prepareSyncDirectionDropdown() {
        val options = SyncDirectionObject.getOptionsArray(this)
        val directionAdapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        syncDirection.adapter = directionAdapter
        syncDirection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parentView: AdapterView<*>?,
                selectedItemView: View,
                position: Int,
                id: Long
            ) {
                val direction = SyncDirectionObject.directionForSpinnerPosition(position)
                updateSpinnerDescription(direction)
                updateRemoteFieldVisibility(direction)
            }

            override fun onNothingSelected(adapterView: AdapterView<*>?) {}
        }
        val initialDirection = existingTask?.direction ?: SyncDirectionObject.SYNC_LOCAL_TO_REMOTE
        syncDirection.setSelection(SyncDirectionObject.spinnerPositionForDirection(initialDirection))
        updateRemoteFieldVisibility(initialDirection)
    }

    private fun prepareTransfersDropdown() {
        val entries = resources.getStringArray(R.array.task_transfers_entries)
        transfersDropdown.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, entries)
        val current = existingTask?.transfers
        var selection = 0
        if (current != null) {
            val values = resources.getStringArray(R.array.task_transfers_values)
            val idx = values.indexOfFirst { it.toIntOrNull() == current }
            if (idx >= 0) selection = idx
        }
        transfersDropdown.setSelection(selection)
    }

    private fun updateSpinnerDescription(value: Int) {
        var text = getString(R.string.description_sync_direction_sync_toremote)
        when (value) {
            SyncDirectionObject.SYNC_LOCAL_TO_REMOTE -> text =
                getString(R.string.description_sync_direction_sync_toremote)
            SyncDirectionObject.SYNC_REMOTE_TO_LOCAL -> text =
                getString(R.string.description_sync_direction_sync_tolocal)
            SyncDirectionObject.COPY_LOCAL_TO_REMOTE -> text =
                getString(R.string.description_sync_direction_copy_toremote)
            SyncDirectionObject.COPY_REMOTE_TO_LOCAL -> text =
                getString(R.string.description_sync_direction_copy_tolocal)
            SyncDirectionObject.SYNC_REMOTE_TO_REMOTE -> text =
                getString(R.string.description_sync_direction_sync_remotetoremote)
            SyncDirectionObject.COPY_REMOTE_TO_REMOTE -> text =
                getString(R.string.description_sync_direction_copy_remotetoremote)
            SyncDirectionObject.SYNC_BIDIRECTIONAL -> text =
                getString(R.string.description_sync_direction_sync_bidirectional)
        }
        syncDescription.text = text
    }

    companion object {
        const val ID_EXTRA = "TASK_EDIT_ID"
        const val REQUEST_CODE_FP_LOCAL = 500
        const val REQUEST_CODE_FP_REMOTE = 444
        const val REQUEST_CODE_FILTER = 333

    }

    inner class TaskNameIdPair(var id: Long, private var name: String) {
        override fun toString(): String {
            return name
        }
    }
}
