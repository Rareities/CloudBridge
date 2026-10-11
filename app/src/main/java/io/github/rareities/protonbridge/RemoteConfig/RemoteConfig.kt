package io.github.rareities.protonbridge.RemoteConfig

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SearchView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import io.github.rareities.protonbridge.R
import io.github.rareities.protonbridge.Rclone
import io.github.rareities.protonbridge.RemoteConfig.ProviderListFragment.Companion.newProviderListConfig
import io.github.rareities.protonbridge.RemoteConfig.ProviderListFragment.ProviderSelectedListener
import io.github.rareities.protonbridge.RuntimeConfiguration
import io.github.rareities.protonbridge.rclone.Provider
import io.github.rareities.protonbridge.util.ActivityHelper
import es.dmoral.toasty.Toasty


class RemoteConfig : AppCompatActivity(), ProviderSelectedListener {

    private val OUTSTATE_TITLE = "io.github.rareities.protonbridge.remoteConfig.TITLE"
    private var mFragment: Fragment? = null
    private lateinit var mSearchBar: SearchView
    private var mSearchIcon: MenuItem? = null

    companion object {
        const val CONFIG_EDIT_CODE = 139
        const val CONFIG_EDIT_TARGET = "CONFIG_EDIT_TARGET"

        @VisibleForTesting
        internal var editConfigForTests: ((String) -> HashMap<String, String>?)? = null

        internal fun loadEditConfig(rclone: Rclone, remoteName: String): HashMap<String, String>? {
            val fixture = editConfigForTests
            return if (fixture != null) fixture(remoteName) else rclone.getConfig(remoteName)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(RuntimeConfiguration.attach(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The provider picker also uses a constructor parameter. Install its
        // minimal restoration factory before FragmentActivity restores views.
        supportFragmentManager.fragmentFactory = object : FragmentFactory() {
            override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
                return if (className == ProviderListFragment::class.java.name) {
                    newProviderListConfig()
                } else super.instantiate(classLoader, className)
            }
        }
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        applyTheme()
        setContentView(R.layout.activity_remote_config)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true)
            actionBar.setDisplayShowHomeEnabled(true)
        }

        mSearchBar = findViewById(R.id.search)
        mSearchBar.setOnCloseListener {
            toggleSearch(false)
            return@setOnCloseListener true
        }

        mSearchBar.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String): Boolean {
                if(isProviderlist()) {
                    (getCurrentFragment() as ProviderListFragment).setSearchterm(newText)
                }
                if(isDynamicRemoteConfigFragment()) {
                    (getCurrentFragment() as DynamicRemoteConfigFragment).setSearchterm(newText)
                }
                return true
            }

            override fun onQueryTextSubmit(query: String): Boolean {
                return true
            }
        })

        if (savedInstanceState != null) {
            // FragmentManager has already restored the real instance and its
            // safe identity arguments. Replacing it discards that restoration.
            mFragment = supportFragmentManager.findFragmentById(R.id.flFragment)
                ?: supportFragmentManager.findFragmentByTag("new config")
                ?: supportFragmentManager.findFragmentByTag("config list")
            if (mFragment == null) startProviderlist()
            savedInstanceState.getString(OUTSTATE_TITLE)?.let { supportActionBar?.title = it }
            return
        }

        mFragment = newProviderListConfig()
        supportFragmentManager.beginTransaction()
            .replace(R.id.flFragment, getCurrentFragment(), "config list").commit()
        val shouldEdit = intent.getStringExtra(CONFIG_EDIT_TARGET)
        if (!shouldEdit.isNullOrEmpty()) {
            val rclone = Rclone(this)
            val config = runCatching { loadEditConfig(rclone, shouldEdit) }.getOrNull()
            val provider = if (config != null && config[Rclone.RCLONE_CONFIG_NAME_KEY] == shouldEdit &&
                !config["type"].isNullOrBlank()) {
                runCatching { rclone.getProvider(config["type"]) }.getOrNull()
            } else null
            if (provider == null) {
                Toasty.error(this, getString(R.string.dynamic_config_unknown_error), Toast.LENGTH_SHORT, true).show()
                finish()
                return
            }
            mFragment = DynamicRemoteConfigFragment(provider.name, config)
            startConfig(provider)
        }

    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.remote_config_menu, menu)
        mSearchIcon = menu?.findItem(R.id.app_bar_search);
        return true
    }

    private fun toggleSearch(show: Boolean) {
        if(show) {
            mSearchBar.visibility = View.VISIBLE
            mSearchBar.requestFocus()
            mSearchBar.isFocusable = true
            mSearchBar.isIconified = false
            mSearchBar.requestFocusFromTouch()
            mSearchIcon?.isVisible = false
        } else {
            mSearchBar.visibility = View.GONE
            mSearchIcon?.isVisible = true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.app_bar_search -> {
                toggleSearch(true)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (supportActionBar != null) {
            val title = supportActionBar!!.title
            if (title != null) {
                outState.putString(OUTSTATE_TITLE, title.toString())
            }
        }
    }
    override fun onSupportNavigateUp(): Boolean {
        handleBackAction()
        return true
    }

    override fun onBackPressed() {
        super.onBackPressed()
        handleBackAction()
    }

    private fun handleBackAction() {
        if(mSearchBar.visibility == View.VISIBLE) {
            toggleSearch(false)
            return
        }

        if (mFragment is ProviderListFragment) {
            finish()
        } else if (mFragment is DynamicRemoteConfigFragment) {
            if ((mFragment as DynamicRemoteConfigFragment).isEditConfig()) {
                finish()
            } else {
                var title: String? = null
                if (supportActionBar != null) {
                    title = supportActionBar!!.title.toString()
                }
                startProviderlist(title)
            }
        } else {
            startProviderlist()
        }
    }

    override fun onProviderSelected(provider: Provider) {
        if (provider == null) {
            Toasty.error(this, getString(R.string.nothing_selected), Toast.LENGTH_SHORT, true)
                .show()
            return
        }
        toggleSearch(false)
        mFragment = DynamicRemoteConfigFragment(provider.name)
        startConfig(provider)
    }

    private fun applyTheme() {
        ActivityHelper.applyTheme(this)
        val typedValue = TypedValue()
        theme.resolveAttribute(R.attr.colorPrimaryDark, typedValue, true)
        window.statusBarColor = typedValue.data
    }

    private fun startConfig(provider: Provider) {
        val transaction = supportFragmentManager.beginTransaction()
        transaction.replace(R.id.flFragment, getCurrentFragment(), "new config")
        transaction.commit()
        if (supportActionBar != null) {
            supportActionBar!!.title = provider.getNameCapitalized()
        }
    }

    private fun startProviderlist() {
        startProviderlist(null)
    }
    private fun startProviderlist(preselection: String?) {
        mFragment = newProviderListConfig(preselection)
        val fragmentTransaction = supportFragmentManager.beginTransaction()
        fragmentTransaction.replace(R.id.flFragment, getCurrentFragment(), "config list")
        fragmentTransaction.commit()
        if (supportActionBar != null) {
            supportActionBar!!.setTitle(R.string.title_activity_remote_config)
        }
    }

    private fun isProviderlist(): Boolean {
        if (mFragment is ProviderListFragment) {
            return true
        }
        return false
    }
    private fun isDynamicRemoteConfigFragment(): Boolean {
        if (mFragment is DynamicRemoteConfigFragment) {
            return true
        }
        return false
    }


    /**
     * This defauls to the provider list
     */
    private fun getCurrentFragment(): Fragment {
        if(mFragment == null) {
            return newProviderListConfig()
        }
        return mFragment!!
    }
}
