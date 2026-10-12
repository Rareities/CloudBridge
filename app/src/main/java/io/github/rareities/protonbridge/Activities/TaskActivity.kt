package io.github.rareities.protonbridge.Activities

import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.annotation.VisibleForTesting
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import io.github.rareities.protonbridge.Database.DatabaseHandler
import io.github.rareities.protonbridge.BuildConfig
import io.github.rareities.protonbridge.FilePicker
import io.github.rareities.protonbridge.Fragments.FolderSelectorCallback
import io.github.rareities.protonbridge.Fragments.RemoteFolderPickerFragment
import io.github.rareities.protonbridge.Items.Filter
import io.github.rareities.protonbridge.Items.RemoteItem
import io.github.rareities.protonbridge.Items.SyncDirectionObject
import io.github.rareities.protonbridge.Items.Task
import io.github.rareities.protonbridge.R
import io.github.rareities.protonbridge.Rclone
import io.github.rareities.protonbridge.SpinnerAdapters.FilterSpinnerAdapter
import io.github.rareities.protonbridge.workmanager.SyncManager
import io.github.rareities.protonbridge.util.ActivityHelper
import io.github.rareities.protonbridge.util.BisyncPolicy
import com.google.android.material.floatingactionbutton.FloatingActionButton
import es.dmoral.toasty.Toasty

class TaskActivity : AppCompatActivity(), FolderSelectorCallback{


    private lateinit var rcloneInstance: Rclone
    private val editorRemotes: List<RemoteItem>
        get() = editorRemotesForTests ?: rcloneInstance.remotes
    private lateinit var dbHandler: DatabaseHandler

    private lateinit var syncDescription: TextView
    private lateinit var remotePath: EditText
    private lateinit var localPath: EditText
    private lateinit var remoteDropdown: Spinner
    private lateinit var syncDirection: Spinner
    private lateinit var transfersDropdown: Spinner
    private lateinit var taskControls: TaskEditorControls
    private var editorReady = false
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
    private var selectedRemoteName: String? = null
    private var selectedRemoteName2: String? = null
    // Tracks which remote field the RemoteFolderPickerFragment is choosing a path for.
    private var pickingDestinationRemote = false
    private var selectedFilter: Filter? = null



    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_FP_LOCAL -> {
                if (data != null) {
                    localPath.setText(data.getStringExtra(FilePicker.FILE_PICKER_RESULT))
                }
                localPath.clearFocus()
            }
            REQUEST_CODE_FP_REMOTE -> if (resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!
                try {
                    require(uri.scheme == "content" && uri.authority == BuildConfig.VCP_AUTHORITY)
                    val document = when (uri.pathSegments.firstOrNull()) {
                        "tree" -> DocumentsContract.getTreeDocumentId(uri)
                        "document" -> DocumentsContract.getDocumentId(uri)
                        else -> throw IllegalArgumentException("Unsupported document URI")
                    }
                    val prefix = "rclone/remotes/"
                    require(document.startsWith(prefix))
                    val selected = document.removePrefix(prefix)
                    val separator = selected.indexOf(':')
                    require(separator > 0)
                    val name = selected.substring(0, separator)
                    require(editorRemotes.any { it.name == name })
                    val index = (0 until remoteDropdown.count).firstOrNull {
                        remoteDropdown.getItemAtPosition(it).toString() == name
                    } ?: throw IllegalArgumentException("Remote is unavailable")
                    // The document ID is already decoded by DocumentsContract.
                    // Selection callbacks must not erase the path returned by this picker.
                    selectedRemoteName = name
                    remoteDropdown.setSelection(index)
                    remotePath.setText(selected.substring(separator + 1).ifEmpty { "//$name" })
                } catch (_: IllegalArgumentException) {
                    Toasty.error(this, getString(R.string.task_invalid_remote_folder)).show()
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
        val taskId = savedInstanceState?.takeIf { it.containsKey(ID_EXTRA) }?.getLong(ID_EXTRA)
            ?: intent.getLongExtra(ID_EXTRA, 0L)
        if (taskId != 0L) {
            existingTask = dbHandler.getTask(taskId)
            if (existingTask == null) {
                Toasty.error(this, getString(R.string.taskactivity_task_not_found)).show()
                finish()
                return
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
        if (existingTask != null) setTitle(R.string.task_edit_title)
        switchWifi.setText(R.string.task_unmetered_label)
        findViewById<TextView>(R.id.task_wifi_hint_cancel).setText(R.string.task_unmetered_help)
        findViewById<TextView>(R.id.task_wifi_hint_ignore_defaults).visibility = View.GONE
        switchWifi.isChecked = existingTask?.wifionly ?: false
        switchMD5sum.isChecked = existingTask?.md5sum ?: false
        switchDeleteExcluded.isChecked = existingTask?.deleteExcluded ?: false
        filterDeletionAcknowledged = savedInstanceState?.getBoolean("filterDeletionAcknowledged") ?: ((existingTask?.filterPolicyVersion ?: 0) >= 1)
        taskControls = TaskEditorControls(this, rcloneInstance, { existingTask }, { persistWithoutExit() },
            { saved -> editorReady && draftMatchesSaved(saved) })
        taskControls.populate(existingTask)
        prepareSyncDirectionDropdown()
        prepareTransfersDropdown()
        prepareLocal()
        prepareRemote()
        prepareRemote2()
        prepareFilterDropdown()
        prepareOnFailDropdown()
        prepareOnSuccessDropdown()
        editorReady = true
        watchTaskPathChanges()
        taskControls.refresh()
    }

    private val remoteItems: Array<String>
        get() = (editorRemotes.map { it.name } +
            listOfNotNull(existingTask?.remoteId, existingTask?.remoteId2).filter { it.isNotBlank() })
            .distinct().toTypedArray()

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



    private var filterItems: List<Filter> = emptyList()
    private var filterDeletionAcknowledged = false


    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun persistTaskChanges() {
        val updatedTask = getTaskValues(existingTask!!.id)
        if (updatedTask != null) {
            dbHandler.updateTask(updatedTask)
            finish()
        }
    }

    private fun saveTask() {
        val newTask = getTaskValues(0)
        if (newTask != null) {
            dbHandler.createTask(newTask)
            finish()
        }
    }

    private fun getTaskValues(id: Long): Task? {
        val taskToPopulate = Task(id)
        taskToPopulate.title = findViewById<EditText>(R.id.task_title_textfield).text.toString()
        val remotename = remoteDropdown.selectedItem?.toString()
        if (remotename == null) { Toasty.error(this, getString(R.string.task_remote_required)).show(); return null }
        val available = editorRemotes
        val source = available.firstOrNull { it.name == remotename }
        if (source == null) { Toasty.error(this, getString(R.string.task_remote_unavailable, remotename)).show(); return null }
        taskToPopulate.remoteId = remotename
        val direction = SyncDirectionObject.directionForSpinnerPosition(syncDirection.selectedItemPosition)
        if (direction < 0) { Toasty.error(this, getString(R.string.task_unknown_mode)).show(); return null }
        if (taskToPopulate.title.isBlank()) { Toasty.error(this, "Enter a task name").show(); return null }
        taskToPopulate.remoteType = source.type
        taskToPopulate.remotePath = remotePath.text.toString()
        taskToPopulate.localPath = localPath.text.toString()
        taskToPopulate.direction = direction
        taskToPopulate.bisyncUUID = BisyncPolicy.identityForEdit(existingTask, direction)

        // Destination remote is only meaningful for cloud-to-cloud directions (7/8).
        if (direction == SyncDirectionObject.SYNC_REMOTE_TO_REMOTE
            || direction == SyncDirectionObject.COPY_REMOTE_TO_REMOTE) {
            val destinationName = remoteDropdown2.selectedItem?.toString()
            if (destinationName == null) { Toasty.error(this, getString(R.string.task_remote_required)).show(); return null }
            val destination = available.firstOrNull { it.name == destinationName }
            if (destination == null) { Toasty.error(this, getString(R.string.task_remote_unavailable, destinationName)).show(); return null }
            taskToPopulate.remoteId2 = destinationName
            taskToPopulate.remoteType2 = destination.type
            taskToPopulate.remotePath2 = remotePath2.text.toString()
        }

        taskToPopulate.wifionly = switchWifi.isChecked
        taskToPopulate.md5sum = switchMD5sum.isChecked
        taskToPopulate.deleteExcluded = switchDeleteExcluded.isChecked
        taskToPopulate.filterId = filterItems.getOrNull(filterDropdown.selectedItemPosition - 1)?.id
        taskToPopulate.onFailFollowup = (onFailDropdown.selectedItem as TaskNameIdPair).id
        taskToPopulate.onSuccessFollowup = (onSuccessDropdown.selectedItem as TaskNameIdPair).id
        if (taskToPopulate.onFailFollowup == id && id > 0 ||
            taskToPopulate.onSuccessFollowup == id && id > 0) {
            Toasty.error(this, "A task cannot queue itself as its own follow-up").show()
            return null
        }
        val transfersValue = resources.getStringArray(R.array.task_transfers_values)
            .getOrNull(transfersDropdown.selectedItemPosition)?.toIntOrNull() ?: -1
        taskToPopulate.transfers = if (transfersValue <= 0) null else transfersValue

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
        try { taskControls.applyTo(taskToPopulate) } catch (e: IllegalArgumentException) {
            Toasty.error(this, e.message ?: "Invalid task settings", Toast.LENGTH_LONG, true).show()
            return null
        }
        val selectedRules = try {
            taskToPopulate.filterId?.let { dbHandler.getFilter(it)?.getFilters() } ?: arrayListOf()
        } catch (error: IllegalArgumentException) {
            Toasty.error(this, error.message ?: "Invalid saved filter", Toast.LENGTH_LONG, true).show()
            return null
        }
        if (taskToPopulate.deleteExcluded && selectedRules.any { it.filterType == io.github.rareities.protonbridge.Items.FilterEntry.FILTER_INCLUDE } && !filterDeletionAcknowledged) {
            AlertDialog.Builder(this).setTitle("Review excluded-file deletion")
                .setMessage("Include rules now limit the selected files. Delete excluded files can remove destination files outside that selection. Continue with this setting?")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> filterDeletionAcknowledged = true; fab.performClick() }
                .show()
            return null
        }
        taskToPopulate.filterPolicyVersion = 1
        return taskToPopulate
    }

    /** Compare unsaved baseline inputs without persisting or showing validation popups. */
    private fun draftMatchesSaved(saved: Task): Boolean {
        val mode = SyncDirectionObject.directionForSpinnerPosition(syncDirection.selectedItemPosition)
        val transfersValue = resources.getStringArray(R.array.task_transfers_values)
            .getOrNull(transfersDropdown.selectedItemPosition)?.toIntOrNull()?.takeIf { it > 0 }
        val filter = if (filterDropdown.selectedItemPosition > 0)
            filterItems.getOrNull(filterDropdown.selectedItemPosition - 1)?.id else null
        return BisyncPolicy.normalisedMode(mode) == BisyncPolicy.normalisedMode(saved.direction) &&
            remoteDropdown.selectedItem?.toString() == saved.remoteId &&
            remotePath.text.toString() == saved.remotePath &&
            localPath.text.toString() == saved.localPath &&
            filter == saved.filterId && transfersValue == saved.transfers &&
            switchDeleteExcluded.isChecked == saved.deleteExcluded &&
            taskControls.matchesSavedOptions(saved)
    }

    private fun watchTaskPathChanges() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                taskControls.onDraftChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        localPath.addTextChangedListener(watcher)
        remotePath.addTextChangedListener(watcher)
        transfersDropdown.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                taskControls.onDraftChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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

        remotePathHolder = existingTask?.remotePath ?: ""
        remotePath.setText(remotePathHolder)
        remoteDropdown.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, remoteItems)

        if (existingTask != null) {
            for ((i, remote) in remoteItems.withIndex()) {
                if (remote == existingTask!!.remoteId) {
                    remoteDropdown.setSelection(i)
                }
            }
        }

        remoteDropdown.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parentView: AdapterView<*>?, selectedItemView: View?, position: Int, id: Long) {
                val remoteName = remoteDropdown.selectedItem?.toString() ?: return
                if (selectedRemoteName != null && selectedRemoteName != remoteName) {
                    remotePath.setText("")
                }
                selectedRemoteName = remoteName
                taskControls.onDraftChanged()
            }

            override fun onNothingSelected(parentView: AdapterView<*>?) {}
        }

        // Todo: This will break if the remote changed, but the path did not.
        //       Catch this issue by forcing the path to be emtpy
        remotePath.onFocusChangeListener = object : View.OnFocusChangeListener {
            override fun onFocusChange(p0: View?, p1: Boolean) {
                if (!p1) return
                val name = remoteDropdown.selectedItem?.toString() ?: return
                val remote = editorRemotes.firstOrNull { it.name == name }
                if (remote == null) { Toasty.error(this@TaskActivity, getString(R.string.task_remote_unavailable, name)).show(); return }
                startRemotePicker(remote, "/", false)
                remotePath.clearFocus()
            }
        }
    }

    private fun prepareRemote2() {
        remotePathHolder2 = existingTask?.remotePath2 ?: ""
        remotePath2.setText(remotePathHolder2)
        remoteDropdown2.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, remoteItems)

        if (existingTask != null) {
            for ((i, remote) in remoteItems.withIndex()) {
                if (remote == existingTask!!.remoteId2) {
                    remoteDropdown2.setSelection(i)
                }
            }
        }

        remoteDropdown2.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parentView: AdapterView<*>?, selectedItemView: View?, position: Int, id: Long) {
                val remoteName = remoteDropdown2.selectedItem?.toString() ?: return
                if (selectedRemoteName2 != null && selectedRemoteName2 != remoteName) remotePath2.setText("")
                selectedRemoteName2 = remoteName
                taskControls.onDraftChanged()
            }

            override fun onNothingSelected(parentView: AdapterView<*>?) {}
        }

        remotePath2.onFocusChangeListener = object : View.OnFocusChangeListener {
            override fun onFocusChange(p0: View?, p1: Boolean) {
                if (!p1) return
                val name = remoteDropdown2.selectedItem?.toString() ?: return
                val remote = editorRemotes.firstOrNull { it.name == name }
                if (remote == null) { Toasty.error(this@TaskActivity, getString(R.string.task_remote_unavailable, name)).show(); return }
                startRemotePicker(remote, "/", true)
                remotePath2.clearFocus()
            }
        }
    }

    private fun updateRemoteFieldVisibility(direction: Int) {
        val cloudToCloud = direction == SyncDirectionObject.SYNC_REMOTE_TO_REMOTE
                || direction == SyncDirectionObject.COPY_REMOTE_TO_REMOTE
        taskRemote2Card.visibility = if (cloudToCloud) View.VISIBLE else View.GONE
        taskLocalCard.visibility = if (cloudToCloud) View.GONE else View.VISIBLE
        taskControls.mode(direction)
        val bisync = isBisyncDirection(direction)
        switchMD5sum.isEnabled = !bisync
        switchMD5sum.setText(if (bisync) R.string.bisync_checksum_required else R.string.use_md5checksum)
        findViewById<TextView>(R.id.task_md5_hint).setText(if (bisync) R.string.bisync_checksum_help else R.string.task_hint_md5checksum)
        switchDeleteExcluded.isEnabled = !bisync
        if (bisync) { switchMD5sum.isChecked = true; switchDeleteExcluded.isChecked = false }
    }

    private fun isBisyncDirection(direction: Int) =
        direction == SyncDirectionObject.SYNC_BIDIRECTIONAL_INITIAL ||
            direction == SyncDirectionObject.SYNC_BIDIRECTIONAL

    private fun persistWithoutExit(): Task? {
        val candidate = getTaskValues(existingTask?.id ?: 0) ?: return null
        try {
            if (existingTask == null) dbHandler.createTask(candidate) else dbHandler.updateTask(candidate)
            existingTask = candidate
            setTitle(R.string.task_edit_title)
            return candidate
        } catch (e: Exception) {
            Toasty.error(this, "Could not save task settings", Toast.LENGTH_LONG, true).show()
            return null
        }
    }

    override fun onResume() {
        super.onResume()
        if (::taskControls.isInitialized) taskControls.refreshAfterResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("filterDeletionAcknowledged", filterDeletionAcknowledged)
        existingTask?.let { outState.putLong(ID_EXTRA, it.id) }
        super.onSaveInstanceState(outState)
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
            override fun onItemSelected(parentView: AdapterView<*>?, selectedItemView: View?, position: Int, id: Long) {}

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
            override fun onItemSelected(parentView: AdapterView<*>?, selectedItemView: View?, position: Int, id: Long) {}

            override fun onNothingSelected(parentView: AdapterView<*>?) {}
        }
    }



    private fun prepareFilterDropdown() {
        filterItems = dbHandler.allFilters
        val filterList = filterItems.toMutableList()
        filterDropdown.onItemSelectedListener = null

        if(filterList.isEmpty()) {
            createNewFilterButton.visibility = View.VISIBLE
            filterDropdown.adapter = FilterSpinnerAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf(getString(R.string.task_edit_filter_nofilter)))
            filterDropdown.setSelection(0)
            selectedFilter = null
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
                override fun onItemSelected(parentView: AdapterView<*>?, selectedItemView: View?, position: Int, id: Long) {
                    selectedFilter = filterItems.getOrNull(position - 1)
                    taskControls.onDraftChanged()
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
                selectedItemView: View?,
                position: Int,
                id: Long
            ) {
                val direction = SyncDirectionObject.directionForSpinnerPosition(position)
                updateSpinnerDescription(direction)
                updateRemoteFieldVisibility(direction)
                taskControls.onDraftChanged()
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
                getString(R.string.bisync_description)
            SyncDirectionObject.SYNC_BIDIRECTIONAL_INITIAL -> text =
                getString(R.string.bisync_description)
        }
        syncDescription.text = text
    }

    companion object {
        @VisibleForTesting @JvmStatic var editorRemotesForTests: List<RemoteItem>? = null
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
