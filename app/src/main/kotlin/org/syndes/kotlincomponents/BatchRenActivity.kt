package org.syndes.kotlincomponents

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import kotlin.text.Regex
import kotlin.text.RegexOption

class BatchRenActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_TREE = 42
        private const val TAG = "BatchRen"
        private const val NEON_GREEN = 0xFF39FF14.toInt()
        private const val DARK_BG = 0xFF0A0A0A.toInt()
    }

    private var pickedTreeUri: Uri? = null
    private var pickedRoot: DocumentFile? = null

    private lateinit var etFind: EditText
    private lateinit var etReplace: EditText
    private lateinit var etParams: EditText
    private lateinit var btnChoose: Button
    private lateinit var btnReplace: Button
    private lateinit var tvStatus: TextView
    private lateinit var cbPreviewOnly: CheckBox

    private var overlay: FrameLayout? = null

    private data class RenameCandidate(
        val originalName: String,
        val uri: Uri,
        val parent: DocumentFile,
        var newName: String,
        var conflict: Boolean = false
    )

    private data class ScanResult(val candidates: List<RenameCandidate>)

    private data class ApplyResult(val applied: Int, val skipped: Int, val failed: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch_ren)

        etFind = findViewById(R.id.etFind)
        etReplace = findViewById(R.id.etReplace)
        etParams = findViewById(R.id.etParams)
        btnChoose = findViewById(R.id.btnChoose)
        btnReplace = findViewById(R.id.btnReplace)
        tvStatus = findViewById(R.id.tvStatus)
        cbPreviewOnly = findViewById(R.id.cbPreviewOnly)

        btnChoose.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(intent, REQUEST_TREE)
        }

        btnReplace.setOnClickListener {
            val patternText = etFind.text.toString()
            val template = etReplace.text.toString()
            val params = etParams.text.toString()
            if (patternText.isEmpty()) {
                Toast.makeText(this, "Pattern (Find) не может быть пустым", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pickedRoot == null) {
                Toast.makeText(this, "Выберите папку через Choose Folder (SAF)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val tokens = params.split(Regex("\\s+")).filter { it.isNotBlank() }
            val recursive = tokens.any { it == "-r" }
            val regexMode = tokens.any { it == "-e" }
            val ignoreCase = tokens.any { it == "-i" }
            val preserveExt = tokens.any { it == "-x" }
            val dryRun = tokens.any { it == "--dry-run" } || cbPreviewOnly.isChecked
            var collisionStrategy = "rename" // default
            tokens.find { it.startsWith("--collision=") }?.let {
                val parts = it.split("=")
                if (parts.size == 2) collisionStrategy = parts[1]
            }

            // start scanning
            showOverlay()
            tvStatus.text = "Scanning..."
            lifecycleScope.launchWhenStarted {
                val scanResult = withContext(Dispatchers.IO) {
                    scanForRenames(
                        pickedRoot!!,
                        patternText,
                        template,
                        recursive,
                        regexMode,
                        ignoreCase,
                        preserveExt
                    )
                }
                hideOverlay()

                if (scanResult.candidates.isEmpty()) {
                    tvStatus.text = "No candidates found."
                    Toast.makeText(this@BatchRenActivity, "Совпадений не найдено", Toast.LENGTH_SHORT).show()
                    return@launchWhenStarted
                }

                // detect simple conflicts (existing files in parents or duplicate targets within candidates)
                markConflicts(scanResult.candidates)

                if (dryRun) {
                    // show preview dialog; don't apply
                    showPreviewDialog(scanResult.candidates, applyImmediately = false, collisionStrategy = collisionStrategy, dryRun = true)
                } else {
                    // show preview and let user confirm apply
                    showPreviewDialog(scanResult.candidates, applyImmediately = true, collisionStrategy = collisionStrategy, dryRun = false)
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_TREE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                pickedTreeUri = uri
                pickedRoot = DocumentFile.fromTreeUri(this, uri)
                tvStatus.text = "Picked: ${pickedRoot?.name ?: uri.path}"
            }
        }
    }

    /**
     * Scan for files to rename.
     * - pattern applies to filename base (without extension) if preserveExt true, else to full name.
     * - supports regex or simple substring matching.
     * - generates newName by applying template (supports {n} and {n:03} and $1 group placeholders for regex).
     */
    private fun scanForRenames(
        root: DocumentFile,
        patternText: String,
        template: String,
        recursive: Boolean,
        regexMode: Boolean,
        ignoreCase: Boolean,
        preserveExt: Boolean
    ): ScanResult {
        val candidates = mutableListOf<RenameCandidate>()
        val counter = AtomicInteger(1)

        val regex: Regex? = if (regexMode) {
            val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            try {
                Regex(patternText, options)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid regex pattern: ${e.message}")
                null
            }
        } else null

        fun processFile(doc: DocumentFile, parentDir: DocumentFile) {
            try {
                if (!doc.isFile) return
                val name = doc.name ?: return

                // separate base and extension if preserveExt
                val base: String
                val ext: String
                if (preserveExt && name.contains('.')) {
                    val idx = name.lastIndexOf('.')
                    base = name.substring(0, idx)
                    ext = name.substring(idx + 1)
                } else {
                    base = name
                    ext = ""
                }

                val targetForMatch = if (preserveExt) base else name

val matched = if (regexMode && regex != null) {
    regex.containsMatchIn(targetForMatch)
} else {
    if (ignoreCase)
        targetForMatch.lowercase().contains(patternText.lowercase())
    else
        targetForMatch.contains(patternText)
}

                if (!matched) return

                // compute newName (apply replacement on base)
                val num = counter.getAndIncrement()
                var newBase = computeReplacement(base, patternText, template, regexMode, regex, ignoreCase, num)
                // attach extension if preserveExt and template didn't already include an extension
                val templateLooksLikeHasExt = template.contains('.')
                val finalName = if (preserveExt && !templateLooksLikeHasExt && ext.isNotEmpty()) {
                    "$newBase.$ext"
                } else {
                    newBase
                }

                candidates.add(RenameCandidate(originalName = name, uri = doc.uri, parent = parentDir, newName = sanitizeName(finalName)))
            } catch (e: Exception) {
                Log.w(TAG, "scan skip file ${doc.uri}", e)
            }
        }

        fun traverse(dir: DocumentFile) {
            val children = try { dir.listFiles() } catch (e: Exception) { arrayOf<DocumentFile>() }
            for (child in children) {
                if (child.isDirectory) {
                    if (recursive) traverse(child)
                } else {
                    processFile(child, dir)
                }
            }
        }

        traverse(root)
        return ScanResult(candidates)
    }

    /**
     * Compute replacement on a base filename (without extension).
     * Supports:
     * - regexMode: using regex and groups ($1, $2...) inside template
     * - simple: literal substring replacement (replaces all occurrences)
     * - numbering: {n} or {n:03}
     */
    private fun computeReplacement(
        base: String,
        patternText: String,
        template: String,
        regexMode: Boolean,
        regex: Regex?,
        ignoreCase: Boolean,
        number: Int
    ): String {
        // replace numbering placeholders first
        val templateWithNumber = replaceNumberPlaceholders(template, number)

        return try {
            if (regexMode && regex != null) {
                // Use Kotlin Regex replace — it supports $1 etc in replacement string
                try {
                    regex.replace(base, templateWithNumber)
                } catch (e: Exception) {
                    templateWithNumber
                }
            } else {
                // simple literal replacement of the pattern in the base
                if (patternText.isEmpty()) return templateWithNumber
                base.replace(patternText, templateWithNumber, ignoreCase = ignoreCase)
            }
        } catch (e: Exception) {
            templateWithNumber
        }
    }

    private fun replaceNumberPlaceholders(template: String, number: Int): String {
        // pattern: {n} or {n:03}
        var result = template
        val p = Pattern.compile("\\{n(?::(\\d+))?}")
        val m = p.matcher(template)
        val sb = StringBuffer()
        while (m.find()) {
            val widthStr = m.group(1)
            val rep = if (widthStr != null) {
                val w = try { widthStr.toInt() } catch (e: Exception) { 0 }
                String.format("%0${w}d", number)
            } else {
                number.toString()
            }
            m.appendReplacement(sb, rep)
        }
        m.appendTail(sb)
        result = sb.toString()
        return result
    }

    private fun sanitizeName(name: String): String {
        // minimal sanitization: remove slashes and control chars
        return name.replace(Regex("[/\\\\\\x00]"), "_")
    }

    /**
     * Mark conflicts if a target name already exists in parent or if multiple candidates collide.
     */
    private fun markConflicts(candidates: List<RenameCandidate>) {
        // map by parent folder -> set of existing names
        val parentToExisting = mutableMapOf<DocumentFile, MutableSet<String>>()
        for (c in candidates) {
            val parent = c.parent
            val existing = parentToExisting.getOrPut(parent) {
                val set = mutableSetOf<String>()
                try {
                    for (f in parent.listFiles()) {
                        f.name?.let { set.add(it) }
                    }
                } catch (e: Exception) {
                    // ignore
                }
                set
            }
            // conflict exists if newName already in existing (and that existing file is not this same file)
            if (existing.contains(c.newName) && c.originalName != c.newName) {
                c.conflict = true
            }
        }
        // detect internal collisions (multiple candidates targeting same name in same parent)
        val grouped = candidates.groupBy { Pair(it.parent, it.newName) }
        for ((_, group) in grouped) {
            if (group.size > 1) {
                for (c in group) c.conflict = true
            }
        }
    }

    /**
     * Show preview dialog for rename candidates.
     * If applyImmediately == true, positive button will start applying changes.
     * If dryRun == true, nothing will be applied even after pressing Apply (just reports).
     */
    private fun showPreviewDialog(candidates: List<RenameCandidate>, applyImmediately: Boolean, collisionStrategy: String, dryRun: Boolean) {
        runOnUiThread {
            val total = candidates.size
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Preview: $total candidates")
            val scroll = ScrollView(this)
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 20, 20, 20)
                setBackgroundColor(DARK_BG)
            }

            for (c in candidates) {
                val fileTitle = TextView(this).apply {
                    text = c.originalName
                    setTextColor(NEON_GREEN)
                    textSize = 14f
                }
                container.addView(fileTitle)

                val tv = TextView(this).apply {
                    text = "→ ${c.newName}" + if (c.conflict) "  (conflict)" else ""
                    setTextColor(NEON_GREEN)
                    textSize = 12f
                    setPadding(0, 6, 0, 10)
                }
                container.addView(tv)

                val sep = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(NEON_GREEN and 0x55FFFFFF.toInt())
                }
                container.addView(sep)
            }

            scroll.addView(container)
            builder.setView(scroll)

            builder.setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            builder.setPositiveButton(if (applyImmediately && !dryRun) "Apply" else "Close") { dialog, _ ->
                dialog.dismiss()
                if (applyImmediately && !dryRun) {
                    // perform apply
                    showOverlay()
                    tvStatus.text = "Applying..."
                    lifecycleScope.launchWhenStarted {
                        val result = withContext(Dispatchers.IO) {
                            applyRenames(candidates, collisionStrategy)
                        }
                        hideOverlay()
                        tvStatus.text = "Applied: ${result.applied}, Skipped: ${result.skipped}, Failed: ${result.failed}"
                        Toast.makeText(this@BatchRenActivity, "Applied: ${result.applied}, Skipped: ${result.skipped}, Failed: ${result.failed}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    // dry-run / close preview -> show summary
                    val applied = 0
                    val skipped = candidates.count { it.conflict }
                    val failed = 0
                    tvStatus.text = "Preview: candidates=$total, conflicts=$skipped"
                    Toast.makeText(this@BatchRenActivity, "Preview: candidates=$total, conflicts=$skipped", Toast.LENGTH_LONG).show()
                }
            }

            val dialog = builder.create()
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(NEON_GREEN)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(NEON_GREEN)
        }
    }

    /**
     * Apply renames according to collisionStrategy:
     * - skip: skip if conflict
     * - rename: auto-suffix _1, _2 ...
     * - overwrite: attempt to delete existing target and replace
     */
    private fun applyRenames(candidates: List<RenameCandidate>, collisionStrategy: String): ApplyResult {
        var applied = 0
        var skipped = 0
        var failed = 0

        for (c in candidates) {
            try {
                val parent = c.parent
                var targetName = c.newName
                val existing = try { parent.findFile(targetName) } catch (e: Exception) { null }
                if (existing != null && existing.exists() && existing.name != c.originalName) {
                    // there's a conflicting file
                    when (collisionStrategy) {
                        "skip" -> {
                            skipped++
                            continue
                        }
                        "rename" -> {
                            // generate unique name with suffix
                            var idx = 1
                            val (base, ext) = splitNameExt(targetName)
                            var finalName = if (ext.isNotEmpty()) "$base.$ext" else base
                            while (parent.findFile(finalName) != null) {
                                val candidateName = "${base}_$idx"
                                finalName = if (ext.isNotEmpty()) "$candidateName.$ext" else candidateName
                                idx++
                            }
                            targetName = finalName
                        }
                        "overwrite" -> {
                            // try to delete existing target
                            try {
                                existing.delete()
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to delete existing target ${existing.uri}", e)
                                // fallthrough, we'll try rename fallback
                            }
                        }
                        else -> {
                            // default behavior: rename
                            var idx = 1
                            val (base, ext) = splitNameExt(targetName)
                            var finalName = if (ext.isNotEmpty()) "$base.$ext" else base
                            while (parent.findFile(finalName) != null) {
                                val candidateName = "${base}_$idx"
                                finalName = if (ext.isNotEmpty()) "$candidateName.$ext" else candidateName
                                idx++
                            }
                            targetName = finalName
                        }
                    }
                }

                // attempt DocumentFile.renameTo on original doc
                val originalDoc = DocumentFile.fromSingleUri(this, c.uri)
                var success = false
                if (originalDoc != null) {
                    try {
                        success = originalDoc.renameTo(targetName)
                    } catch (e: Exception) {
                        Log.w(TAG, "renameTo failed for ${c.uri} -> $targetName: ${e.message}")
                        success = false
                    }
                }

                // if renameTo not supported or failed: fallback copy+create+delete
                if (!success) {
                    // create new file under parent
                    val mime = originalDoc?.type ?: "application/octet-stream"
                    val newFile = try {
                        parent.createFile(mime, targetName)
                    } catch (e: Exception) {
                        Log.w(TAG, "createFile failed in parent ${parent.uri} for $targetName", e)
                        null
                    }

                    if (newFile == null) {
                        Log.w(TAG, "Cannot create target file for $targetName")
                        failed++
                        continue
                    }

                    // copy bytes
                    val ok = try {
                        contentResolver.openInputStream(c.uri).use { input ->
                            contentResolver.openOutputStream(newFile.uri, "w").use { output ->
                                if (input == null || output == null) {
                                    throw Exception("Stream null")
                                }
                                copyStream(input, output)
                                true
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "copy fallback failed for ${c.uri} -> ${newFile.uri}", e)
                        false
                    }

                    if (!ok) {
                        // cleanup created file if copy failed
                        try { newFile.delete() } catch (e: Exception) { /* ignore */ }
                        failed++
                        continue
                    } else {
                        // delete original
                        try {
                            val orig = DocumentFile.fromSingleUri(this, c.uri)
                            orig?.delete()
                            applied++
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete original ${c.uri}", e)
                            // still count as applied (file copied), but note potential leftover
                            applied++
                        }
                    }
                } else {
                    applied++
                }
            } catch (e: Exception) {
                Log.w(TAG, "apply rename failed for ${c.uri}", e)
                failed++
            }
        }

        return ApplyResult(applied = applied, skipped = skipped, failed = failed)
    }

    private fun splitNameExt(name: String): Pair<String, String> {
        return if (name.contains('.')) {
            val idx = name.lastIndexOf('.')
            Pair(name.substring(0, idx), name.substring(idx + 1))
        } else {
            Pair(name, "")
        }
    }

    private fun copyStream(input: InputStream, output: java.io.OutputStream) {
        BufferedInputStream(input).use { inp ->
            BufferedOutputStream(output).use { out ->
                val buffer = ByteArray(8192)
                var read: Int
                while (true) {
                    read = inp.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
                out.flush()
            }
        }
    }

    // overlay UI: dark background, centered rotating neon-green text "RENAMING", bottom "working..."
    private fun showOverlay() {
        runOnUiThread {
            if (overlay != null) return@runOnUiThread
            val root = window.decorView as? FrameLayout ?: return@runOnUiThread
            overlay = FrameLayout(this).apply {
                setBackgroundColor(0xCC000000.toInt()) // semi-transparent black
                isClickable = true
                isFocusable = true
            }

            val tv = TextView(this).apply {
                text = "(\u00A0\u00A0\u00A0\u00A0\u00A0)"
                textSize = 36f
                setTextColor(NEON_GREEN)
                gravity = Gravity.CENTER
            }

            val working = TextView(this).apply {
                text = "working..."
                textSize = 14f
                setTextColor(NEON_GREEN)
                val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                lp.bottomMargin = 60
                layoutParams = lp
            }

            val centerLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            centerLp.gravity = Gravity.CENTER
            root.addView(overlay)
            overlay?.addView(tv, centerLp)
            overlay?.addView(working)

            val anim = RotateAnimation(0f, 360f, RotateAnimation.RELATIVE_TO_SELF, 0.5f, RotateAnimation.RELATIVE_TO_SELF, 0.5f)
            anim.duration = 1200
            anim.repeatCount = RotateAnimation.INFINITE
            anim.interpolator = LinearInterpolator()
            tv.startAnimation(anim)
        }
    }

    private fun hideOverlay() {
        runOnUiThread {
            overlay?.let {
                (window.decorView as? FrameLayout)?.removeView(it)
                overlay = null
            }
        }
    }
}
