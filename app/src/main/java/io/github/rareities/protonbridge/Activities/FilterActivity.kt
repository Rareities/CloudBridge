package io.github.rareities.protonbridge.Activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.rareities.protonbridge.Database.DatabaseHandler
import io.github.rareities.protonbridge.Items.Filter
import io.github.rareities.protonbridge.Items.FilterEntry
import io.github.rareities.protonbridge.R
import io.github.rareities.protonbridge.Rclone
import io.github.rareities.protonbridge.RecyclerViewAdapters.FilterEntryRecyclerViewAdapter
import io.github.rareities.protonbridge.util.ActivityHelper
import com.google.android.material.floatingactionbutton.FloatingActionButton
import es.dmoral.toasty.Toasty
import jp.wasabeef.recyclerview.animators.LandingAnimator

class FilterActivity : AppCompatActivity() {

    private lateinit var dbHandler: DatabaseHandler

    private lateinit var filterTitle: EditText
    private lateinit var filterList: RecyclerView

    private var existingFilter: Filter? = null
    private var filters: ArrayList<FilterEntry> = arrayListOf()


    companion object {
        const val ID_EXTRA = "FILTER_EDIT_ID"
        const val SAVED_FILTER_ID_EXTRA = "filterId"
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        ActivityHelper.applyTheme(this)
        setContentView(R.layout.activity_filter)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)

        filterTitle = findViewById(R.id.filter_title_textfield)
        filterList = findViewById(R.id.filter_filterlist)
        val newFilterEntry = findViewById<Button>(R.id.filter_add_filterentry_button)
        newFilterEntry.setOnClickListener {
            filters.add(FilterEntry(FilterEntry.FILTER_EXCLUDE, ""))
            filterList.adapter?.notifyItemInserted(filters.lastIndex)
        }


        dbHandler = DatabaseHandler(this)
        val extras = intent.extras
        val filterId: Long
        if (extras != null) {
            filterId = extras.getLong(ID_EXTRA)
            if (filterId != 0L) {
                existingFilter = dbHandler.getFilter(filterId)
                if (existingFilter == null) {
                    Toasty.error(
                            this,
                            this.resources.getString(R.string.filteractivity_filter_not_found)
                    ).show()
                    finish()
                }
            }
        }
        val fab = findViewById<FloatingActionButton>(R.id.saveButton)
        fab.setOnClickListener {
            if (existingFilter == null) {
                saveFilter()
            } else {
                persistFilterChanges()
            }
        }

        if (existingFilter != null) {
            // Preserve malformed/unfinished older rows in the editor so they can
            // be repaired; execution and saving still validate every pattern.
            filters = ArrayList(existingFilter!!.getFiltersRaw().lineSequence().filter { it.isNotBlank() }.map { raw ->
                val line = raw.trim()
                val type = if (line.startsWith("+")) FilterEntry.FILTER_INCLUDE else FilterEntry.FILTER_EXCLUDE
                FilterEntry(type, if (line.startsWith("+") || line.startsWith("-")) line.substring(1).trim() else line)
            }.toList())
        }
        filterTitle.setText(savedInstanceState?.getString("filterDraftTitle") ?: existingFilter?.title)
        savedInstanceState?.getString("filterDraftRows")?.takeIf { it.length <= 65536 }?.let { raw ->
            try {
                val rows = org.json.JSONArray(raw)
                require(rows.length() <= 4096) { "Too many filter rows" }
                filters = ArrayList((0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    FilterEntry(row.getInt("type"), row.getString("pattern"))
                })
            } catch (_: Exception) { Toasty.error(this, "Could not restore the unfinished filter edit").show() }
        }
        prepareFilterList()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("filterDraftTitle", filterTitle.text.toString())
        val rows = org.json.JSONArray()
        filters.forEach { rows.put(org.json.JSONObject().put("type", it.filterType).put("pattern", it.filter)) }
        if (rows.toString().length <= 65536) outState.putString("filterDraftRows", rows.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun persistFilterChanges() {
        val updatedFilter = getFilterValues(existingFilter!!.id)
        if (updatedFilter != null) {
            dbHandler.updateFilter(updatedFilter)
            val resultIntent = Intent()
            resultIntent.putExtra(SAVED_FILTER_ID_EXTRA, updatedFilter.id)
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun saveFilter() {
        val newFilter = getFilterValues(0)
        if (newFilter != null) {
            val filter = dbHandler.createFilter(newFilter)
            val resultIntent = Intent()
            resultIntent.putExtra(SAVED_FILTER_ID_EXTRA, filter.id)
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun getFilterValues(id: Long): Filter? {
        val filterToPopulate = Filter(id)
        filterToPopulate.title = filterTitle.text.toString()
        try { filterToPopulate.setFilters(filters) }
        catch (error: IllegalArgumentException) {
            Toasty.error(this, error.message ?: "Invalid filter pattern", Toast.LENGTH_LONG, true).show()
            return null
        }
        if (filterTitle.text.toString() == "") {
            Toasty.error(
                    this.applicationContext,
                    getString(R.string.filter_data_validation_error_no_title),
                    Toast.LENGTH_SHORT,
                    true
            ).show()
            return null
        }
        return filterToPopulate
    }

    private fun prepareFilterList() {
        val adapter = FilterEntryRecyclerViewAdapter(filters, this)
        filterList.layoutManager = LinearLayoutManager(this)
        filterList.itemAnimator = LandingAnimator()
        filterList.adapter = adapter
    }
}