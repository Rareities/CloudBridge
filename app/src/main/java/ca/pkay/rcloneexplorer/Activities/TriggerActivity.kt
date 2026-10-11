package ca.pkay.rcloneexplorer.Activities

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.TimePicker
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.Items.Trigger
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Services.TriggerService
import ca.pkay.rcloneexplorer.util.ActivityHelper
import ca.pkay.rcloneexplorer.util.ScheduleCalculator
import com.google.android.material.floatingactionbutton.FloatingActionButton
import es.dmoral.toasty.Toasty
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

class TriggerActivity : AppCompatActivity() {
    companion object { const val ID_EXTRA = "TRIGGER_EDIT_ID" }
    private lateinit var db: DatabaseHandler
    private var original: Trigger? = null
    private var tasks: List<Task> = emptyList()
    private lateinit var titleField: EditText
    private lateinit var enabled: CheckBox
    private lateinit var type: Spinner
    private lateinit var interval: EditText
    private lateinit var presets: Spinner
    private lateinit var time: TimePicker
    private lateinit var target: Spinner
    private lateinit var zone: AutoCompleteTextView
    private lateinit var days: List<CheckBox>
    private lateinit var nextRun: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        ActivityHelper.applyTheme(this)
        setContentView(R.layout.activity_trigger)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        db = DatabaseHandler(this)
        val id = intent.getLongExtra(ID_EXTRA, Trigger.TRIGGER_ID_DOESNTEXIST)
        original = if (id >= 0) db.getTrigger(id) else null
        if (id >= 0 && original == null) {
            Toasty.error(this, getString(R.string.triggeractivity_trigger_not_found)).show()
            finish(); return
        }
        tasks = db.allTasks
        val value = original ?: Trigger(Trigger.TRIGGER_ID_DOESNTEXIST).apply {
            time = 9 * 60
            triggerTarget = intent.getLongExtra("TASK_TARGET_ID", tasks.firstOrNull()?.id ?: -1)
        }
        titleField = findViewById(R.id.trigger_name_edit)
        enabled = findViewById(R.id.cb_is_enabled)
        type = findViewById(R.id.triggerType)
        presets = findViewById(R.id.spinnerIntervals)
        interval = findViewById(R.id.trigger_custom_interval)
        time = findViewById(R.id.trigger_time)
        target = findViewById(R.id.trigger_targets)
        zone = findViewById(R.id.trigger_zone)
        nextRun = findViewById(R.id.trigger_next_run)
        days = listOf(R.id.trigger_cb_monday, R.id.trigger_cb_tuesday, R.id.trigger_cb_wednesday,
            R.id.trigger_cb_thursday, R.id.trigger_cb_friday, R.id.trigger_cb_saturday, R.id.trigger_cb_sunday)
            .map { findViewById<CheckBox>(it) }

        // Restore the complete form first. Listeners never rewrite other input fields.
        titleField.setText(value.title)
        enabled.isChecked = value.isEnabled
        type.setSelection(value.type.takeIf { it in 0..1 } ?: -1)
        time.setIs24HourView(android.text.format.DateFormat.is24HourFormat(this))
        time.hour = if (value.type == 0) (value.time / 60).coerceIn(0, 23) else 9
        time.minute = if (value.type == 0) (value.time % 60).coerceIn(0, 59) else 0
        interval.setText((if (value.type == 1) value.time else 60).toString())
        days.forEachIndexed { index, checkbox -> checkbox.isChecked = value.isEnabledAtDay(index) }
        zone.setText(value.zoneId)
        zone.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, TimeZone.getAvailableIDs().sorted()))
        target.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, tasks.map { it.title })
        target.setSelection(tasks.indexOfFirst { it.id == value.triggerTarget })
        val presetValues = listOf(0, 15, 30, 60, 120, 360, 720, 1440)
        presets.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Custom") + presetValues.drop(1).map { "$it minutes" })
        presets.setSelection(presetValues.indexOf(interval.text.toString().toIntOrNull()).coerceAtLeast(0))
        type.onItemSelectedListener = selectionListener { refresh() }
        presets.onItemSelectedListener = selectionListener {
            presetValues.getOrNull(presets.selectedItemPosition)?.takeIf { it > 0 }?.let { interval.setText(it.toString()) }
            refresh()
        }
        target.onItemSelectedListener = selectionListener { refresh() }
        time.setOnTimeChangedListener { _, _, _ -> refresh() }
        days.forEach { it.setOnCheckedChangeListener { _, _ -> refresh() } }
        enabled.setOnCheckedChangeListener { _, _ -> refresh() }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh() }
            override fun afterTextChanged(s: Editable?) {}
        }
        interval.addTextChangedListener(watcher)
        zone.addTextChangedListener(watcher)
        findViewById<FloatingActionButton>(R.id.saveButton).setOnClickListener { save() }
        refresh()
    }

    private fun selectionListener(block: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { block() }
        override fun onNothingSelected(parent: AdapterView<*>?) { block() }
    }

    private fun form(requireName: Boolean): Trigger {
        val selected = tasks.getOrNull(target.selectedItemPosition)
            ?: throw IllegalArgumentException(getString(R.string.trigger_save_notasks))
        val result = Trigger(original?.id ?: Trigger.TRIGGER_ID_DOESNTEXIST)
        result.title = titleField.text.toString().trim()
        if (requireName) require(result.title.isNotEmpty()) { getString(R.string.trigger_save_notitle) }
        result.isEnabled = enabled.isChecked
        result.type = type.selectedItemPosition
        require(result.type in 0..1) { "Choose a supported schedule type" }
        result.triggerTarget = selected.id
        result.zoneId = zone.text.toString().trim()
        ScheduleCalculator.resolveZone(result.zoneId)
        result.time = if (result.type == 0) time.hour * 60 + time.minute else
            interval.text.toString().toIntOrNull()?.takeIf { it in 15..525600 }
                ?: throw IllegalArgumentException("Choose an interval from 15 minutes to 365 days")
        result.setWeekdays(0)
        days.forEachIndexed { index, checkbox -> result.setEnabledAtDay(index, checkbox.isChecked) }
        require(result.getWeekdays() != 0) { "Select at least one weekday" }
        val unchanged = original?.let { it.type == result.type && it.time == result.time &&
            it.zoneId == result.zoneId && it.getWeekdays() == result.getWeekdays() && it.isEnabled == result.isEnabled } ?: false
        result.nextDue = if (unchanged) original!!.nextDue else 0
        return result
    }

    private fun refresh() {
        findViewById<View>(R.id.intervalCard).visibility = if (type.selectedItemPosition == 1) View.VISIBLE else View.GONE
        findViewById<View>(R.id.timeCard).visibility = if (type.selectedItemPosition == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.weekdaysCard).visibility = View.VISIBLE
        nextRun.text = try {
            val schedule = form(false)
            if (!schedule.isEnabled) "Schedule disabled" else {
                val now = System.currentTimeMillis()
                val due = if (schedule.type == 0) ScheduleCalculator.nextWeeklyTime(now, schedule.time,
                    schedule.getWeekdays(), ScheduleCalculator.resolveZone(schedule.zoneId))
                else ScheduleCalculator.nextInterval(now, schedule.time, schedule.nextDue)
                val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                formatter.timeZone = ScheduleCalculator.resolveZone(schedule.zoneId)
                getString(R.string.schedule_next_run, formatter.format(Date(due)) + " " + formatter.timeZone.id)
            }
        } catch (e: IllegalArgumentException) { e.message }
    }

    private fun save() {
        try {
            time.clearFocus()
            val value = form(true)
            require(db.getTask(value.triggerTarget) != null) { "The selected task no longer exists" }
            if (original == null) db.createTrigger(value) else db.updateTrigger(value)
            TriggerService(this).queueSingleTrigger(value)
            finish()
        } catch (e: Exception) { Toasty.error(this, e.message ?: "Could not save schedule").show() }
    }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
