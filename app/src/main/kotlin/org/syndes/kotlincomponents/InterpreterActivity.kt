package org.syndes.kotlincomponents

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import org.syndes.kotlincomponents.databinding.ActivityInterpreterBinding
import kotlinx.coroutines.*
import org.luaj.vm2.*
import org.luaj.vm2.lib.*
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.PrintWriter
import java.io.StringWriter
import android.view.WindowManager
import android.view.View

class InterpreterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInterpreterBinding
    private var exampleIndex = 0
    private var currentJob: Job? = null // Храним ссылку на текущую корутину

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
                
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityInterpreterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        loadExample()
    }

    private fun setupListeners() {
        binding.btnRun.setOnClickListener {
            // 🔥 ГЛАВНОЕ ИСПРАВЛЕНИЕ: Отменяем предыдущую задачу перед запуском новой
            currentJob?.cancel()
            runLuaCode()
        }

        binding.btnClear.setOnClickListener {
            // Отменяем задачу и очищаем UI
            currentJob?.cancel()
            binding.etCode.text.clear()
            binding.tvOutput.text = "Вывод очищен"
        }

        binding.btnExample.setOnClickListener {
            loadExample()
        }

        binding.tvOutput.setOnLongClickListener {
            val text = binding.tvOutput.text.toString()
            if (text.isNotEmpty() && text != "Вывод очищен" && !text.startsWith("Выполнение...")) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Lua Output", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Вывод скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
                true
            } else {
                false
            }
        }
    }

    // 🔍 ФИЛЬТР ОПАСНЫХ ПАТТЕРНОВ
    // Смягчен: убраны жесткие блокировки while true/1, так как они легитимно 
    // используются с break при работе с массивами. Главной защитой теперь является таймаут.
    private fun checkForDangerousPatterns(code: String): String? {
        val dangerousPatterns = listOf(
            // Regex("""while\s+true\s+do""", RegexOption.IGNORE_CASE), // Смягчено
            // Regex("""while\s+1\s+do""", RegexOption.IGNORE_CASE),    // Смягчено
            Regex("""repeat\s+.*\s+until\s+false""", RegexOption.IGNORE_CASE),
            Regex("""repeat\s+.*\s+until\s+0""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in dangerousPatterns) {
            if (pattern.containsMatchIn(code)) {
                return "Обнаружен потенциально опасный код (бесконечный цикл).\nВыполнение заблокировано фильтром безопасности."
            }
        }
        return null
    }

    private fun runLuaCode() {
        val code = binding.etCode.text.toString()

        if (code.isBlank()) {
            binding.tvOutput.text = "Ошибка: код пустой"
            return
        }

        // Проверяем код на опасные паттерны
        val dangerWarning = checkForDangerousPatterns(code)
        if (dangerWarning != null) {
            binding.tvOutput.text = "⚠️ Блокировка фильтра:\n$dangerWarning"
            return
        }

        binding.tvOutput.text = "Выполнение...\n\n"

        // Запускаем новую корутину и сохраняем ссылку на неё
        currentJob = lifecycleScope.launch(Dispatchers.Default) {
            var output = StringBuilder()
            var errorMessage: String? = null
            var errorType: String = ""

            try {
                val globals = JsePlatform.standardGlobals()

                globals.set("io", LuaValue.NIL)
                globals.set("os", LuaValue.NIL)

                globals.set("print", object : VarArgFunction() {
                    override fun invoke(args: Varargs): Varargs {
                        for (i in 1..args.narg()) {
                            output.append(args.arg(i).tojstring())
                            if (i < args.narg()) output.append("\t")
                        }
                        output.append("\n")
                        return LuaValue.NONE
                    }
                })

                addHelperFunctions(globals, output)

                // 🔥 УВЕЛИЧЕН ТАЙМАУТ: с 30 до 60 секунд для корректной обработки больших массивов
                val completed = withTimeoutOrNull(60_000L) {
                    val chunk = globals.load(code, "script") 
                    chunk.call()
                    true
                }

                if (completed == null) {
                    errorMessage = "Превышено время выполнения (60 секунд)\nВозможно, бесконечный цикл или слишком тяжелая операция с массивами?"
                    errorType = "Таймаут"
                }

            } catch (e: LuaError) {
                errorType = "Ошибка Lua"
                errorMessage = getSafeStackTrace(e, 200) // 🔥 УВЕЛИЧЕН ЛИМИТ СТРОК: с 50 до 200
            } catch (e: StackOverflowError) {
                errorType = "Переполнение стека"
                errorMessage = "Бесконечная рекурсия или слишком глубокий вызов функций.\nСтек переполнен."
            } catch (e: OutOfMemoryError) {
                errorType = "Нехватка памяти"
                errorMessage = "Скрипт потребляет слишком много памяти.\nПопробуйте уменьшить размер данных или размер массивов."
            } catch (e: SecurityException) {
                errorType = "Ошибка безопасности"
                errorMessage = getSafeStackTrace(e, 200) // 🔥 УВЕЛИЧЕН ЛИМИТ СТРОК
            } catch (e: Throwable) {
                // Игнорируем CancellationException, если корутина была отменена пользователем
                if (e is CancellationException) throw e
                errorType = "Системная ошибка"
                errorMessage = getSafeStackTrace(e, 200) // 🔥 УВЕЛИЧЕН ЛИМИТ СТРОК
            }

            withContext(Dispatchers.Main) {
                try {
                    if (errorMessage != null) {
                        binding.tvOutput.text = "$errorType:\n$errorMessage"
                    } else {
                        binding.tvOutput.text = if (output.isEmpty()) {
                            "Код выполнен успешно (нет вывода)"
                        } else {
                            output.toString()
                        }
                    }
                } catch (e: Exception) {
                    binding.tvOutput.text = "Ошибка выполнения скрипта"
                }
            }
        }
    }

    private fun getSafeStackTrace(e: Throwable, maxLines: Int): String {
        return try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            e.printStackTrace(pw)
            val fullTrace = sw.toString()
            
            val lines = fullTrace.lines()
            if (lines.size > maxLines) {
                lines.take(maxLines).joinToString("\n") + "\n... (обрезано)"
            } else {
                fullTrace
            }
        } catch (e: Exception) {
            "Не удалось получить stack trace: ${e.message}"
        } catch (e: StackOverflowError) {
            "Стек переполнен при получении stack trace"
        }
    }

    private fun addHelperFunctions(globals: Globals, output: StringBuilder) {
        globals.set("input", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = LuaValue.valueOf(0.5)
        })

        globals.set("square", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val num = arg.todouble()
                return LuaValue.valueOf(num * num)
            }
        })

        globals.set("sqrt", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val num = arg.todouble()
                return LuaValue.valueOf(kotlin.math.sqrt(num))
            }
        })

        globals.set("pow", object : TwoArgFunction() {
            override fun call(base: LuaValue, exp: LuaValue): LuaValue {
                return LuaValue.valueOf(Math.pow(base.todouble(), exp.todouble()))
            }
        })

        globals.set("result", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                output.append("=== РЕЗУЛЬТАТ: ${arg.tojstring()} ===\n")
                return LuaValue.NONE
            }
        })

        globals.set("format_num", object : TwoArgFunction() {
            override fun call(num: LuaValue, decimals: LuaValue): LuaValue {
                return try {
                    val value = num.todouble()
                    val dec = decimals.toint()
                    val format = "%.${dec}f"
                    LuaValue.valueOf(String.format(format, value))
                } catch (e: Exception) {
                    LuaValue.valueOf("Ошибка форматирования: ${e.message}")
                }
            }
        })

        globals.set("round", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                return LuaValue.valueOf(Math.round(arg.todouble()).toDouble())
            }
        })

        // ✅ ИСПРАВЛЕНИЕ: Заменяем сломанную функцию 'check' на математически корректную 'clamp'
        // Она ограничивает значение диапазоном [min, max], не затирая исходные данные, если они в норме.
        globals.set("clamp", object : ThreeArgFunction() {
            override fun call(value: LuaValue, min: LuaValue, max: LuaValue): LuaValue {
                val v = value.todouble()
                val mn = min.todouble()
                val mx = max.todouble()
                return LuaValue.valueOf(Math.max(mn, Math.min(mx, v)))
            }
        })

        globals.set("log", object : TwoArgFunction() {
            override fun call(base: LuaValue, num: LuaValue): LuaValue {
                val b = base.todouble()
                val n = num.todouble()
                if (b <= 0 || b == 1.0 || n <= 0) return LuaValue.valueOf(Double.NaN)
                return LuaValue.valueOf(Math.log(n) / Math.log(b))
            }
        })
    }

    private fun loadExample() {
        val examples = listOf(
            """
                -- Простой пример
                print("Привет, мир!")
                print("2 + 2 = " .. (2 + 2))
            """.trimIndent(),

            """
                -- Калькулятор целесообразности покупки
                local name = "Ноутбук"
                local support, flow, upgrade = 1.0, 0.0, 1.0
                local brand, official, price, longevity = 0.9, 0.9, 0.7, 0.8
                local risk = 0.1
                
                local utility = (support + flow + upgrade) / 3
                local reliability = (brand + official + price + longevity) / 4
                local risk_factor = 1 - (risk * risk)
                local purchase_score = ((utility * 0.3) + (reliability * 0.7)) * risk_factor
                
                print("=== " .. name .. " ===")
                print("Целесообразность: " .. format_num(purchase_score * 100, 1) .. "%")
            """.trimIndent(),

            """
                -- Синтаксическая ошибка для теста
                local x = 
                print(x)
            """.trimIndent(),

            """
                -- Пример работы с массивом (теперь не будет ложно блокироваться)
                local arr = {10, 20, 30, 40, 50}
                local sum = 0
                local i = 1
                while true do
                    if i > #arr then break end
                    sum = sum + arr[i]
                    i = i + 1
                end
                print("Сумма элементов массива: " .. sum)
            """.trimIndent(),

            """
                -- Математические операции
                local x = 16
                print("Квадрат: " .. square(x))
                print("Корень: " .. sqrt(x))
            """.trimIndent()
        )

        val currentDisplayIndex = exampleIndex + 1
        binding.etCode.setText(examples[exampleIndex])
        exampleIndex = (exampleIndex + 1) % examples.size
        Toast.makeText(this, "Пример $currentDisplayIndex/${examples.size}", Toast.LENGTH_SHORT).show()
    }
}
