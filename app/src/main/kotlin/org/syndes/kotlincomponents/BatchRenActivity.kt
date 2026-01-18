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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

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

            if (pickedRoot == null) {
                Toast.makeText(this, "Сначала выберите папку!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Парсинг параметров
            val tokens = params.split(Regex("\\s+")).filter { it.isNotBlank() }
            val recursive = tokens.any { it == "-r" }
            val regexMode = tokens.any { it == "-e" } // Режим Regex
            val ignoreCase = tokens.any { it == "-i" } // Игнор регистра
            val preserveExt = tokens.any { it == "-x" } // Сохранять оригинальное расширение
            val dryRun = tokens.any { it == "--dry-run" } || cbPreviewOnly.isChecked
            
            var collisionStrategy = "rename" // skip, overwrite, rename
            tokens.find { it.startsWith("--collision=") }?.let {
                val parts = it.split("=")
                if (parts.size == 2) collisionStrategy = parts[1]
            }

            // Если поле Find пустое, считаем это как "*" (все файлы)
            val effectivePattern = if (patternText.isEmpty()) "*" else patternText

            showOverlay()
            tvStatus.text = "Scanning..."
            
            lifecycleScope.launch {
                val scanResult = withContext(Dispatchers.IO) {
                    scanForRenames(
                        pickedRoot!!,
                        effectivePattern,
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
                    Toast.makeText(this@BatchRenActivity, "Файлы не найдены (проверьте pattern)", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                markConflicts(scanResult.candidates)

                showPreviewDialog(scanResult.candidates, applyImmediately = !dryRun, collisionStrategy = collisionStrategy, dryRun = dryRun)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_TREE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                pickedTreeUri = uri
                pickedRoot = DocumentFile.fromTreeUri(this, uri)
                tvStatus.text = "Folder: ${pickedRoot?.name ?: uri.path}"
            }
        }
    }

    // --- ЛОГИКА СКАНИРОВАНИЯ ---

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

        // 1. Создаем Regex для поиска
        val matcherRegex: Regex? = try {
            val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            if (regexMode) {
                Regex(patternText, options)
            } else {
                // Конвертируем Wildcard (*.jpg) в Regex (^.*\.jpg$)
                wildcardToRegex(patternText, options)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bad pattern", e)
            null
        }

        fun processFile(doc: DocumentFile, parentDir: DocumentFile) {
            try {
                if (!doc.isFile) return
                val originalName = doc.name ?: return

                // 2. ПРОВЕРКА: Подходит ли файл под маску?
                // Важно: проверяем ПОЛНОЕ имя файла.
                val isMatch = matcherRegex?.matches(originalName) == true || 
                              (!regexMode && matcherRegex?.containsMatchIn(originalName) == true)

                // Если не Regex режим, мы используем строгий matches (полное совпадение по Wildcard)
                // Если Regex режим, ищем вхождение.
                val matched = if (regexMode) {
                    matcherRegex?.containsMatchIn(originalName) == true
                } else {
                    matcherRegex?.matches(originalName) == true
                }

                if (!matched) return

                // 3. Формирование нового имени
                val num = counter.getAndIncrement()
                
                // Раскладываем оригинальное имя на базу и расширение
                val dotIndex = originalName.lastIndexOf('.')
                val hasExt = dotIndex > 0
                val originalBase = if (hasExt) originalName.substring(0, dotIndex) else originalName
                val originalExt = if (hasExt) originalName.substring(dotIndex + 1) else ""

                // Подготавливаем строку для замены (либо полное имя, либо только база, если preserveExt)
                // НО: для Regex Replace нам часто нужно полное имя, чтобы работали группы $1.
                // Поэтому стратегия такая: генерируем новое "тело" имени.
                
                var newNameFull: String

                if (preserveExt) {
                    // Режим сохранения расширения (-x)
                    // Мы применяем шаблон к BASE части (если это не regex, захватывающий всё)
                    // Но проще всего: Сгенерировать имя по шаблону, и жестко прилепить старое расширение.
                    
                    val tempName = applyTemplate(originalBase, patternText, template, regexMode, matcherRegex, num)
                    // Если шаблон вернул пустоту (редко), или просто заменил строку
                    newNameFull = if (originalExt.isNotEmpty()) "$tempName.$originalExt" else tempName
                } else {
                    // Режим полного изменения (включая расширение)
                    // Пример: *.png -> file_{n}.webp
                    newNameFull = applyTemplate(originalName, patternText, template, regexMode, matcherRegex, num)
                }

                // Санитизация (убрать запрещенные символы)
                newNameFull = sanitizeName(newNameFull)

                if (newNameFull != originalName) {
                    candidates.add(RenameCandidate(originalName, doc.uri, parentDir, newNameFull))
                }

            } catch (e: Exception) {
                Log.w(TAG, "Error scanning file ${doc.uri}", e)
            }
        }

        fun traverse(dir: DocumentFile) {
            val children = dir.listFiles()
            for (child in children) {
                if (child.isDirectory && recursive) {
                    traverse(child)
                } else if (!child.isDirectory) {
                    processFile(child, dir)
                }
            }
        }

        if (matcherRegex != null) {
            traverse(root)
        }
        
        return ScanResult(candidates)
    }

    /**
     * Превращает строку с * и ? в Regex
     */
    private fun wildcardToRegex(wildcard: String, options: Set<RegexOption>): Regex {
        val sb = StringBuilder("^")
        wildcard.forEach { char ->
            when (char) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                // Экранируем спецсимволы regex
                '.', '\\', '[', ']', '(', ')', '{', '}', '+', '^', '$', '|' -> sb.append("\\$char")
                else -> sb.append(char)
            }
        }
        sb.append("$")
        return Regex(sb.toString(), options)
    }

    private fun applyTemplate(
        source: String, 
        pattern: String, 
        template: String, 
        regexMode: Boolean, 
        regex: Regex?, 
        number: Int
    ): String {
        // 1. Вставляем числа {n} или {n:003}
        var processedTemplate = template
        val p = Pattern.compile("\\{n(?::(\\d+))?}") // ищет {n} или {n:3}
        val m = p.matcher(template)
        val sb = StringBuffer()
        while (m.find()) {
            val widthStr = m.group(1)
            val rep = if (widthStr != null) {
                val w = widthStr.toIntOrNull() ?: 0
                String.format("%0${w}d", number)
            } else {
                number.toString()
            }
            m.appendReplacement(sb, rep)
        }
        m.appendTail(sb)
        processedTemplate = sb.toString()

        // 2. Применяем замену
        return if (regexMode && regex != null) {
            try {
                // В режиме Regex используем мощь $1, $2 и т.д.
                regex.replace(source, processedTemplate)
            } catch (e: Exception) {
                processedTemplate
            }
        } else {
            // В режиме Wildcard или простого текста мы обычно полностью заменяем имя на шаблон
            // Если в шаблоне нет спецсимволов, просто возвращаем шаблон
            processedTemplate
        }
    }

    private fun sanitizeName(name: String): String {
        return name.replace(Regex("[/\\\\:*?\"<>|\\x00]"), "_")
    }

    // --- ОСТАЛЬНАЯ ЛОГИКА БЕЗ ИЗМЕНЕНИЙ (Конфликты и UI) ---

    private fun markConflicts(candidates: List<RenameCandidate>) {
        // Проверка: новое имя уже существует в папке?
        for (c in candidates) {
            if (c.parent.findFile(c.newName) != null) {
                // Файл существует. Это конфликт, если только это не тот же самый файл (регистр)
                // Но SAF findFile может быть нечувствителен к регистру.
                // Для простоты пометим как конфликт, пользователь решит стратегию.
                if (c.originalName != c.newName) c.conflict = true
            }
        }
        // Проверка: несколько файлов переименовываются в одно и то же имя
        val grouped = candidates.groupBy { Pair(it.parent.uri, it.newName) }
        for ((_, group) in grouped) {
            if (group.size > 1) {
                group.forEach { it.conflict = true }
            }
        }
    }

    private fun showPreviewDialog(candidates: List<RenameCandidate>, applyImmediately: Boolean, collisionStrategy: String, dryRun: Boolean) {
        runOnUiThread {
            val total = candidates.size
            val conflicts = candidates.count { it.conflict }
            
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Preview ($total files)")
            
            val scroll = ScrollView(this)
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(30, 20, 30, 20)
                setBackgroundColor(DARK_BG)
            }

            // Показываем первые 50 чтобы не тормозить UI
            for (c in candidates.take(50)) {
                val itemLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 10, 0, 10)
                }
                
                val originalTv = TextView(this).apply {
                    text = c.originalName
                    setTextColor(0xFFAAAAAA.toInt())
                    textSize = 12f
                }
                
                val arrowTv = TextView(this).apply {
                    text = "  ⬇  " + c.newName + if(c.conflict) " [CONFLICT!]" else ""
                    setTextColor(if (c.conflict) 0xFFFF4444.toInt() else NEON_GREEN)
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }

                itemLayout.addView(originalTv)
                itemLayout.addView(arrowTv)
                container.addView(itemLayout)
                
                val line = View(this).apply { 
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(0xFF333333.toInt())
                }
                container.addView(line)
            }
            
            if (candidates.size > 50) {
                val moreTv = TextView(this).apply { 
                    text = "... and ${candidates.size - 50} more"
                    setTextColor(0xFFAAAAAA.toInt())
                    setPadding(0, 20, 0, 0)
                }
                container.addView(moreTv)
            }

            scroll.addView(container)
            builder.setView(scroll)

            val actionBtnText = if (dryRun) "Close (Dry Run)" else "RENAME ALL"
            
            builder.setPositiveButton(actionBtnText) { dialog, _ ->
                dialog.dismiss()
                if (!dryRun) {
                    performRenaming(candidates, collisionStrategy)
                }
            }
            builder.setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            
            builder.show()
        }
    }

    private fun performRenaming(candidates: List<RenameCandidate>, collisionStrategy: String) {
        showOverlay()
        tvStatus.text = "Renaming..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                applyRenames(candidates, collisionStrategy)
            }
            hideOverlay()
            val msg = "Done: ${result.applied} applied, ${result.skipped} skipped, ${result.failed} failed."
            tvStatus.text = msg
            Toast.makeText(this@BatchRenActivity, msg, Toast.LENGTH_LONG).show()
            
            // Refresh logic (optional: reload list)
        }
    }

    private fun applyRenames(candidates: List<RenameCandidate>, collisionStrategy: String): ApplyResult {
        var applied = 0
        var skipped = 0
        var failed = 0

        for (c in candidates) {
            try {
                val parent = c.parent
                var targetName = c.newName
                
                // Проверяем коллизии в реальном времени
                val existingFile = parent.findFile(targetName)
                if (existingFile != null && existingFile.exists()) {
                     // Если это тот же файл (изменение регистра), пропускаем проверку коллизий
                     if (c.originalName == c.newName) {
                         // ничего не делаем, renameTo сработает (на некоторых ФС) или нет
                     } else {
                         when (collisionStrategy) {
                             "skip" -> { skipped++; continue }
                             "overwrite" -> { try { existingFile.delete() } catch(e:Exception){} }
                             else -> { 
                                 // Auto rename: file.txt -> file_1.txt
                                 var idx = 1
                                 val dot = targetName.lastIndexOf('.')
                                 val base = if(dot>0) targetName.substring(0,dot) else targetName
                                 val ext = if(dot>0) targetName.substring(dot+1) else ""
                                 
                                 while(parent.findFile(targetName) != null) {
                                     targetName = if(ext.isNotEmpty()) "${base}_$idx.$ext" else "${base}_$idx"
                                     idx++
                                 }
                             }
                         }
                     }
                }

                val doc = DocumentFile.fromSingleUri(this, c.uri) ?: continue
                if (doc.renameTo(targetName)) {
                    applied++
                } else {
                    // Fallback для некоторых провайдеров, которые не поддерживают renameTo
                    // (Копирование -> Удаление оригинала). Это медленно.
                    failed++ // Пока считаем за failed, чтобы не усложнять код копированием
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename ${c.originalName}", e)
                failed++
            }
        }
        return ApplyResult(applied, skipped, failed)
    }

    // --- Overlay UI (без изменений) ---
    private fun showOverlay() {
        runOnUiThread {
            if (overlay != null) return@runOnUiThread
            val root = window.decorView as? FrameLayout ?: return@runOnUiThread
            overlay = FrameLayout(this).apply {
                setBackgroundColor(0xCC000000.toInt())
                isClickable = true; isFocusable = true
            }
            val working = TextView(this).apply {
                text = "WORKING..."
                textSize = 24f
                setTextColor(NEON_GREEN)
                gravity = Gravity.CENTER
            }
            overlay?.addView(working)
            root.addView(overlay)
        }
    }

    private fun hideOverlay() {
        runOnUiThread {
            overlay?.let { (window.decorView as? FrameLayout)?.removeView(it) }
            overlay = null
        }
    }
}
