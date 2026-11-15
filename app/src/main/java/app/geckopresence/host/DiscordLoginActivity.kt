package app.geckopresence.host

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.geckopresence.host.core.DiscordTokenInputPolicy

/** Explicit user-controlled import; no WebView storage scraping or JavaScript credential bridge. */
class DiscordLoginActivity : Activity() {
    private var credentialInput: EditText? = null
    private var importButton: Button? = null
    private var statusView: TextView? = null
    private var importInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            filterTouchesWhenObscured = true
            setBackgroundColor(Color.rgb(16, 18, 24))
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        root.addView(TextView(this).apply {
            text = "DISCORD SESSION IMPORT"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        })
        root.addView(TextView(this).apply {
            text = "Paste a session credential that you already possess for your own Discord account. Gecko validates it and stores it locally using Android Keystore. This is not OAuth2. Never send this value to another person or service."
            setTextColor(Color.LTGRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, dp(14))
        })

        credentialInput = EditText(this).apply {
            hint = "Paste raw session credential (without quotes or Bearer prefix)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 14f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            transformationMethod = PasswordTransformationMethod.getInstance()
            filters = arrayOf(InputFilter.LengthFilter(DiscordTokenInputPolicy.MAX_TOKEN_LENGTH))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            filterTouchesWhenObscured = true
            setSaveEnabled(false)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(30, 33, 41))
                setStroke(dp(1), Color.rgb(84, 90, 102))
                cornerRadius = dp(6).toFloat()
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    importButton?.isEnabled = !s.isNullOrBlank() && !importInProgress
                    statusView?.text = "Credential is kept in this Activity until you import or cancel."
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(credentialInput)

        statusView = TextView(this).apply {
            text = "No credential is stored from this screen yet."
            setTextColor(Color.LTGRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(statusView)

        importButton = Button(this).apply {
            text = "IMPORT SESSION TO ANDROID KEYSTORE"
            isEnabled = false
            setOnClickListener { importPastedSession() }
        }
        root.addView(importButton)
        root.addView(Button(this).apply {
            text = "CANCEL AND CLEAR INPUT"
            setOnClickListener {
                credentialInput?.text?.clear()
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        })
        setContentView(root)
    }

    private fun importPastedSession() {
        if (importInProgress) return
        val token = DiscordTokenInputPolicy.normalize(credentialInput?.text?.toString().orEmpty())
        if (token == null) {
            updateStatus("The pasted value is empty, too short, too long, or contains whitespace. Check it and retry.")
            return
        }
        importInProgress = true
        importButton?.isEnabled = false
        updateStatus("Validating and encrypting the credential locally…")
        try {
            DiscordCredentialVault.save(this, token)
            credentialInput?.text?.clear()
            setResult(Activity.RESULT_OK)
            finish()
        } catch (_: IllegalArgumentException) {
            importInProgress = false
            importButton?.isEnabled = true
            updateStatus("The credential format was rejected. No value was stored.")
        } catch (_: Exception) {
            importInProgress = false
            importButton?.isEnabled = true
            updateStatus("Android Keystore could not store the credential. No value was imported.")
        }
    }

    private fun updateStatus(message: String) {
        statusView?.text = message
    }

    override fun onDestroy() {
        credentialInput?.text?.clear()
        credentialInput = null
        importButton = null
        statusView = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
