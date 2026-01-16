package org.syndes.kotlincomponents

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.charset.Charset

class ReplaceToolActivity : AppCompatActivity() {

    private val REQUEST_TREE = 42

    private var pickedTreeUri: Uri? = null
    private var pickedRoot: DocumentFile? = null

    private lateinit var etFind: EditText
    private lateinit var etReplace: EditText
    private lateinit var etParams: EditText
    private lateinit var btnChoose: Button
    private lateinit var btnReplace: Button
    private lateinit var tvStatus: TextView

    // overlay views
    private var overlay: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_replace_tool)

        etFind = findViewById(R.id.etFind)
        etReplace = findViewById(R.id.etReplace)
        etParams = findViewById(R.id.etParams)
        btnChoose = findViewById(R.id.btnChoose)
        btnReplace = findViewById(R.id.btnReplace)
        tvStatus = findViewById(R.id.tvStatus)

        btnChoose.setOnClickListener {
            // open SAF folder picker (one-time selection; no persist)
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            // optional: start at external storage root
            startActivityForResult(intent, REQUEST_TREE)
        }

        btnReplace.setOnClickListener {
            val find = etFind.text.toString()
            val replace = etReplace.text.toString()
            val params = etParams.text.toString()
            if (find.isEmpty()) {
                Toast.makeText(this, "Find cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pickedRoot == null) {
                Toast.makeText(this, "Please choose a folder with Choose Folder (SAF)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val recursive = params.split(Regex("\\s+")).any { it == "-r" }
            val ignoreCase = params.split(Regex("\\s+")).any { it == "-i" }
            // start processing
            showOverlay()
            tvStatus.text = "Starting..."
            lifecycleScope.launchWhenStarted {
                val result = withContext(Dispatchers.IO) {
                    processReplace(pickedRoot!!, find, replace, recursive, ignoreCase)
                }
                hideOverlay()
                tvStatus.text = "Done. Files changed: ${result.filesModified}, Replacements: ${result.totalReplacements}"
                Toast.makeText(this@ReplaceToolActivity,
                    "Done. Files changed: ${result.filesModified}, Replacements: ${result.totalReplacements}",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_TREE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                // do NOT take persistable permission -> one-time selection behavior
                // contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                pickedTreeUri = uri
                pickedRoot = DocumentFile.fromTreeUri(this, uri)
                tvStatus.text = "Picked: ${pickedRoot?.name ?: uri.path}"
            }
        }
    }

    private data class ReplaceResult(val filesModified: Int, val totalReplacements: Int)

    private fun processReplace(root: DocumentFile, find: String, replace: String, recursive: Boolean, ignoreCase: Boolean): ReplaceResult {
        var filesModified = 0
        var totalReplacements = 0

        fun shouldSkipByMime(mime: String?): Boolean {
            if (mime == null) return false
            val lower = mime.lowercase()
            // skip images, video, audio, apk, font, zip, etc
            if (lower.startsWith("image/") || lower.startsWith("video/") || lower.startsWith("audio/")) return true
            if (lower.contains("zip") || lower.contains("font") || lower.contains("octet-stream")) return true
            return false
        }

        fun isProbablyTextByExt(name: String?): Boolean {
            if (name == null) return false
            val lower = name.lowercase()
            val textExt = listOf(
                ".txt", ".md", ".json", ".xml", ".html", ".htm", ".csv",
                ".properties", ".yml", ".yaml", ".gradle", ".java", ".kt",
                ".kts", ".cpp", ".c", ".h", ".py", ".sh", ".js", ".css", ".php"
            )
            return textExt.any { lower.endsWith(it) }
        }

        fun processFile(doc: DocumentFile) {
            try {
                val mime = doc.type
                if (shouldSkipByMime(mime)) return
                if (!doc.isFile) return

                val input = contentResolver.openInputStream(doc.uri) ?: return
                val data = readAllBytesSafely(input) ?: return
                // detect null bytes -> likely binary
                if (data.contains(0.toByte())) {
                    return
                }
                // try decode as UTF-8 (fallback to system charset)
                val text = try {
                    String(data, Charset.forName("UTF-8"))
                } catch (e: Exception) {
                    try {
                        String(data, Charset.defaultCharset())
                    } catch (ex: Exception) {
                        return
                    }
                }

                // prepare regex for simple literal replacement (not regex replacement)
                val pattern = if (ignoreCase) Regex(Regex.escape(find), setOf(RegexOption.IGNORE_CASE)) else Regex(Regex.escape(find))
                val count = pattern.findAll(text).count()
                if (count == 0) return

                val replaced = pattern.replace(text, replace)

                // write back
                val out = contentResolver.openOutputStream(doc.uri, "rwt") ?: return
                out.use { it.write(replaced.toByteArray(Charsets.UTF_8)) }

                filesModified += 1
                totalReplacements += count
            } catch (e: Exception) {
                Log.w("ReplaceTool", "skip file ${doc.uri}", e)
            }
        }

        fun traverse(dir: DocumentFile) {
            val children = dir.listFiles()
            for (child in children) {
                if (child.isDirectory) {
                    if (recursive) traverse(child)
                } else {
                    // if mime unknown and name has text-ext -> try
                    if (child.type == null && !isProbablyTextByExt(child.name)) {
                        // attempt to read first bytes and check for null -> safekeep
                        val peek = try { contentResolver.openInputStream(child.uri)?.use { readPeekBytes(it, 512) } } catch (e: Exception) { null }
                        if (peek == null || peek.contains(0.toByte())) continue
                    }
                    processFile(child)
                }
            }
        }

        traverse(root)
        return ReplaceResult(filesModified, totalReplacements)
    }

    private fun readAllBytesSafely(input: InputStream): ByteArray? {
        return try {
            BufferedInputStream(input).use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun readPeekBytes(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        val n = input.read(buffer)
        return if (n == -1) ByteArray(0) else buffer.copyOf(n)
    }

    // overlay UI: black background, centered rotating amber text "REPLACING", bottom "working..."
    private fun showOverlay() {
        runOnUiThread {
            if (overlay != null) return@runOnUiThread
            val root = window.decorView as? FrameLayout ?: return@runOnUiThread
            overlay = FrameLayout(this).apply {
                setBackgroundColor(0xFF000000.toInt()) // #000000
                isClickable = true
                isFocusable = true
            }

            val tv = TextView(this).apply {
                text = "REPLACING"
                textSize = 36f
                setTextColor(0xFFFFBF00.toInt()) // amber
                gravity = Gravity.CENTER
            }

            val working = TextView(this).apply {
                text = "working..."
                textSize = 14f
                setTextColor(0xFFFFBF00.toInt())
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

            // rotation animation
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
