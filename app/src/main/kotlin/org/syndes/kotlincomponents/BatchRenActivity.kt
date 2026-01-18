package org.syndes.kotlincomponents

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.*

class BatchRenActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_OPEN_TREE = 42
        private const val NEON_GREEN = "#39FF14"
    }

    private lateinit var selectFolderBtn: Button
    private lateinit var folderNameTv: TextView
    private lateinit var sourcePatternEt: EditText
    private lateinit var renamePatternEt: EditText
    private lateinit var flagsEt: EditText
    private lateinit var instructionTv: TextView
    private lateinit var renameBtn: Button
    private lateinit var statusTv: TextView

    private var treeUri: Uri? = null
    private var treeDoc: DocumentFile? = null
    private var dotsJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch_ren)

        selectFolderBtn = findViewById(R.id.btn_select_folder)
        folderNameTv = findViewById(R.id.tv_folder_name)
        sourcePatternEt = findViewById(R.id.et_source)
        renamePatternEt = findViewById(R.id.et_rename)
        flagsEt = findViewById(R.id.et_flags)
        instructionTv = findViewById(R.id.tv_instruction)
        renameBtn = findViewById(R.id.btn_rename)
        statusTv = findViewById(R.id.tv_status)

        selectFolderBtn.setOnClickListener { openFolderPicker() }
        renameBtn.setOnClickListener { startRenameProcess() }
        instructionTv.text = buildInstructionText()
    }

    private fun buildInstructionText(): String {
        return "Инструкция:\n" +
                "- Поле 1: что искать (обычно расширение, например: .txt). Пусто — все файлы.\n" +
                "- Поле 2: правило переименования. Примеры:\n" +
                "    • .py — заменить расширение на .py\n" +
                "    • IMG_\$numb.jpg — последовательность IMG_1.jpg, IMG_2.jpg …\n" +
                "    • rev:IMG_\$numb.jpg — то же, но в обратном порядке по дате.\n" +
                "- Флаги: -I/-i (игнорировать регистр), -r (рекурсивно). Можно писать вместе: -ir, -ri или с пробелами.\n" +
                "- Select folder (SAF). Права не сохраняются (по требованию).\n" +
                "- Нажмите Rename — процесс в корутинах; под кнопкой Status: working с анимацией."
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
        }
        startActivityForResult(intent, REQUEST_CODE_OPEN_TREE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OPEN_TREE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            // intentionally NOT taking persistable permission per spec
            treeUri = uri
            treeDoc = DocumentFile.fromTreeUri(this, uri)
            folderNameTv.text = "Folder: ${treeDoc?.name ?: uri.path}"
        }
    }

    private fun startRenameProcess() {
        val tree = treeDoc
        if (tree == null) {
            statusTv.text = "Status: select a folder first"
            return
        }

        val sourcePatternRaw = sourcePatternEt.text.toString().trim()
        var renamePatternRaw = renamePatternEt.text.toString().trim()
        val flagsRaw = flagsEt.text.toString()

        // Normalize flags case-insensitively
        val compactFlags = flagsRaw.lowercase(Locale.getDefault()).replace("\\s+".toRegex(), "")
        val ignoreCase = compactFlags.contains("-i")
        val recursive = compactFlags.contains("-r")

        // reverse-order embedded in rename pattern: rev:
        var reverseOrder = false
        if (renamePatternRaw.startsWith("rev:", ignoreCase = true)) {
            reverseOrder = true
            renamePatternRaw = renamePatternRaw.substring(4)
        }

        dotsJob?.cancel()
        statusTv.text = "Status: working"
        dotsJob = startDotsAnimation()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    processRename(tree, sourcePatternRaw, renamePatternRaw, ignoreCase, recursive, reverseOrder)
                    "Status: done"
                } catch (e: CancellationException) {
                    "Status: cancelled"
                } catch (t: Throwable) {
                    "Status: error: ${t.message}"
                }
            }
            dotsJob?.cancel()
            statusTv.text = result
        }
    }

    private fun startDotsAnimation(): Job {
        return lifecycleScope.launch {
            val base = "Status: working"
            var dots = 0
            while (isActive) {
                val dotsStr = ".".repeat(dots % 4)
                statusTv.text = base + " $dotsStr"
                dots++
                delay(400)
            }
        }
    }

    private fun processRename(
        root: DocumentFile,
        sourcePatternRaw: String,
        renamePattern: String,
        ignoreCase: Boolean,
        recursive: Boolean,
        reverseOrder: Boolean
    ) {
        val allFiles = mutableListOf<DocumentFile>()
        collectFiles(root, recursive, allFiles)

        val sourcePattern = sourcePatternRaw.trim()
        val matching = allFiles.filter { df ->
            val name = df.name ?: return@filter false
            if (sourcePattern.isEmpty()) return@filter true
            // exact suffix match of sourcePattern (case-insensitive if requested)
            if (ignoreCase) {
                name.lowercase(Locale.getDefault()).endsWith(sourcePattern.lowercase(Locale.getDefault()))
            } else {
                name.endsWith(sourcePattern)
            }
        }.toMutableList()

        if (matching.isEmpty()) return

        // sort by lastModified (old -> new); reverseOrder flips it
        matching.sortBy { it.lastModified() ?: 0L }
        if (reverseOrder) matching.reverse()

        if (renamePattern.contains("\$numb")) {
            var counter = 1
            for (file in matching) {
                val parent = file.parentFile ?: continue
                val desiredName = renamePattern.replace("\$numb", counter.toString())
                val finalName = ensureUniqueName(parent, desiredName)
                safeCopyAndReplace(file, parent, finalName)
                counter++
            }
        } else {
            if (renamePattern.startsWith(".")) {
                // We're replacing the sourcePattern suffix with renamePattern.
                for (file in matching) {
                    val parent = file.parentFile ?: continue
                    val originalName = file.name ?: continue
                    val baseName = removeSourceSuffixExactly(originalName, sourcePattern, ignoreCase)
                    val desiredName = baseName + renamePattern
                    val finalName = ensureUniqueName(parent, desiredName)
                    safeCopyAndReplace(file, parent, finalName)
                }
            } else {
                if (matching.size == 1) {
                    val file = matching.first()
                    val parent = file.parentFile ?: return
                    val origExt = getExtension(file.name) ?: ""
                    val desiredName = renamePattern + origExt
                    val finalName = ensureUniqueName(parent, desiredName)
                    safeCopyAndReplace(file, parent, finalName)
                } else {
                    var counter = 1
                    for (file in matching) {
                        val parent = file.parentFile ?: continue
                        val origExt = getExtension(file.name) ?: ""
                        val desiredName = "${renamePattern}_${counter}${origExt}"
                        val finalName = ensureUniqueName(parent, desiredName)
                        safeCopyAndReplace(file, parent, finalName)
                        counter++
                    }
                }
            }
        }
    }

    /**
     * Remove exactly the sourcePattern suffix from originalName if it matches (respecting ignoreCase).
     * If sourcePattern is empty or doesn't match, fallback to removing the last extension (last dot).
     *
     * Examples:
     *  - removeSourceSuffixExactly("script.txt", ".txt", false) -> "script"
     *  - removeSourceSuffixExactly("a.b.txt", ".txt", false) -> "a.b"
     *  - removeSourceSuffixExactly("file", ".txt", false) -> "file" (no change)
     */
    private fun removeSourceSuffixExactly(originalName: String, sourcePattern: String, ignoreCase: Boolean): String {
        if (sourcePattern.isNotEmpty() && originalName.length > sourcePattern.length) {
            if (ignoreCase) {
                if (originalName.lowercase(Locale.getDefault()).endsWith(sourcePattern.lowercase(Locale.getDefault()))) {
                    return originalName.substring(0, originalName.length - sourcePattern.length)
                }
            } else {
                if (originalName.endsWith(sourcePattern)) {
                    return originalName.substring(0, originalName.length - sourcePattern.length)
                }
            }
        }
        // fallback: remove last extension if present
        val idx = originalName.lastIndexOf('.')
        return if (idx > 0) originalName.substring(0, idx) else originalName
    }

    private fun ensureUniqueName(parent: DocumentFile, desiredName: String): String {
        var candidate = desiredName
        var attempt = 0
        while (parent.findFile(candidate) != null) {
            attempt++
            val dotIdx = desiredName.lastIndexOf('.')
            candidate = if (dotIdx >= 0) {
                val base = desiredName.substring(0, dotIdx)
                val ext = desiredName.substring(dotIdx)
                "${base}_$attempt$ext"
            } else {
                "${desiredName}_$attempt"
            }
        }
        return candidate
    }

    private fun collectFiles(dir: DocumentFile, recursive: Boolean, out: MutableList<DocumentFile>) {
        val children = dir.listFiles()
        for (c in children) {
            if (c.isDirectory) {
                if (recursive) collectFiles(c, true, out)
            } else if (c.isFile) {
                out.add(c)
            }
        }
    }

    private fun getExtension(name: String?): String? {
        if (name == null) return null
        val idx = name.lastIndexOf('.')
        return if (idx >= 0) name.substring(idx) else null
    }

    /**
     * Compute MIME type to use when creating the new file.
     * Priority:
     * 1) If desiredName has extension and MimeTypeMap knows it -> use that MIME
     * 2) Else use origMime (from source)
     * 3) Else "application/octet-stream"
     */
    private fun computeMimeForName(desiredName: String, origMime: String?): String {
        val ext = getExtension(desiredName)
        if (ext != null && ext.length > 1) {
            val extNoDot = ext.substring(1).lowercase(Locale.getDefault())
            val map = MimeTypeMap.getSingleton()
            val mimeFromExt = map.getMimeTypeFromExtension(extNoDot)
            if (!mimeFromExt.isNullOrEmpty()) return mimeFromExt
        }
        return origMime ?: "application/octet-stream"
    }

    /**
     * Copy file bytes (in RAM), create new file with MIME derived from desired name (important),
     * then delete source only after successful write.
     */
    private fun safeCopyAndReplace(source: DocumentFile, parent: DocumentFile, desiredName: String) {
        val origUri = source.uri
        val origMime = contentResolver.getType(origUri)
        // read into RAM
        val bytes = readBytesFromUri(origUri)

        // choose MIME based on desiredName (prevents system appending old extension)
        val desiredMime = computeMimeForName(desiredName, origMime)

        // create file with desired MIME and the exact desiredName
        val created = parent.createFile(desiredMime, desiredName)
            ?: throw RuntimeException("Cannot create file $desiredName in ${parent.uri}")

        contentResolver.openOutputStream(created.uri)?.use { os ->
            os.write(bytes)
            os.flush()
        } ?: throw RuntimeException("Cannot open output for ${created.uri}")

        // delete original AFTER successful create/write
        source.delete()
    }

    private fun readBytesFromUri(uri: Uri): ByteArray {
        contentResolver.openInputStream(uri)?.use { input ->
            return readAllBytes(input)
        } ?: throw RuntimeException("Cannot open input for $uri")
    }

    private fun readAllBytes(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val data = ByteArray(8 * 1024)
        var n: Int
        while (true) {
            n = input.read(data)
            if (n <= 0) break
            buffer.write(data, 0, n)
        }
        return buffer.toByteArray()
    }

    override fun onDestroy() {
        super.onDestroy()
        dotsJob?.cancel()
    }
}
