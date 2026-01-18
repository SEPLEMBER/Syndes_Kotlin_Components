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
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_TREE)
        }

        btnReplace.setOnClickListener {
            val patternText = etFind.text.toString()
            val template = etReplace.text.toString()
            val params = etParams.text.toString()

            if (patternText.isEmpty()) {
                Toast.makeText(this, "Pattern (Find) пустой", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pickedRoot == null) {
                Toast.makeText(this, "Сначала выбери папку (SAF)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val tokens = params.split(Regex("\\s+")).filter { it.isNotBlank() }
            val recursive = "-r" in tokens
            val regexMode = "-e" in tokens
            val ignoreCase = "-i" in tokens
            val preserveExt = "-x" in tokens
            val dryRun = "--dry-run" in tokens || cbPreviewOnly.isChecked

            showOverlay()
            tvStatus.text = "Scanning..."

            lifecycleScope.launchWhenStarted {
                val scan = withContext(Dispatchers.IO) {
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

                if (scan.candidates.isEmpty()) {
                    tvStatus.text = "No matches"
                    Toast.makeText(this@BatchRenActivity, "Совпадений не найдено", Toast.LENGTH_SHORT).show()
                    return@launchWhenStarted
                }

                markConflicts(scan.candidates)
                showPreviewDialog(scan.candidates, !dryRun, dryRun)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_TREE && resultCode == Activity.RESULT_OK) {
            data?.data?.let {
                pickedTreeUri = it
                pickedRoot = DocumentFile.fromTreeUri(this, it)
                tvStatus.text = "Picked: ${pickedRoot?.name}"
            }
        }
    }

    private fun scanForRenames(
        root: DocumentFile,
        patternText: String,
        template: String,
        recursive: Boolean,
        regexMode: Boolean,
        ignoreCase: Boolean,
        preserveExt: Boolean
    ): ScanResult {

        val result = mutableListOf<RenameCandidate>()
        val counter = AtomicInteger(1)

        val regex = if (regexMode) {
            try {
                Regex(patternText, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
            } catch (e: Exception) {
                Log.w(TAG, "Bad regex", e)
                null
            }
        } else null

        fun processFile(doc: DocumentFile, parent: DocumentFile) {
            val name = doc.name ?: return
            if (!doc.isFile) return

            val dot = name.lastIndexOf('.')
            val base = if (preserveExt && dot > 0) name.substring(0, dot) else name
            val ext = if (preserveExt && dot > 0) name.substring(dot + 1) else ""

            val matchTarget = if (preserveExt) base else name

            val matched = if (regexMode && regex != null) {
                regex.containsMatchIn(matchTarget)
            } else {
                if (ignoreCase)
                    matchTarget.lowercase().contains(patternText.lowercase())
                else
                    matchTarget.contains(patternText)
            }

            if (!matched) return

            val num = counter.getAndIncrement()
            val newBase = computeReplacement(base, patternText, template, regexMode, regex, ignoreCase, num)

            val finalName =
                if (preserveExt && !template.contains('.') && ext.isNotEmpty())
                    "$newBase.$ext"
                else
                    newBase

            result += RenameCandidate(name, doc.uri, parent, sanitizeName(finalName))
        }

        fun walk(dir: DocumentFile) {
            for (f in dir.listFiles()) {
                if (f.isDirectory && recursive) walk(f)
                else processFile(f, dir)
            }
        }

        walk(root)
        return ScanResult(result)
    }

    private fun computeReplacement(
        base: String,
        patternText: String,
        template: String,
        regexMode: Boolean,
        regex: Regex?,
        ignoreCase: Boolean,
        number: Int
    ): String {

        val numbered = replaceNumberPlaceholders(template, number)

        return if (regexMode && regex != null) {
            try { regex.replace(base, numbered) } catch (_: Exception) { numbered }
        } else {
            base.replace(patternText, numbered, ignoreCase)
        }
    }

    private fun replaceNumberPlaceholders(template: String, number: Int): String {
        val p = Pattern.compile("\\{n(?::(\\d+))?}")
        val m = p.matcher(template)
        val sb = StringBuffer()
        while (m.find()) {
            val w = m.group(1)?.toIntOrNull()
            val rep = if (w != null) String.format("%0${w}d", number) else number.toString()
            m.appendReplacement(sb, rep)
        }
        m.appendTail(sb)
        return sb.toString()
    }

    private fun sanitizeName(name: String): String =
        name.replace(Regex("[/\\\\\\x00]"), "_")

    private fun markConflicts(list: List<RenameCandidate>) {
        val map = mutableMapOf<Pair<DocumentFile, String>, Int>()
        for (c in list) {
            val key = c.parent to c.newName
            map[key] = (map[key] ?: 0) + 1
        }
        for (c in list) {
            if ((map[c.parent to c.newName] ?: 0) > 1 ||
                c.parent.findFile(c.newName) != null && c.originalName != c.newName
            ) c.conflict = true
        }
    }

    private fun showPreviewDialog(list: List<RenameCandidate>, canApply: Boolean, dryRun: Boolean) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(DARK_BG)
        }

        for (c in list) {
            box.addView(TextView(this).apply {
                text = c.originalName
                setTextColor(NEON_GREEN)
            })
            box.addView(TextView(this).apply {
                text = "→ ${c.newName}${if (c.conflict) " (conflict)" else ""}"
                setTextColor(NEON_GREEN)
                textSize = 12f
            })
        }

        AlertDialog.Builder(this)
            .setTitle("Preview (${list.size})")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Close", null)
            .setPositiveButton(if (canApply && !dryRun) "Apply" else "OK") { _, _ ->
                if (canApply && !dryRun) applyRenames(list)
            }
            .show()
    }

    private fun applyRenames(list: List<RenameCandidate>) {
        showOverlay()
        lifecycleScope.launchWhenStarted {
            val res = withContext(Dispatchers.IO) {
                var ok = 0; var skip = 0; var fail = 0
                for (c in list) {
                    val doc = DocumentFile.fromSingleUri(this@BatchRenActivity, c.uri)
                    if (doc == null) { fail++; continue }
                    if (c.conflict) { skip++; continue }
                    if (doc.renameTo(c.newName)) ok++ else fail++
                }
                ApplyResult(ok, skip, fail)
            }
            hideOverlay()
            tvStatus.text = "Applied=${res.applied} Skipped=${res.skipped} Failed=${res.failed}"
        }
    }

    private fun showOverlay() {
        if (overlay != null) return
        val root = window.decorView as FrameLayout
        overlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true
        }
        val tv = TextView(this).apply {
            text = "(\u00A0\u00A0\u00A0\u00A0\u00A0)"
            textSize = 36f
            setTextColor(NEON_GREEN)
            gravity = Gravity.CENTER
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        root.addView(overlay)
        overlay!!.addView(tv, lp)

        tv.startAnimation(RotateAnimation(
            0f, 360f,
            RotateAnimation.RELATIVE_TO_SELF, 0.5f,
            RotateAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 1200
            repeatCount = RotateAnimation.INFINITE
            interpolator = LinearInterpolator()
        })
    }

    private fun hideOverlay() {
        overlay?.let {
            (window.decorView as FrameLayout).removeView(it)
            overlay = null
        }
    }
}
