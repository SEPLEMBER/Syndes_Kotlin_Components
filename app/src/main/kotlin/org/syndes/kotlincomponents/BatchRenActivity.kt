package org.syndes.kotlincomponents

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Patterns
import android.view.View
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
import kotlin.math.max

class BatchRenActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_OPEN_TREE = 42
        private const val NEON_GREEN = "#39FF14"
    }

    // UI
    private lateinit var selectFolderBtn: Button
    private lateinit var folderNameTv: TextView
    private lateinit var sourcePatternEt: EditText
    private lateinit var renamePatternEt: EditText
    private lateinit var flagsEt: EditText
    private lateinit var instructionTv: TextView
    private lateinit var renameBtn: Button
    private lateinit var statusTv: TextView

    // Selected folder (SAF)
    private var treeUri: Uri? = null
    private var treeDoc: DocumentFile? = null

    // job for animated dots
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

        selectFolderBtn.setOnClickListener {
            openFolderPicker()
        }

        renameBtn.setOnClickListener {
            startRenameProcess()
        }

        // Instruction (хардкод)
        instructionTv.text = buildInstructionText()
    }

    private fun buildInstructionText(): String {
        return "Инструкция:\n" +
                "- В поле 1 вводите что искать (обычно расширение, например: .txt). Можно ввести пусто — тогда будут все файлы.\n" +
                "- В поле 2 вводите правило переименования. Примеры:\n" +
                "    • .py — просто заменить расширение на .py\n" +
                "    • IMG_\$numb.jpg — переименовать в IMG_1.jpg, IMG_2.jpg …\n" +
                "    • rev:IMG_\$numb.jpg — то же самое, но в обратном порядке по дате (новые → старые).\n" +
                "- В поле флагов укажите: -I (ignore case), -r (рекурсивно). Пример: \"-I -r\".\n" +
                "- Нажмите Select folder → выберите папку (используется SAF). После выхода из активности выбор не сохраняется.\n" +
                "- Нажмите Rename, процесс запустится в фоне (корутины), под кнопкой будет Status: working с анимацией."
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
            // Important: we DO NOT persist permission intentionally, so it will be forgotten after exit
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

        val sourcePattern = sourcePatternEt.text.toString().trim()
        var renamePatternRaw = renamePatternEt.text.toString().trim()
        val flags = flagsEt.text.toString()

        val ignoreCase = flags.contains("-I")
        val recursive = flags.contains("-r")

        // reverse-order flag embedded in rename pattern: rev:NAME
        var reverseOrder = false
        if (renamePatternRaw.startsWith("rev:", ignoreCase = true)) {
            reverseOrder = true
            renamePatternRaw = renamePatternRaw.substring(4)
        }

        // start status animation
        dotsJob?.cancel()
        statusTv.text = "Status: working"
        dotsJob = startDotsAnimation()

        // run rename in coroutine
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    processRename(
                        tree,
                        sourcePattern,
                        renamePatternRaw,
                        ignoreCase,
                        recursive,
                        reverseOrder
                    )
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
        sourcePattern: String,
        renamePattern: String,
        ignoreCase: Boolean,
        recursive: Boolean,
        reverseOrder: Boolean
    ) {
        // collect files
        val allFiles = mutableListOf<DocumentFile>()
        collectFiles(root, recursive, allFiles)

        // filter by sourcePattern
        val matching = allFiles.filter { df ->
            val name = df.name ?: return@filter false
            if (sourcePattern.isEmpty()) return@filter true
            // If pattern starts with '.', treat as extension match
            if (sourcePattern.startsWith(".")) {
                if (ignoreCase) {
                    return@filter name.lowercase(Locale.getDefault()).endsWith(sourcePattern.lowercase(Locale.getDefault()))
                } else {
                    return@filter name.endsWith(sourcePattern)
                }
            } else {
                // fallback: suffix match or exact match
                return@filter if (ignoreCase) {
                    name.lowercase(Locale.getDefault()).endsWith(sourcePattern.lowercase(Locale.getDefault()))
                } else {
                    name.endsWith(sourcePattern)
                }
            }
        }.toMutableList()

        if (matching.isEmpty()) return

        // order by lastModified (date added unavailable via SAF reliably). lastModified used.
        matching.sortBy { it.lastModified() ?: 0L }
        if (reverseOrder) matching.reverse()

        // If renamePattern contains $numb -> sequential renaming
        if (renamePattern.contains("\$numb")) {
            var counter = 1
            for (file in matching) {
                val parent = file.parentFile ?: continue
                val extFromPattern = extractExtensionFromPattern(renamePattern)
                val newNameWithoutExt = renamePattern.replace("\$numb", counter.toString())
                val desiredName = if (extFromPattern != null) {
                    newNameWithoutExt // pattern included extension
                } else {
                    // append original extension if pattern has no extension
                    val origExt = getExtension(file.name)
                    "$newNameWithoutExt${if (origExt != null) origExt else ""}"
                }
                safeCopyAndReplace(file, parent, desiredName)
                counter++
            }
        } else {
            // renamePattern is probably an extension (like .py) or fixed name
            if (renamePattern.startsWith(".")) {
                // replace extension for every file
                for (file in matching) {
                    val parent = file.parentFile ?: continue
                    val baseName = stripExtension(file.name)
                    val desiredName = baseName + renamePattern
                    safeCopyAndReplace(file, parent, desiredName)
                }
            } else {
                // If single file -> rename to given name (keep extension)
                if (matching.size == 1) {
                    val file = matching.first()
                    val parent = file.parentFile ?: return
                    val origExt = getExtension(file.name) ?: ""
                    val desiredName = renamePattern + origExt
                    safeCopyAndReplace(file, parent, desiredName)
                } else {
                    // Multiple files and pattern has no $numb — append suffix indices
                    var counter = 1
                    for (file in matching) {
                        val parent = file.parentFile ?: continue
                        val origExt = getExtension(file.name) ?: ""
                        val desiredName = "${renamePattern}_${counter}${origExt}"
                        safeCopyAndReplace(file, parent, desiredName)
                        counter++
                    }
                }
            }
        }
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

    private fun extractExtensionFromPattern(pattern: String): String? {
        val idx = pattern.lastIndexOf('.')
        if (idx >= 0 && idx < pattern.length - 1) {
            return pattern.substring(idx)
        }
        return null
    }

    private fun stripExtension(name: String?): String {
        if (name == null) return ""
        val idx = name.lastIndexOf('.')
        return if (idx >= 0) name.substring(0, idx) else name
    }

    private fun getExtension(name: String?): String? {
        if (name == null) return null
        val idx = name.lastIndexOf('.')
        return if (idx >= 0) name.substring(idx) else null
    }

    private fun safeCopyAndReplace(source: DocumentFile, parent: DocumentFile, desiredName: String) {
        // Determine MIME type (try to reuse original)
        val origUri = source.uri
        val mime = contentResolver.getType(origUri) ?: "application/octet-stream"

        // read into RAM
        val bytes = readBytesFromUri(origUri)

        // ensure unique desired name
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

        // create new file and write
        val created = parent.createFile(mime, candidate)
            ?: throw RuntimeException("Cannot create file $candidate in ${parent.uri}")

        contentResolver.openOutputStream(created.uri)?.use { os ->
            os.write(bytes)
            os.flush()
        } ?: throw RuntimeException("Cannot open output for ${created.uri}")

        // delete original
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
