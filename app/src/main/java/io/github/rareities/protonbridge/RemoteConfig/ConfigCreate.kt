package io.github.rareities.protonbridge.RemoteConfig

import android.annotation.SuppressLint
import android.content.Context
import io.github.rareities.protonbridge.Rclone
import android.os.AsyncTask
import es.dmoral.toasty.Toasty
import io.github.rareities.protonbridge.R
import android.widget.Toast
import android.content.Intent
import android.view.View
import io.github.rareities.protonbridge.Activities.MainActivity
import io.github.rareities.protonbridge.util.FLog
import io.github.rareities.protonbridge.util.ProcessRunner
import java.util.ArrayList

@SuppressLint("StaticFieldLeak")
class ConfigCreate internal constructor(
    options: ArrayList<String>?,
    formView: View,
    authView: View,
    context: Context,
    rclone: Rclone,
    private val providerType: String = ""
) : AsyncTask<Void?, Void?, Boolean>() {
    private val options: ArrayList<String>
    @Volatile private var process: Process? = null
    @Volatile private var activeRunner: ProcessRunner? = null
    private val mContext: Context
    private val mRclone: Rclone
    private val mFormView: View
    private val mAuthView: View

    companion object {
        private const val TAG = "ConfigCreate"
    }

    init {
        this.options = ArrayList(options)
        mFormView = formView
        mAuthView = authView
        mContext = context
        mRclone = rclone
    }

    override fun onPreExecute() {
        super.onPreExecute()
        mAuthView.visibility = View.VISIBLE
        mFormView.visibility = View.GONE
    }

    override fun doInBackground(vararg params: Void?): Boolean {
        return if (providerType.equals("internxt", ignoreCase = true)) {
            createInternxtWithTwoFactor()
        } else {
            OauthHelper.createOptionsWithOauth(options, mRclone, mContext)
        }
    }

    /**
     * Creates an Internxt remote with 2FA support.
     * Uses manual buffer reading to handle both 2FA and non-2FA accounts.
     * 
     * Flow:
     * 1. Ask user for Auth Method (Temporary vs Auto-Login)
     * 2. If Auto-Login: Ask for TOTP Secret (Seed) and add to options
     * 3. rclone authenticates with email/password (+ totp_secret if provided)
     * 4. If 2FA enabled: prompts "Two-factor authentication code" -> show dialog
     * 5. Shows "Keep this" confirmation -> respond with 'y'
     */
    private fun createInternxtWithTwoFactor(): Boolean {
        android.util.Log.e(TAG, "=== INTERNXT AUTH START ===")

        // Step 0: Ask for Auth Method (Temporary vs Auto-Login)
        val authMethod = getAuthPreferenceFromUser()
        if (authMethod == "CANCEL") {
             return false
        }

        if (authMethod == "PERMANENT") {
            val totpSecret = getTOTPSecretFromUser()
            if (totpSecret.isEmpty()) {
                // User cancelled or entered empty string
                return false
            }
            // Add totp_secret to options
            options.add("totp_secret")
            options.add(totpSecret)
            android.util.Log.e(TAG, "Added totp_secret to options")
        }
        
        // Step 1: Create the remote entry with --no-interaction so the backend's Config()
        // function (which does interactive login) is NOT triggered. We just save email/pass/
        // totp_secret as raw key-value pairs. The real interactive login happens in Step 2.
        android.util.Log.e(TAG, "Step 1: Running config create (no-interaction)...")
        process = mRclone.configCreateNoInteract(options)
        if (process == null) {
            android.util.Log.e(TAG, "Step 1 FAILED: process is null")
            return false
        }
        
        val createProc = process!!
        android.util.Log.e(TAG, "Step 1: Waiting for config create to finish...")
        
        val createRunner = ProcessRunner().also { activeRunner = it }
        val createDeadline = android.os.SystemClock.elapsedRealtime() + 60_000L
        val created = createRunner.run(createProc,
            { isCancelled || android.os.SystemClock.elapsedRealtime() >= createDeadline }, null, null)
        if (!created.success() || isCancelled) return false
        
        // Extract remote name from options (first element)
        val remoteName = if (options.isNotEmpty()) options[0] else {
            android.util.Log.e(TAG, "ERROR: options empty, cannot get remote name")
            return false
        }
        android.util.Log.d(TAG, "Starting remote authentication")
        
        // Step 2: Run config reconnect to complete the interactive auth
        return runConfigReconnect(remoteName)
    }
    
    /**
     * Runs config reconnect to complete Internxt authentication.
     * Handles both 2FA and mnemonic confirmation interactively.
     */
    internal fun runConfigReconnect(remoteName: String): Boolean {
        var state = ""
        var result = ""
        var remainingSteps = 32

        while (!isCancelled && remainingSteps-- > 0) {
            val options = arrayListOf(remoteName, "--non-interactive", "--no-obscure")
            if (state.isNotEmpty()) {
                options.add("--continue")
                options.add("--state")
                options.add(state)
                options.add("--result")
                options.add(result)
            }

            android.util.Log.e(TAG, "Running Internxt config update")
            val proc = mRclone.config("update", options) ?: return false
            process = proc

            val jsonOutput = java.lang.StringBuilder()
            val overflow = java.util.concurrent.atomic.AtomicBoolean()
            val runner = ProcessRunner().also { activeRunner = it }
            val deadline = android.os.SystemClock.elapsedRealtime() + 120_000L
            val outcome = runner.run(proc,
                { isCancelled || overflow.get() || android.os.SystemClock.elapsedRealtime() >= deadline },
                { line ->
                    if (jsonOutput.length + line.length + 1 > 128 * 1024) {
                        overflow.set(true)
                        runner.requestStop()
                    } else jsonOutput.append(line).append('\n')
                }, null)
            if (!outcome.success() || isCancelled || overflow.get()) return false

            val jsonStr = jsonOutput.toString().trim()
            if (jsonStr.isEmpty()) {
                // Empty JSON means complete
                android.util.Log.e(TAG, "Config state machine finished successfully")
                return true
            }

            try {
                val json = org.json.JSONObject(jsonStr)
                state = json.optString("State", "")
                
                if (state.isEmpty()) {
                    android.util.Log.e(TAG, "Config state machine reached terminal state")
                    return true
                }

                val optionObj = json.optJSONObject("Option")
                if (optionObj != null) {
                    val helpText = optionObj.optString("Help", "")
                    
                    if (helpText.contains("Two-factor authentication code", ignoreCase = true)) {
                        android.util.Log.e(TAG, "JSON requested 2FA")
                        result = getTwoFactorCodeFromUser()
                    } else if (helpText.contains("password", ignoreCase = true)) {
                        result = "" 
                        android.util.Log.d(TAG, "Authentication requested another credential")
                    } else {
                        // Any other prompt, default to empty
                        result = ""
                    }
                } else {
                    // No Option object, meaning it's a Goto State (like {"State": "login"}).
                    result = ""
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Invalid authentication response")
                return false
            }
        }
        return false
    }

    private fun getAuthPreferenceFromUser(): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        var choice = "CANCEL"

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(mContext)
            builder.setTitle(R.string.internxt_auth_method_title)

            
            val options = arrayOf(
                mContext.getString(R.string.internxt_auth_option_temp),
                mContext.getString(R.string.internxt_auth_option_perm)
            )
            
            builder.setItems(options) { dialog, which ->
                if (which == 0) {
                    choice = "TEMPORARY"
                } else {
                    choice = "PERMANENT"
                }
                latch.countDown()
            }
            
            builder.setNegativeButton(android.R.string.cancel) { _, _ ->
                latch.countDown()
            }
            builder.setCancelable(false)
            builder.show()
        }

        try {
            latch.await(5, java.util.concurrent.TimeUnit.MINUTES)
        } catch (e: Exception) {
            FLog.e(TAG, "Error waiting for user auth preference", e)
        }
        return choice
    }

    private fun getTOTPSecretFromUser(): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        var secret = ""
        
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(mContext)
            builder.setTitle(R.string.internxt_totp_secret_title)
            builder.setMessage(R.string.internxt_totp_secret_message)
            
            val inputLayout = com.google.android.material.textfield.TextInputLayout(mContext)
            inputLayout.hint = mContext.getString(R.string.internxt_totp_secret_hint)
            
            val input = com.google.android.material.textfield.TextInputEditText(mContext)
            input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            inputLayout.addView(input)
            
            val padding = (16 * mContext.resources.displayMetrics.density).toInt()
            inputLayout.setPadding(padding, 0, padding, 0)
            builder.setView(inputLayout)
            
            builder.setPositiveButton(android.R.string.ok) { _, _ ->
                secret = input.text?.toString()?.trim() ?: ""
                latch.countDown()
            }
            builder.setNegativeButton(android.R.string.cancel) { _, _ ->
                latch.countDown()
            }
            builder.setCancelable(false)
            builder.show()
        }
        
        try {
            latch.await(5, java.util.concurrent.TimeUnit.MINUTES)
        } catch (e: Exception) {
            FLog.e(TAG, "Error waiting for user TOTP secret", e)
        }
        return secret
    }

    private fun getTwoFactorCodeFromUser(): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        var code = ""
        
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(mContext)
            builder.setTitle(R.string.internxt_2fa_title)
            builder.setMessage(R.string.internxt_2fa_message)
            
            val inputLayout = com.google.android.material.textfield.TextInputLayout(mContext)
            inputLayout.hint = mContext.getString(R.string.internxt_2fa_hint)
            
            val input = com.google.android.material.textfield.TextInputEditText(mContext)
            input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            inputLayout.addView(input)
            
            val padding = (16 * mContext.resources.displayMetrics.density).toInt()
            inputLayout.setPadding(padding, 0, padding, 0)
            builder.setView(inputLayout)
            
            builder.setPositiveButton(android.R.string.ok) { _, _ ->
                code = input.text?.toString()?.trim() ?: ""
                latch.countDown()
            }
            builder.setNegativeButton(android.R.string.cancel) { _, _ ->
                latch.countDown()
            }
            builder.setCancelable(false)
            builder.show()
        }
        
        try {
            latch.await(5, java.util.concurrent.TimeUnit.MINUTES)
        } catch (e: Exception) {
            FLog.e(TAG, "Error waiting for user input", e)
        }
        return code
    }

    override fun onCancelled() {
        super.onCancelled()
        activeRunner?.requestStop()
    }

    override fun onPostExecute(success: Boolean) {
        super.onPostExecute(success)
        if (!success) {
            Toasty.error(
                mContext,
                mContext.getString(R.string.error_creating_remote),
                Toast.LENGTH_SHORT,
                true
            ).show()
        } else {
            Toasty.success(
                mContext,
                mContext.getString(R.string.remote_creation_success),
                Toast.LENGTH_SHORT,
                true
            ).show()
        }
        val intent = Intent(mContext, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        mContext.startActivity(intent)
    }
}
