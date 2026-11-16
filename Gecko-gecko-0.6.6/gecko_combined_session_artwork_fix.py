#!/usr/bin/env python3
"""Apply the Gecko 0.6.6 explicit session-import + artwork Studio/Gallery fix.

The Kizzy WebView source was reviewed as a diagnostic reference. Its JavaScript
iframe/localStorage token extraction is not reproduced; credentials are explicitly
pasted by the user and written only to the existing Android Keystore vault.

Run from the Gecko repository root:
    python3 gecko_combined_session_artwork_fix.py

The installer is deliberately fail-closed: every source anchor must match exactly
once before any project file is written. Original files are backed up once.
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path.cwd()


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one source anchor, found {count}")
    return text.replace(old, new, 1)


def replace_method(text: str, start: str, end: str, replacement: str, label: str) -> str:
    start_at = text.find(start)
    if start_at < 0:
        raise RuntimeError(f"{label}: start anchor not found")
    end_at = text.find(end, start_at + len(start))
    if end_at < 0:
        raise RuntimeError(f"{label}: end anchor not found")
    if text.find(start, start_at + len(start)) >= 0 and text.find(start, start_at + len(start)) < end_at:
        raise RuntimeError(f"{label}: ambiguous duplicate start anchor")
    return text[:start_at] + replacement + text[end_at:]


NEW_LOGIN_SOURCE = r'''package app.geckopresence.host

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
'''


def update_login(source: str) -> str:
    if source == NEW_LOGIN_SOURCE:
        raise RuntimeError("DiscordLoginActivity already contains the combined session-import fix")
    return NEW_LOGIN_SOURCE


def update_main(source: str) -> str:
    source = replace_once(
        source,
        'import android.graphics.Color\n',
        'import android.graphics.BitmapFactory\nimport android.graphics.Color\n',
        "import sampled artwork preview decoder",
    )
    source = replace_once(
        source,
        'import android.widget.LinearLayout\n',
        'import android.widget.ImageView\nimport android.widget.LinearLayout\n',
        "import artwork ImageView",
    )
    source = replace_once(
        source,
        '                "This is not OAuth2. Sign in to Discord in an isolated first-party WebView, then press Import once. Gecko stores the session credential encrypted with Android Keystore and does not send it to Gecko servers. It is a full account bearer secret. After saving, connect the Gateway from Developer Console.",',
        '                "This is not OAuth2. Paste a session credential you already possess for your own account. Gecko validates it and stores it encrypted with Android Keystore; this screen does not read browser storage, cookies, or clipboard contents. It is a full account bearer secret. After saving, connect the Gateway from Developer Console.",',
        "explain explicit local credential import",
    )
    source = replace_once(
        source,
        'val loginButton = actionButton("LOGIN WITH DISCORD AND IMPORT SESSION", true) {',
        'val loginButton = actionButton("IMPORT EXISTING DISCORD SESSION", true) {',
        "rename session import action accurately",
    )
    source = replace_once(
        source,
        '                .setTitle("Import your Discord session?")\n                .setMessage("This is not OAuth2. After you sign in, Gecko reads the session credential only when you press Import. It is a bearer credential with broad account access. Gecko stores it encrypted on this device and does not send it to a Gecko server. Gateway publishing uses an undocumented user-session connection and may stop working or expose your account to enforcement. Continue only with your own account.")',
        '                .setTitle("Import your Discord session?")\n                .setMessage("Paste only a session credential you already possess for your own account. It is a bearer credential with broad account access. Gecko stores it encrypted on this device and does not send it to a Gecko server. This is not OAuth2 and Gateway publishing uses an undocumented user-session connection. Continue only with your own account.")',
        "remove misleading embedded login instructions",
    )
    source = replace_once(
        source,
        '        }.apply {\n            isEnabled = DiscordWebViewIsolation.isAvailable()\n        }\n        credentialCard.addView(loginButton, marginTop(10))',
        '        }\n        credentialCard.addView(loginButton, marginTop(10))',
        "enable explicit credential import without WebView availability gate",
    )

    source = replace_once(
        source,
        '    private var pendingArtworkField: EditText? = null\n',
        '    private var artworkPreviewExecutor: java.util.concurrent.ExecutorService? = null\n',
        "replace fragile target view with bounded preview executor",
    )

    source = replace_once(
        source,
        '''    override fun onDestroy() {
        super.onDestroy()
    }''',
        '''    override fun onDestroy() {
        artworkPreviewExecutor?.shutdownNow()
        artworkPreviewExecutor = null
        super.onDestroy()
    }''',
        "cancel background artwork preview work with Activity lifecycle",
    )

    source = replace_once(
        source,
        """    override fun onResume() {
        super.onResume()
        if (DiscordWebViewIsolation.isAvailable() && DiscordWebSessionCleanup.hasPendingCleanup(this)) {
            DiscordWebSessionCleanup.clear(this)
        }
        showPage(activePage)
    }""",
        """    override fun onResume() {
        super.onResume()
        if (DiscordWebViewIsolation.isAvailable() && DiscordWebSessionCleanup.hasPendingCleanup(this)) {
            DiscordWebSessionCleanup.clear(this)
        }
        restoreCompletedPendingArtwork()
        showPage(activePage)
    }""",
        "recover an upload completed while MainActivity was recreated",
    )

    source = replace_once(
        source,
        '''        if (requestCode == REQUEST_ARTWORK_PICKER) {
            if (resultCode == RESULT_OK) {
                val uri = data?.data
                if (uri != null) importArtworkSelection(uri) else pendingArtworkField = null
            } else {
                pendingArtworkField = null
            }
            return
        }''',
        '''        if (requestCode == REQUEST_ARTWORK_PICKER) {
            if (resultCode == RESULT_OK) {
                val uri = data?.data
                if (uri != null) importArtworkSelection(uri) else clearPendingArtworkSelection()
            } else {
                clearPendingArtworkSelection()
            }
            return
        }''',
        "restore artwork target by stable preference key after picker result",
    )

    source = replace_once(
        source,
        '''        previewCard.addView(previewHeader)
        val previewDetails = text("No details", 12, COLOR_TEXT)''',
        '''        previewCard.addView(previewHeader)
        val previewArtworkLabels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        previewArtworkLabels.addView(text("LARGE IMAGE", 9, COLOR_MUTED, true, mono = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        previewArtworkLabels.addView(text("SMALL IMAGE", 9, COLOR_MUTED, true, mono = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        previewCard.addView(previewArtworkLabels, marginTop(14))
        val previewArtworkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val previewLargeArtwork = ImageView(this).apply {
            contentDescription = "Large artwork preview"
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackground(outline(COLOR_LINE, COLOR_PANEL))
            setImageResource(android.R.drawable.ic_menu_gallery)
        }
        val previewSmallArtwork = ImageView(this).apply {
            contentDescription = "Small artwork preview"
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackground(outline(COLOR_LINE, COLOR_PANEL))
            setImageResource(android.R.drawable.ic_menu_gallery)
        }
        previewArtworkRow.addView(previewLargeArtwork,
            LinearLayout.LayoutParams(0, dp(112), 1f).apply { marginEnd = dp(5) })
        previewArtworkRow.addView(previewSmallArtwork,
            LinearLayout.LayoutParams(0, dp(112), 1f).apply { marginStart = dp(5) })
        previewCard.addView(previewArtworkRow, marginTop(5))
        val previewDetails = text("No details", 12, COLOR_TEXT)''',
        "add Large/Small artwork images to Studio preview",
    )

    source = replace_once(
        source,
        '''            previewAccountStatus.text = "ACCOUNT · $accountLabel"
        }''',
        '''            previewAccountStatus.text = "ACCOUNT · $accountLabel"
            loadArtworkPreview(previewLargeArtwork, largeImage.text.toString())
            loadArtworkPreview(previewSmallArtwork, smallImage.text.toString())
        }''',
        "refresh artwork images whenever Studio preview updates",
    )

    source = replace_once(
        source,
        '''        listOf(name, details, state).forEach { field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updatePreview()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }''',
        '''        listOf(name, details, state, largeImage, smallImage).forEach { field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updatePreview()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }''',
        "refresh Studio image preview as artwork fields change",
    )

    source = replace_once(
        source,
        'launchArtworkPicker(largeImage)',
        'launchArtworkPicker(KEY_STUDIO_DRAFT_LARGE_IMAGE)',
        "persist Large Image picker target",
    )
    source = replace_once(
        source,
        'launchArtworkPicker(smallImage)',
        'launchArtworkPicker(KEY_STUDIO_DRAFT_SMALL_IMAGE)',
        "persist Small Image picker target",
    )
    source = replace_once(
        source,
        '''        editor.addView(largeTooltip, marginTop(6))
        editor.addView(text("ARTWORK · SMALL IMAGE", 10, COLOR_ACCENT, true, mono = true), marginTop(12))''',
        '''        editor.addView(largeTooltip, marginTop(6))
        editor.addView(text("This is the text Discord shows when someone hovers over the large artwork. It is not a help tooltip for this editor.", 10, COLOR_MUTED), marginTop(4))
        editor.addView(text("ARTWORK · SMALL IMAGE", 10, COLOR_ACCENT, true, mono = true), marginTop(12))''',
        "explain Rich Presence artwork tooltip semantics",
    )
    source = replace_once(
        source,
        '''        editor.addView(smallTooltip, marginTop(6))
        editor.addView(text("RICH PRESENCE BUTTONS · MAX 2", 10, COLOR_ACCENT, true, mono = true), marginTop(16))''',
        '''        editor.addView(smallTooltip, marginTop(6))
        editor.addView(text("This text is displayed by Discord when someone hovers over the small artwork.", 10, COLOR_MUTED), marginTop(4))
        editor.addView(text("RICH PRESENCE BUTTONS · MAX 2", 10, COLOR_ACCENT, true, mono = true), marginTop(16))''',
        "explain Small Image tooltip semantics",
    )

    # Add a thumbnail, stable UUID, and Copy ID action to each Gallery item.
    source = replace_once(
        source,
        '''                gallery.addView(text(item.displayName, 13, COLOR_TEXT, true), marginTop(8))
                gallery.addView(text("${item.mimeType} · ${item.byteCount / 1024} KiB\\n${item.remoteUrl ?: "LOCAL ONLY · NOT UPLOADED"}", 10, COLOR_MUTED, mono = true), marginTop(4))''',
        '''                gallery.addView(text(item.displayName, 13, COLOR_TEXT, true), marginTop(8))
                val thumbnail = ImageView(this).apply {
                    contentDescription = "${item.displayName} artwork thumbnail"
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackground(outline(COLOR_LINE, COLOR_PANEL))
                    setImageResource(android.R.drawable.ic_menu_gallery)
                }
                gallery.addView(thumbnail, LinearLayout.LayoutParams(dp(112), dp(112)).apply { topMargin = dp(8) })
                loadArtworkPreview(thumbnail, item.id)
                gallery.addView(text("ID · ${item.id}", 9, COLOR_MUTED, mono = true), marginTop(6))
                gallery.addView(actionButton("COPY IMAGE ID", false) {
                    copyText(item.id, "Artwork ID copied")
                }, marginTop(4))
                gallery.addView(text("${item.mimeType} · ${item.byteCount / 1024} KiB\\n${item.remoteUrl ?: "LOCAL ONLY · NOT UPLOADED"}", 10, COLOR_MUTED, mono = true), marginTop(4))''',
        "give Gallery entries a preview and stable copyable ID",
    )
    source = replace_once(
        source,
        '                        pendingArtworkField = null\n                        confirmArtworkUpload(item)',
        '                        clearPendingArtworkSelection()\n                        confirmArtworkUpload(item)',
        "use no Studio destination for Gallery-only uploads",
    )

    # Replace picker/import/upload methods as one block, retaining stable pending metadata.
    old_methods_start = '    private fun launchArtworkPicker(target: EditText) {'
    old_methods_end = '    private fun buildSettings() {'
    replacement_methods = '''    private fun launchArtworkPicker(targetKey: String) {
        if (artworkUploadInProgress || artworkImportInProgress) {
            Toast.makeText(this, "An artwork operation is already running", Toast.LENGTH_SHORT).show()
            return
        }
        if (targetKey !in setOf(KEY_STUDIO_DRAFT_LARGE_IMAGE, KEY_STUDIO_DRAFT_SMALL_IMAGE)) {
            Toast.makeText(this, "Invalid Studio artwork destination", Toast.LENGTH_SHORT).show()
            return
        }
        // Persist a logical target, not an EditText reference that can disappear when
        // the Activity is recreated or the user visits Settings to configure Imgur.
        settings.edit()
            .putString(KEY_PENDING_ARTWORK_TARGET, targetKey)
            .remove(KEY_PENDING_ARTWORK_ID)
            .commit()
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivityForResult(picker, REQUEST_ARTWORK_PICKER) }
            .onFailure {
                clearPendingArtworkSelection()
                Toast.makeText(this, "Could not open the Android image picker", Toast.LENGTH_LONG).show()
            }
    }

    private fun importArtworkSelection(uri: Uri) {
        if (artworkImportInProgress) return
        artworkImportInProgress = true
        Toast.makeText(this, "Importing artwork into private storage…", Toast.LENGTH_SHORT).show()
        Thread({
            val result = ArtworkGalleryStore.importUri(this, uri)
            runOnUiThread {
                artworkImportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is ArtworkGalleryStore.ImportResult.Rejected -> {
                        clearPendingArtworkSelection()
                        Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                    }
                    is ArtworkGalleryStore.ImportResult.Added -> {
                        settings.edit().putString(KEY_PENDING_ARTWORK_ID, result.artwork.id).commit()
                        confirmArtworkUpload(result.artwork)
                    }
                    is ArtworkGalleryStore.ImportResult.Duplicate -> {
                        settings.edit().putString(KEY_PENDING_ARTWORK_ID, result.artwork.id).commit()
                        confirmArtworkUpload(result.artwork)
                    }
                }
            }
        }, "gecko-artwork-import").start()
    }

    private fun confirmArtworkUpload(artwork: ArtworkGalleryStore.Artwork) {
        artwork.remoteUrl?.let { url ->
            val targetKey = settings.getString(KEY_PENDING_ARTWORK_TARGET, "").orEmpty()
            if (targetKey in setOf(KEY_STUDIO_DRAFT_LARGE_IMAGE, KEY_STUDIO_DRAFT_SMALL_IMAGE)) {
                settings.edit().putString(targetKey, url).commit()
                clearPendingArtworkSelection()
                Toast.makeText(this, "Existing artwork linked to Studio", Toast.LENGTH_SHORT).show()
                showPage(PAGE_STUDIO)
            } else {
                clearPendingArtworkSelection()
                Toast.makeText(this, "Artwork is already hosted", Toast.LENGTH_SHORT).show()
                showPage(PAGE_LIBRARY)
            }
            return
        }
        settings.edit().putString(KEY_PENDING_ARTWORK_ID, artwork.id).commit()
        val clientId = settings.getString(KEY_IMGUR_CLIENT_ID, "").orEmpty()
        if (clientId.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("Imgur Client-ID required")
                .setMessage("The image is saved locally and its Studio destination has been retained. Open Settings, save your Imgur Client-ID, and Gecko will resume this artwork selection.")
                .setPositiveButton("OPEN SETTINGS") { _, _ -> showPage(PAGE_SETTINGS) }
                .setNegativeButton("KEEP LOCAL") { _, _ -> clearPendingArtworkSelection(); showPage(PAGE_LIBRARY) }
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Upload artwork publicly?")
            .setMessage("Gecko will upload '${artwork.displayName}' (${artwork.byteCount / 1024} KiB) to Imgur using anonymous hosting. Anyone with the resulting URL may view it. The local copy remains on this device. Continue?")
            .setNegativeButton("CANCEL") { _, _ -> clearPendingArtworkSelection() }
            .setPositiveButton("UPLOAD") { _, _ -> uploadArtwork(artwork, clientId) }
            .show()
    }

    private fun uploadArtwork(artwork: ArtworkGalleryStore.Artwork, clientId: String) {
        if (artworkUploadInProgress || artworkImportInProgress) return
        artworkUploadInProgress = true
        settings.edit().putString(KEY_PENDING_ARTWORK_ID, artwork.id).commit()
        Toast.makeText(this, "Uploading artwork to Imgur…", Toast.LENGTH_SHORT).show()
        Thread({
            val file = ArtworkGalleryStore.file(this, artwork)
            val result = if (file == null) {
                ImgurAnonymousUploader.Result.Failure("Local artwork file is missing or failed its integrity check.")
            } else {
                ImgurAnonymousUploader.upload(file, clientId)
            }
            val metadataSaved = if (result is ImgurAnonymousUploader.Result.Success) {
                ArtworkGalleryStore.markUploaded(this, artwork.id, result.directUrl)
            } else false
            runOnUiThread {
                artworkUploadInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is ImgurAnonymousUploader.Result.Success -> {
                        if (metadataSaved) {
                            val targetKey = settings.getString(KEY_PENDING_ARTWORK_TARGET, "").orEmpty()
                            if (targetKey in setOf(KEY_STUDIO_DRAFT_LARGE_IMAGE, KEY_STUDIO_DRAFT_SMALL_IMAGE)) {
                                settings.edit().putString(targetKey, result.directUrl).commit()
                                clearPendingArtworkSelection()
                                Toast.makeText(this, "Artwork uploaded and inserted into Studio", Toast.LENGTH_LONG).show()
                                showPage(PAGE_STUDIO)
                            } else {
                                clearPendingArtworkSelection()
                                Toast.makeText(this, "Artwork uploaded and saved to Gallery", Toast.LENGTH_LONG).show()
                                showPage(PAGE_LIBRARY)
                            }
                        } else {
                            Toast.makeText(this, "Upload succeeded but local gallery metadata could not be updated", Toast.LENGTH_LONG).show()
                        }
                    }
                    is ImgurAnonymousUploader.Result.Failure ->
                        Toast.makeText(this, "${result.message} Pending artwork selection was preserved for retry.", Toast.LENGTH_LONG).show()
                }
            }
        }, "gecko-artwork-upload").start()
    }

    private fun clearPendingArtworkSelection() {
        settings.edit()
            .remove(KEY_PENDING_ARTWORK_TARGET)
            .remove(KEY_PENDING_ARTWORK_ID)
            .apply()
    }

    private fun resumePendingArtworkUpload() {
        val pendingId = settings.getString(KEY_PENDING_ARTWORK_ID, null) ?: return
        val artwork = ArtworkGalleryStore.list(this).firstOrNull { it.id == pendingId }
        if (artwork == null) {
            clearPendingArtworkSelection()
            Toast.makeText(this, "Pending artwork was not found in Gallery", Toast.LENGTH_LONG).show()
            return
        }
        confirmArtworkUpload(artwork)
    }

    private fun restoreCompletedPendingArtwork() {
        val pendingId = settings.getString(KEY_PENDING_ARTWORK_ID, null) ?: return
        val targetKey = settings.getString(KEY_PENDING_ARTWORK_TARGET, "").orEmpty()
        if (targetKey !in setOf(KEY_STUDIO_DRAFT_LARGE_IMAGE, KEY_STUDIO_DRAFT_SMALL_IMAGE)) return
        val hostedUrl = ArtworkGalleryStore.list(this)
            .firstOrNull { it.id == pendingId }
            ?.remoteUrl
            ?: return
        settings.edit()
            .putString(targetKey, hostedUrl)
            .remove(KEY_PENDING_ARTWORK_TARGET)
            .remove(KEY_PENDING_ARTWORK_ID)
            .apply()
    }

    private fun copyText(value: String, message: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Gecko Presence", value))
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** Local preview lookup is intentionally limited to files Gecko already imported.
     *  It never fetches arbitrary user-entered URLs or performs network I/O on the UI thread.
     */
    private fun loadArtworkPreview(target: ImageView, reference: String) {
        val key = reference.trim()
        if (target.tag == key && target.drawable != null) return
        target.tag = key
        target.setImageResource(android.R.drawable.ic_menu_gallery)
        if (key.isBlank()) return
        val artwork = ArtworkGalleryStore.list(this).firstOrNull {
            it.id == key || it.remoteUrl.equals(key, ignoreCase = false)
        } ?: return
        val file = ArtworkGalleryStore.previewFile(this, artwork) ?: return
        artworkPreviewExecutor().execute {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 480 || bounds.outHeight / sample > 480) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            })
            runOnUiThread {
                if (!isFinishing && !isDestroyed && target.tag == key && bitmap != null) {
                    target.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun artworkPreviewExecutor(): java.util.concurrent.ExecutorService {
        return artworkPreviewExecutor ?: java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "gecko-artwork-preview").apply { isDaemon = true }
        }.also { artworkPreviewExecutor = it }
    }

'''
    source = replace_method(source, old_methods_start, old_methods_end, replacement_methods, "replace artwork picker/upload lifecycle")

    # Saving an Imgur ID resumes the pending artwork selection instead of making the user pick again.
    source = replace_once(
        source,
        '''            settings.edit().putString(KEY_IMGUR_CLIENT_ID, value).apply()
            Toast.makeText(this, if (value.isEmpty()) "Imgur hosting disabled" else "Imgur Client-ID saved on this device", Toast.LENGTH_SHORT).show()''',
        '''            settings.edit().putString(KEY_IMGUR_CLIENT_ID, value).apply()
            Toast.makeText(this, if (value.isEmpty()) "Imgur hosting disabled" else "Imgur Client-ID saved on this device", Toast.LENGTH_SHORT).show()
            if (value.isNotEmpty()) resumePendingArtworkUpload()''',
        "resume pending upload when the Client-ID is saved",
    )

    # Library now makes each preset identifiable and previewable, and supports duplication.
    source = replace_once(
        source,
        '''            entry.addView(text(preset.label, 14, COLOR_TEXT, true))
            entry.addView(text("${preset.name}\\n${preset.details}${preset.state?.let { "\\n$it" }.orEmpty()}", 11, COLOR_MUTED), marginTop(6))
            entry.addView(actionButton("LOAD INTO STUDIO", true) {''',
        '''            entry.addView(text(preset.label, 14, COLOR_TEXT, true))
            entry.addView(text("PRESET ID · ${preset.id}", 9, COLOR_MUTED, mono = true), marginTop(5))
            entry.addView(actionButton("COPY PRESET ID", false) { copyText(preset.id, "Preset ID copied") }, marginTop(4))
            val presetArtworkRef = runCatching {
                org.json.JSONObject(preset.advancedFieldsJson).optString(KEY_STUDIO_DRAFT_LARGE_IMAGE)
            }.getOrDefault("")
            val presetArtworkPreview = ImageView(this).apply {
                contentDescription = "${preset.label} artwork preview"
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackground(outline(COLOR_LINE, COLOR_PANEL))
                setImageResource(android.R.drawable.ic_menu_gallery)
            }
            entry.addView(presetArtworkPreview, LinearLayout.LayoutParams(dp(112), dp(112)).apply { topMargin = dp(8) })
            loadArtworkPreview(presetArtworkPreview, presetArtworkRef)
            entry.addView(text("${preset.name}\\n${preset.details}${preset.state?.let { "\\n$it" }.orEmpty()}", 11, COLOR_MUTED), marginTop(6))
            entry.addView(actionButton("DUPLICATE PRESET", false) {
                PresencePresetStore.duplicate(this, preset.id)
                showPage(PAGE_LIBRARY)
            }, marginTop(8))
            entry.addView(actionButton("LOAD INTO STUDIO", true) {''',
        "add stable preset IDs, image preview, and duplicate action",
    )

    source = replace_once(
        source,
        '        private const val KEY_STUDIO_DRAFT_STATE = "studio_draft_state"\n',
        '        private const val KEY_STUDIO_DRAFT_STATE = "studio_draft_state"\n'
        '        private const val KEY_STUDIO_DRAFT_LARGE_IMAGE = "studio_draft_large_image"\n'
        '        private const val KEY_STUDIO_DRAFT_SMALL_IMAGE = "studio_draft_small_image"\n'
        '        private const val KEY_PENDING_ARTWORK_TARGET = "pending_artwork_target"\n'
        '        private const val KEY_PENDING_ARTWORK_ID = "pending_artwork_id"\n',
        "define stable Studio artwork and pending-selection preference keys",
    )
    return source


def update_gallery_store(source: str) -> str:
    return replace_once(
        source,
        """    fun file(context: Context, artwork: Artwork): File? {""",
        """    /** UI-only preview access: canonical path and size checks without a full-file SHA-256 pass.
     *  The upload path must continue to use file(), which verifies the stored digest.
     */
    fun previewFile(context: Context, artwork: Artwork): File? {
        val target = safeLocalFile(context, artwork.id, artwork.fileName) ?: return null
        return target.takeIf { it.isFile && it.length() == artwork.byteCount }
    }

    fun file(context: Context, artwork: Artwork): File? {""",
        "avoid hashing full artwork files on every UI preview refresh",
    )


def update_preset_store(source: str) -> str:
    return replace_once(
        source,
        '''    fun delete(context: Context, id: String) {
        persist(context, list(context).filterNot { it.id == id })
    }''',
        '''    fun duplicate(context: Context, id: String): PresencePreset? {
        val original = list(context).firstOrNull { it.id == id } ?: return null
        val existing = list(context).map { it.label.lowercase() }.toSet()
        var suffix = 1
        var label: String
        do {
            val ending = if (suffix == 1) " copy" else " copy $suffix"
            label = original.label.take((48 - ending.length).coerceAtLeast(1)) + ending
            suffix++
        } while (label.lowercase() in existing && suffix <= MAX_PRESETS + 2)
        if (label.lowercase() in existing) return null
        return save(
            context = context,
            label = label,
            name = original.name,
            details = original.details,
            state = original.state,
            advancedFieldsJson = original.advancedFieldsJson,
        )
    }

    fun delete(context: Context, id: String) {
        persist(context, list(context).filterNot { it.id == id })
    }''',
        "support stable preset duplication",
    )


def update_verifier(source: str) -> str:
    source = replace_once(
        source,
        '''# WebView import boundary remains narrow and user-driven.
login_path = app / "src/main/java/app/geckopresence/host/DiscordLoginActivity.kt"
cleanup_path = app / "src/main/java/app/geckopresence/host/DiscordWebSessionCleanup.kt"
login = login_path.read_text()
cleanup = cleanup_path.read_text()
require(not re.search(r"addJavascriptInterface", login, re.I), "no JavaScript bridge into native code")
require('DISCORD_LOGIN_URL = "https://discord.com/login"' in login, "sign-in starts at Discord HTTPS login")
require("if (!request.isForMainFrame) return false" in login, "WebView permits isolated challenge subframes while filtering top-level navigations")
require("setOnClickListener { importSessionFromDiscordPage() }" in login, "session import requires explicit user tap")
require("window.localStorage.getItem('token')" in login and "DiscordCredentialVault.save(this, token)" in login, "session value handed only to local encrypted vault")
require("handler?.proceed()" not in login and "handler?.cancel()" in login, "WebView certificate errors fail closed")
require("setAcceptThirdPartyCookies(webView, false)" in login and "WebView.setWebContentsDebuggingEnabled(false)" in login, "WebView cookies and debugging are hardened")
require("CookieManager.getInstance().removeAllCookies" in cleanup and "WebStorage.getInstance().deleteAllData" in cleanup, "temporary WebView session data is cleared")''',
        '''# Session import is explicit and local-only; browser storage is never inspected.
login_path = app / "src/main/java/app/geckopresence/host/DiscordLoginActivity.kt"
login = login_path.read_text()
require("DiscordTokenInputPolicy.normalize" in login and "DiscordCredentialVault.save(this, token)" in login, "pasted session is validated and stored only through the local vault")
require("FLAG_SECURE" in login and "PasswordTransformationMethod" in login and "IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS" in login, "credential input is masked, excluded from autofill and protected from screenshots")
require("DiscordTokenInputPolicy.MAX_TOKEN_LENGTH" in login and "InputFilter.LengthFilter" in login, "pasted session input is length-bounded before validation")
require("evaluateJavascript" not in login and "localStorage" not in login and "addJavascriptInterface" not in login, "session import does not scrape WebView storage or expose a JavaScript bridge")''',
        "replace WebView credential-scraping source contracts with explicit import hardening",
    )
    source = replace_once(
        source,
        'require("REQUEST_ARTWORK_PICKER" in main_activity and "Intent.ACTION_OPEN_DOCUMENT" in main_activity, "Studio uses Android document picker for local artwork import")',
        'require("REQUEST_ARTWORK_PICKER" in main_activity and "Intent.ACTION_OPEN_DOCUMENT" in main_activity, "Studio uses Android document picker for local artwork import")\n'
        'require("KEY_PENDING_ARTWORK_TARGET" in main_activity and "KEY_PENDING_ARTWORK_ID" in main_activity and "pendingArtworkField" not in main_activity, "artwork destination survives Activity recreation without transient view references")\n'
        'require("loadArtworkPreview" in main_activity and "previewLargeArtwork" in main_activity and "previewSmallArtwork" in main_activity, "Studio renders Large and Small artwork previews")\n'
        'require("COPY IMAGE ID" in main_activity and "PRESET ID" in main_activity and "COPY PRESET ID" in main_activity, "Gallery and Library expose readable stable IDs and copy actions")\n'
        'require("ArtworkGalleryStore.previewFile(this, artwork)" in main_activity and "fun previewFile(context: Context, artwork: Artwork)" in (app / "src/main/java/app/geckopresence/host/ArtworkGalleryStore.kt").read_text(), "preview decoding uses a path/size-checked lightweight file accessor while upload retains SHA-256 verification")\n'
        'require("PresencePresetStore.duplicate(this, preset.id)" in main_activity and "fun duplicate(context: Context, id: String)" in (app / "src/main/java/app/geckopresence/host/PresencePresetStore.kt").read_text(), "Library supports duplicating a saved preset")\nrequire("Executors.newSingleThreadExecutor" in main_activity and "artworkPreviewExecutor?.shutdownNow()" in main_activity, "artwork thumbnails decode on one bounded background worker and stop with the Activity")',
        "add regression contracts for artwork target, Studio preview, IDs and duplication",
    )
    return source

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="Gecko repository root (default: current working directory)")
    args = parser.parse_args()
    root = args.root.resolve()

    files = {
        "login": root / "app/src/main/java/app/geckopresence/host/DiscordLoginActivity.kt",
        "main": root / "app/src/main/java/app/geckopresence/host/MainActivity.kt",
        "presets": root / "app/src/main/java/app/geckopresence/host/PresencePresetStore.kt",
        "gallery": root / "app/src/main/java/app/geckopresence/host/ArtworkGalleryStore.kt",
        "verify": root / "scripts/verify-source.sh",
    }
    missing = [str(path) for path in files.values() if not path.is_file()]
    if missing:
        print("ERROR: run this script from a Gecko 0.6.6 workspace; missing:\n  " + "\n  ".join(missing), file=sys.stderr)
        return 2

    original = {name: path.read_text(encoding="utf-8") for name, path in files.items()}
    updated = {
        "login": update_login(original["login"]),
        "main": update_main(original["main"]),
        "presets": update_preset_store(original["presets"]),
        "gallery": update_gallery_store(original["gallery"]),
        "verify": update_verifier(original["verify"]),
    }

    # Check for accidental no-op patches before touching disk.
    unchanged = [name for name in files if updated[name] == original[name]]
    if unchanged:
        print("ERROR: patch unexpectedly made no changes to: " + ", ".join(unchanged), file=sys.stderr)
        return 3

    backups: list[tuple[Path, Path]] = []
    staged: list[tuple[Path, Path]] = []
    try:
        for name, path in files.items():
            backup = path.with_suffix(path.suffix + ".pre-session-artwork-fix.bak")
            if not backup.exists():
                shutil.copy2(path, backup)
                backups.append((path, backup))
            fd, temp_name = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
            with os.fdopen(fd, "w", encoding="utf-8", newline="") as handle:
                handle.write(updated[name])
                handle.flush()
                os.fsync(handle.fileno())
            staged.append((path, Path(temp_name)))
        for path, temp_path in staged:
            os.replace(temp_path, path)
    except Exception:
        for path, temp_path in staged:
            if temp_path.exists():
                temp_path.unlink()
        for path, backup in backups:
            if backup.exists():
                shutil.copy2(backup, path)
        raise

    print("Applied combined Gecko session-import and artwork UI fix.")
    print("Updated files:")
    for path in files.values():
        print(f"  - {path.relative_to(root)}")
    print("Backups:")
    for path in files.values():
        backup = path.with_suffix(path.suffix + ".pre-session-artwork-fix.bak")
        if backup.exists():
            print(f"  - {backup.relative_to(root)}")
    print("Next: run scripts/test-core.sh, scripts/test-imgur-upload-dynamic.sh, scripts/verify-source.sh, then both Gradle test and assemble tasks.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"ERROR: patch was not applied: {error}", file=sys.stderr)
        raise SystemExit(10)
