package ca.pkay.rcloneexplorer.RemoteConfig

import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Context
import android.os.AsyncTask
import android.text.InputType
import android.widget.Toast
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.NativeExecutionHandle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import es.dmoral.toasty.Toasty
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("StaticFieldLeak")
class InternxtReauth(
    private val context: Context,
    private val rclone: Rclone,
    private val remoteName: String
) : AsyncTask<Void?, Void?, Boolean>() {

    private var progressDialog: ProgressDialog? = null
    private var errorMessage: String? = null

    companion object {
        private const val TAG = "InternxtReauth"
        private const val CANCEL = "CANCEL"
        private const val TEMPORARY = "TEMPORARY"
        private const val PERMANENT = "PERMANENT"
        private const val MAX_CONFIG_STATE_OUTPUT_CHARS = 1_048_576
        private const val MAX_CONFIG_STATE_STEPS = 8
    }

    override fun onPreExecute() {
        progressDialog = ProgressDialog(context).apply {
            setMessage(context.getString(R.string.internxt_reauth_progress))
            setCancelable(false)
            show()
        }
    }

    override fun doInBackground(vararg params: Void?): Boolean {
        val authMethod = getAuthPreferenceFromUser()
        if (authMethod == CANCEL) {
            errorMessage = context.getString(R.string.cancelled)
            return false
        }

        if (authMethod == PERMANENT) {
            val totpSecret = getTOTPSecretFromUser()
            if (totpSecret.isEmpty()) {
                errorMessage = context.getString(R.string.cancelled)
                return false
            }
            if (!updateTOTPSecret(totpSecret)) {
                return false
            }
        }

        return runConfigReconnect()
    }

    private fun updateTOTPSecret(totpSecret: String): Boolean {
        val options = arrayListOf(remoteName, "totp_secret", totpSecret, "--obscure")
        val execution = rclone.configOwned("update", options)
        if (execution == null) {
            errorMessage = context.getString(R.string.error_creating_remote)
            return false
        }

        val outcome = execution.await(TimeUnit.MINUTES.toMillis(1), null, null)
        if (!outcome.isSuccess()) {
            errorMessage = context.getString(R.string.error_creating_remote)
            FLog.e(TAG, "Internxt config update ended with state ${outcome.getState()}")
            return false
        }
        return true
    }

    private fun runConfigReconnect(): Boolean {
        var state = ""
        var result = ""
        var steps = 0

        while (steps < MAX_CONFIG_STATE_STEPS) {
            steps++
            val options = arrayListOf(remoteName, "--non-interactive", "--no-obscure")
            if (state.isNotEmpty()) {
                options.add("--continue")
                options.add("--state")
                options.add(state)
                options.add("--result")
                options.add(result)
            }

            val execution = rclone.configOwned("update", options)
            if (execution == null) {
                errorMessage = context.getString(R.string.error_creating_remote)
                return false
            }

            val jsonOutput = StringBuilder()
            val outputTooLarge = AtomicBoolean(false)
            val outcome = execution.await(TimeUnit.MINUTES.toMillis(2), { line ->
                val addition = "$line\n"
                if (jsonOutput.length + addition.length <= MAX_CONFIG_STATE_OUTPUT_CHARS) {
                    jsonOutput.append(addition)
                } else {
                    outputTooLarge.set(true)
                    execution.cancel()
                }
            }, null)

            if (!outcome.isSuccess() || outputTooLarge.get()) {
                errorMessage = context.getString(R.string.error_creating_remote)
                FLog.e(TAG, "Internxt reauth ended with state ${outcome.getState()}")
                return false
            }

            val jsonStr = jsonOutput.toString().trim()
            if (jsonStr.isEmpty()) {
                return true
            }

            try {
                val json = JSONObject(jsonStr)
                state = json.optString("State", "")
                if (state.isEmpty()) {
                    return true
                }

                val optionObj = json.optJSONObject("Option")
                result = if (optionObj != null &&
                    optionObj.optString("Help", "").contains("Two-factor authentication code", ignoreCase = true)
                ) {
                    getTwoFactorCodeFromUser()
                } else {
                    ""
                }
                if (optionObj != null &&
                    optionObj.optString("Help", "").contains("Two-factor authentication code", ignoreCase = true) &&
                    result.isEmpty()
                ) {
                    errorMessage = context.getString(R.string.cancelled)
                    return false
                }
            } catch (e: Exception) {
                // Reconnect output can contain continuation credentials; do not log JSON or exception text.
                errorMessage = context.getString(R.string.error_creating_remote)
                FLog.e(TAG, "Failed to parse bounded Internxt reauth state")
                return false
            }
        }
        errorMessage = context.getString(R.string.error_creating_remote)
        FLog.e(TAG, "Internxt reauth exceeded the config state step limit")
        return false
    }

    private fun getAuthPreferenceFromUser(): String {
        val latch = CountDownLatch(1)
        var choice = CANCEL

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val options = arrayOf(
                context.getString(R.string.internxt_auth_option_temp),
                context.getString(R.string.internxt_auth_option_perm)
            )

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.internxt_reauth_title)
                .setMessage(R.string.internxt_reauth_message)
                .setItems(options) { _, which ->
                    choice = if (which == 0) TEMPORARY else PERMANENT
                    latch.countDown()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> latch.countDown() }
                .setCancelable(false)
                .show()
        }

        latch.awaitQuietly(5, TimeUnit.MINUTES)
        return choice
    }

    private fun getTOTPSecretFromUser(): String {
        val latch = CountDownLatch(1)
        var secret = ""

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val inputLayout = TextInputLayout(context)
            inputLayout.hint = context.getString(R.string.internxt_totp_secret_hint)

            val input = TextInputEditText(context)
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            inputLayout.addView(input)

            val padding = (16 * context.resources.displayMetrics.density).toInt()
            inputLayout.setPadding(padding, 0, padding, 0)

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.internxt_totp_secret_title)
                .setMessage(R.string.internxt_totp_secret_message)
                .setView(inputLayout)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    secret = input.text?.toString()?.trim() ?: ""
                    latch.countDown()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> latch.countDown() }
                .setCancelable(false)
                .show()
        }

        latch.awaitQuietly(5, TimeUnit.MINUTES)
        return secret
    }

    private fun getTwoFactorCodeFromUser(): String {
        val latch = CountDownLatch(1)
        var code = ""

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val inputLayout = TextInputLayout(context)
            inputLayout.hint = context.getString(R.string.internxt_2fa_hint)

            val input = TextInputEditText(context)
            input.inputType = InputType.TYPE_CLASS_NUMBER
            inputLayout.addView(input)

            val padding = (16 * context.resources.displayMetrics.density).toInt()
            inputLayout.setPadding(padding, 0, padding, 0)

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.internxt_2fa_title)
                .setMessage(R.string.internxt_2fa_message)
                .setView(inputLayout)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    code = input.text?.toString()?.trim() ?: ""
                    latch.countDown()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> latch.countDown() }
                .setCancelable(false)
                .show()
        }

        latch.awaitQuietly(5, TimeUnit.MINUTES)
        return code
    }

    override fun onPostExecute(success: Boolean) {
        progressDialog?.dismiss()
        if (success) {
            Toasty.success(context, context.getString(R.string.internxt_reauth_success), Toast.LENGTH_SHORT, true).show()
        } else if (errorMessage != context.getString(R.string.cancelled)) {
            Toasty.error(
                context,
                errorMessage ?: context.getString(R.string.error_creating_remote),
                Toast.LENGTH_SHORT,
                true
            ).show()
        }
    }
}

private fun CountDownLatch.awaitQuietly(timeout: Long, unit: TimeUnit): Boolean {
    return try {
        await(timeout, unit)
    } catch (e: InterruptedException) {
        false
    }
}
