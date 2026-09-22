package ca.pkay.rcloneexplorer.Settings

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.NativeExecutionHandle
import de.schuelken.cloudbridge.extensions.tag
import de.schuelken.cloudbridge.settings.preferences.ButtonPreference
import java.io.IOException
import java.util.regex.Pattern


class LogPreferencesFragment : PreferenceFragmentCompat() {

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_logging_preferences, rootKey)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        requireActivity().title = getString(R.string.logging_settings_header)

        val sigkill = findPreference<Preference>("TempKeySigquit") as ButtonPreference
        sigkill.setButtonText(getString(R.string.pref_send_sigquit_button))
        sigkill.setButtonOnClick {
            sigquitAll()
        }

    }


    private fun sigquitAll() {
        Toast.makeText(context, getString(R.string.stopping_everything), Toast.LENGTH_LONG).show()
        try {
            val execution = NativeExecutionHandle.launch(arrayOf("ps"), null, "list-native-processes")
            val output = StringBuilder()
            var exceededLimit = false
            val outcome = execution.await(5_000L, { line ->
                if (output.length + line.length + 1 <= 1024 * 1024) {
                    output.append(line).append('\n')
                } else {
                    exceededLimit = true
                }
            }, null)
            if (!outcome.isSuccess() || outcome.isOutputTruncated() || exceededLimit) {
                FLog.e(tag(), "Unable to inspect native processes safely (%s)", outcome.getState())
                return
            }

            val regex = "\\s+(\\d+)\\s+\\d+\\s+\\d+\\s+.+librclone.+$"
            val pattern = Pattern.compile(regex, Pattern.MULTILINE)
            val matcher = pattern.matcher(output.toString())

            while (matcher.find()) {
                for (i in 1..matcher.groupCount()) {
                    val pidMatch = matcher.group(i) ?: continue
                    val pid = pidMatch.toInt()
                    FLog.i(tag(), "SIGQUIT to process pid=%s", pid)
                    Process.sendSignal(pid, Process.SIGNAL_QUIT)
                }
            }
            Process.killProcess(Process.myPid())
        } catch (e: IOException) {
            FLog.e(tag(), "Unable to start native process inspection", e)
        }
    }
}
