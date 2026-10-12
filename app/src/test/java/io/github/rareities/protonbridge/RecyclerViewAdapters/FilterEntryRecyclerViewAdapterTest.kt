package io.github.rareities.protonbridge.RecyclerViewAdapters

import android.app.Application
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import io.github.rareities.protonbridge.Items.FilterEntry
import io.github.rareities.protonbridge.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FilterEntryRecyclerViewAdapterTest {
    @Test fun rebindingRowCannotEditThePreviouslyBoundFilter() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.AppTheme)
        val rows = arrayListOf(FilterEntry(FilterEntry.FILTER_EXCLUDE, "/.nomedia"), FilterEntry(FilterEntry.FILTER_INCLUDE, "*.anki2"))
        val adapter = FilterEntryRecyclerViewAdapter(rows, context)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        adapter.onBindViewHolder(holder, 1)
        assertEquals("/.nomedia", rows[0].filter)
        holder.filterText.setText("*.apkg")
        assertEquals("/.nomedia", rows[0].filter)
        assertEquals("*.apkg", rows[1].filter)
    }
}
