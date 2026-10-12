package io.github.rareities.protonbridge.Activities

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Spinner
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import io.github.rareities.protonbridge.Database.DatabaseHandler
import io.github.rareities.protonbridge.Items.Filter
import io.github.rareities.protonbridge.Items.FilterEntry
import io.github.rareities.protonbridge.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FilterWorkflowTest {
    @Test fun unfinishedBlankRowSurvivesRecreationAndCannotBeSaved() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val state = Bundle()
        Robolectric.buildActivity(FilterActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.findViewById<EditText>(R.id.filter_title_textfield).setText("Unfinished filter")
            activity.findViewById<android.view.View>(R.id.filter_add_filterentry_button).performClick()
            controller.saveInstanceState(state)
        }
        Robolectric.buildActivity(FilterActivity::class.java).create(state).start().resume().visible().use { controller ->
            val activity=controller.get()
            assertEquals("Unfinished filter",activity.findViewById<EditText>(R.id.filter_title_textfield).text.toString())
            assertEquals(1,activity.findViewById<RecyclerView>(R.id.filter_filterlist).adapter!!.itemCount)
            activity.findViewById<android.view.View>(R.id.saveButton).performClick()
            assertFalse(activity.isFinishing)
            assertTrue(DatabaseHandler(app).allFilters.isEmpty())
        }
    }

    @Test fun existingMalformedFilterCanBeOpenedAndRepaired() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val filter=DatabaseHandler(app).createFilter(Filter(0).apply { title="Repair"; setFiltersRaw("- \r\n") })
        val intent=Intent(app,FilterActivity::class.java).putExtra(FilterActivity.ID_EXTRA,filter.id)
        Robolectric.buildActivity(FilterActivity::class.java,intent).setup().use { controller ->
            assertFalse(controller.get().isFinishing)
            assertEquals(1,controller.get().findViewById<RecyclerView>(R.id.filter_filterlist).adapter!!.itemCount)
        }
    }
}
