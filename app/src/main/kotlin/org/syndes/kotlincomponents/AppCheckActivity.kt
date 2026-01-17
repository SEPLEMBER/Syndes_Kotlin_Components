package org.syndes.kotlincomponents

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashMap

/**
 * AppCheckActivity — упрощённая версия:
 * - убран центрированный крутящийся текст
 * - убрана проверка privileged
 * - добавлен разбор субъекта сертификата (CN, OU, O, L, ST, C и т.д.)
 * - пометка "non-standard" когда отсутствуют ожидаемые поля
 *
 * Поместите этот файл в пакет org.syndes.kotlincomponents (как указано).
 */

class AppCheckActivity : AppCompatActivity() {

    private lateinit var editPrefixes: EditText
    private lateinit var btnScan: Button
    private lateinit var btnPermissions: Button
    private lateinit var btnSignatures: Button
    private lateinit var btnExport: Button
    private lateinit var switchOnlySystem: Switch
    private lateinit var switchExcludePrefixes: Switch
    private lateinit var tvStatus: TextView
    private lateinit var listResults: ListView

    // Информация о сертификате, извлечённая нами:
    data class CertInfo(
        val fingerprintSha256: String,
        val attrs: Map<String, String>,
        val missing: List<String>
    )

    // Model in memory
    data class AppEntry(
        val packageName: String,
        val label: String?,
        val isSystem: Boolean,
        val sourceDir: String?,
        val installTime: Long?,
        val requestedPermissions: List<String>,
        val grantedPermissions: Set<String>,
        val signatures: List<CertInfo>
    )

    private val resultsList = ArrayList<String>() // строки для ListView (label + package)
    private val resultsMap = LinkedHashMap<String, AppEntry>() // packageName -> AppEntry
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_app_check)

        editPrefixes = findViewById(R.id.edit_prefixes)
        btnScan = findViewById(R.id.btn_scan)
        btnPermissions = findViewById(R.id.btn_permissions)
        btnSignatures = findViewById(R.id.btn_signatures)
        btnExport = findViewById(R.id.btn_export)
        switchOnlySystem = findViewById(R.id.switch_only_system)
        switchExcludePrefixes = findViewById(R.id.switch_exclude_prefixes)
        tvStatus = findViewById(R.id.tv_status)
        listResults = findViewById(R.id.list_results)

        // Adapter: display label on first line and package name on second (newline in string)
        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, resultsList) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(0xFFDDFFEE.toInt())
                v.typeface = android.graphics.Typeface.MONOSPACE
                v.textSize = 14f
                v.isSingleLine = false
                return v
            }
        }
        listResults.adapter = adapter

        // Item click -> show details
        listResults.setOnItemClickListener { _, _, pos, _ ->
            val s = resultsList[pos]
            val pkg = s.substringAfter("\n").trim()
            val entry = resultsMap[pkg]
            entry?.let { showAppDetailsDialog(it) }
        }

        // Button handlers
        btnScan.setOnClickListener { startScanAndShow() }
        btnPermissions.setOnClickListener { analyzePermissions() }
        btnSignatures.setOnClickListener { analyzeSignatures() }
        btnExport.setOnClickListener { exportReportToClipboard() }

        tvStatus.text = "Ready"
    }

    private fun setStatus(text: String) {
        runOnUiThread { tvStatus.text = text }
    }

    private fun startScanAndShow() {
        resultsList.clear()
        resultsMap.clear()
        adapter.notifyDataSetChanged()
        setStatus("Scanning packages...")

        val prefixes = parsePrefixes(editPrefixes.text.toString())
        val onlySystem = switchOnlySystem.isChecked
        val excludePrefixes = switchExcludePrefixes.isChecked

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pm = packageManager
                val flags = buildFlags()
                val installed: List<PackageInfo> = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getInstalledPackages(android.content.pm.PackageManager.PackageInfoFlags.of(flags.toLong()))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getInstalledPackages(flags)
                    }
                } catch (e: Throwable) {
                    @Suppress("DEPRECATION")
                    pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                }

                setStatus("Found ${installed.size} packages — filtering...")

                var processed = 0
                for (pkg in installed) {
                    if (!isActive) break
                    processed++
                    val ai = pkg.applicationInfo
                    val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                    // prefix filtering
                    if (onlySystem && !isSys) continue
                    if (excludePrefixes && prefixes.isNotEmpty()) {
                        var matches = false
                        for (p in prefixes) {
                            if (pkg.packageName.startsWith(p, ignoreCase = true)) {
                                matches = true
                                break
                            }
                        }
                        if (matches) continue // exclude matching prefixes
                    }

                    // permissions
                    val requested = pkg.requestedPermissions?.toList() ?: emptyList()
                    val granted = HashSet<String>()
                    if (requested.isNotEmpty()) {
                        for (perm in requested) {
                            try {
                                val res = packageManager.checkPermission(perm, pkg.packageName)
                                if (res == PackageManager.PERMISSION_GRANTED) granted.add(perm)
                            } catch (_: Throwable) { /* ignore */ }
                        }
                    }

                    // signatures -> compute sha256 fingerprints + parse subject attributes
                    val certInfos = ArrayList<CertInfo>()
                    try {
                        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val signing = pkg.signingInfo
                            signing?.apkContentsSigners ?: pkg.signatures
                        } else {
                            @Suppress("DEPRECATION")
                            pkg.signatures
                        }
                        if (signatures != null) {
                            val cf = CertificateFactory.getInstance("X.509")
                            val md = MessageDigest.getInstance("SHA-256")
                            for (sig in signatures) {
                                try {
                                    val cert = cf.generateCertificate(ByteArrayInputStream(sig.toByteArray())) as X509Certificate
                                    val digest = md.digest(cert.encoded)
                                    val fp = bytesToHex(digest)
                                    val attrs = parseSubjectAttributes(cert)
                                    val required = listOf("CN", "OU", "O", "L", "ST")
                                    val missing = required.filter { !attrs.containsKey(it) }
                                    certInfos.add(CertInfo(fp, attrs, missing))
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    } catch (_: Throwable) {
                    }

                    val label = try {
                        val l = packageManager.getApplicationLabel(ai)
                        l?.toString()
                    } catch (e: Throwable) {
                        null
                    }
                    val app = AppEntry(
                        packageName = pkg.packageName,
                        label = label,
                        isSystem = isSys,
                        sourceDir = ai.sourceDir,
                        installTime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) pkg.firstInstallTime else null,
                        requestedPermissions = requested,
                        grantedPermissions = granted,
                        signatures = certInfos
                    )

                    // add to UI incrementally
                    withContext(Dispatchers.Main) {
                        val display = "${label ?: pkg.packageName}\n${pkg.packageName}"
                        resultsList.add(display)
                        resultsMap[pkg.packageName] = app
                        adapter.notifyDataSetChanged()
                        setStatus("Scanned $processed / ${installed.size}")
                    }
                }

                setStatus("Scan complete: ${resultsList.size} items shown")
            } catch (t: Throwable) {
                setStatus("Scan error: ${t.localizedMessage}")
            }
        }
    }

    private fun analyzePermissions() {
        if (resultsMap.isEmpty()) {
            Toast.makeText(this, "No results to analyze. Run Scan first.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val dangerCounts = ArrayList<Pair<String, Int>>() // pkg -> dangerousCount
            for ((pkg, entry) in resultsMap) {
                var dangerous = 0
                for (perm in entry.requestedPermissions) {
                    try {
                        val pi = packageManager.getPermissionInfo(perm, 0)
                        val level = pi.protectionLevel
                        val base = level and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE
                        if (base == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS) dangerous++
                    } catch (_: Throwable) { /* ignore unknown permission */ }
                }
                dangerCounts.add(pkg to dangerous)
            }
            dangerCounts.sortByDescending { it.second }
            val top = dangerCounts.take(8)
            val sb = StringBuilder()
            sb.append("Top apps by requested DANGEROUS permissions:\n\n")
            for ((pkg, cnt) in top) {
                val label = resultsMap[pkg]?.label ?: pkg
                sb.append("${label} — $cnt\n")
            }
            withContext(Dispatchers.Main) {
                showTextDialog("Permissions analysis", sb.toString())
            }
        }
    }

    private fun analyzeSignatures() {
        if (resultsMap.isEmpty()) {
            Toast.makeText(this, "No results to analyze. Run Scan first.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.Default) {
            val map = HashMap<String, MutableList<String>>() // fingerprint -> list of pkgs
            val nonstandard = ArrayList<Pair<String, CertInfo>>() // pkg, certinfo with missing attrs
            for ((pkg, entry) in resultsMap) {
                val certs = entry.signatures
                if (certs.isEmpty()) {
                    map.computeIfAbsent("<no-signature>") { ArrayList() }.add(pkg)
                } else {
                    for (c in certs) {
                        map.computeIfAbsent(c.fingerprintSha256) { ArrayList() }.add(pkg)
                        if (c.missing.isNotEmpty()) {
                            nonstandard.add(pkg to c)
                        }
                    }
                }
            }
            val sb = StringBuilder()
            sb.append("Signature clusters (SHA-256):\n\n")
            val sorted = map.entries.sortedByDescending { it.value.size }
            for ((fingerprint, pkgs) in sorted) {
                sb.append("${fingerprint} — ${pkgs.size} packages\n")
                val sample = pkgs.take(6).map { resultsMap[it]?.label ?: it }
                sb.append("  -> ${sample.joinToString(", ")}\n\n")
            }

            if (nonstandard.isNotEmpty()) {
                sb.append("\nCertificates with missing standard fields (CN/OU/O/L/ST):\n\n")
                for ((pkg, cert) in nonstandard.take(20)) {
                    val label = resultsMap[pkg]?.label ?: pkg
                    sb.append("${label} (${pkg})\n  fingerprint: ${cert.fingerprintSha256}\n  missing: ${cert.missing.joinToString(", ")}\n\n")
                }
            }

            withContext(Dispatchers.Main) {
                showTextDialog("Signatures analysis", sb.toString())
            }
        }
    }

    private fun exportReportToClipboard() {
        if (resultsMap.isEmpty()) {
            Toast.makeText(this, "No results to export.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val sb = StringBuilder()
            sb.append("AppCheck report\n")
            sb.append("Generated: ${Date()}\n\n")
            for ((pkg, entry) in resultsMap) {
                sb.append("Label: ${entry.label ?: "<no-label>"}\n")
                sb.append("Package: ${entry.packageName}\n")
                sb.append("System: ${entry.isSystem}\n")
                sb.append("Source: ${entry.sourceDir ?: "?"}\n")
                if (entry.requestedPermissions.isNotEmpty()) {
                    sb.append("Requested permissions: ${entry.requestedPermissions.size}\n")
                }
                if (entry.signatures.isNotEmpty()) {
                    sb.append("Signatures:\n")
                    for (c in entry.signatures) {
                        sb.append("  - fingerprint: ${c.fingerprintSha256}\n")
                        if (c.attrs.isNotEmpty()) {
                            val pairs = c.attrs.map { "${it.key}=${it.value}" }
                            sb.append("    attrs: ${pairs.joinToString(", ")}\n")
                        }
                        if (c.missing.isNotEmpty()) {
                            sb.append("    missing: ${c.missing.joinToString(", ")}\n")
                        }
                    }
                }
                sb.append("\n")
            }
            val text = sb.toString()
            withContext(Dispatchers.Main) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("AppCheck report", text)
                cm.setPrimaryClip(clip)
                Toast.makeText(this@AppCheckActivity, "Report copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAppDetailsDialog(entry: AppEntry) {
        val sb = StringBuilder()
        sb.append("Label: ${entry.label ?: "<no-label>"}\n")
        sb.append("Package: ${entry.packageName}\n")
        sb.append("System: ${entry.isSystem}\n")
        sb.append("Source: ${entry.sourceDir ?: "?"}\n")
        if (!entry.requestedPermissions.isNullOrEmpty()) {
            sb.append("\nPermissions (${entry.requestedPermissions.size}):\n")
            for (p in entry.requestedPermissions) {
                val granted = if (entry.grantedPermissions.contains(p)) " (granted)" else ""
                sb.append("  • $p$granted\n")
            }
        } else {
            sb.append("\nPermissions: none\n")
        }

        if (!entry.signatures.isNullOrEmpty()) {
            sb.append("\nSignatures:\n")
            for (c in entry.signatures) {
                sb.append("  • fingerprint: ${c.fingerprintSha256}\n")
                if (c.attrs.isNotEmpty()) {
                    for ((k, v) in c.attrs) {
                        sb.append("      ${k} = ${v}\n")
                    }
                }
                if (c.missing.isNotEmpty()) {
                    sb.append("      MISSING: ${c.missing.joinToString(", ")}\n")
                }
                sb.append("\n")
            }
        } else {
            sb.append("\nSignatures: none\n")
        }

        val tv = TextView(this)
        tv.setTextIsSelectable(true)
        tv.movementMethod = ScrollingMovementMethod()
        tv.setPadding(18, 18, 18, 18)
        tv.text = sb.toString()
        tv.typeface = android.graphics.Typeface.MONOSPACE
        tv.setTextColor(0xFFDDFFEE.toInt())

        AlertDialog.Builder(this)
            .setTitle(entry.label ?: entry.packageName)
            .setView(tv)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy package") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("package", entry.packageName)
                cm.setPrimaryClip(clip)
                Toast.makeText(this, "Package copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showTextDialog(title: String, text: String) {
        val tv = TextView(this)
        tv.setTextIsSelectable(true)
        tv.movementMethod = ScrollingMovementMethod()
        tv.setPadding(18, 18, 18, 18)
        tv.text = text
        tv.typeface = android.graphics.Typeface.MONOSPACE
        tv.setTextColor(0xFFDDFFEE.toInt())

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(tv)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(title, text)
                cm.setPrimaryClip(clip)
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun parsePrefixes(raw: String): List<String> {
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02X", b))
        }
        return sb.toString()
    }

    private fun buildFlags(): Int {
        var flags = PackageManager.GET_PERMISSIONS
        flags = flags or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        return flags
    }

    /**
     * Разбор DN субъекта сертификата в карту атрибутов.
     * Примеры ключей: CN, OU, O, L, ST, C
     */
    private fun parseSubjectAttributes(cert: X509Certificate): Map<String, String> {
        val dn = cert.subjectX500Principal?.name ?: ""
        val map = LinkedHashMap<String, String>()
        // Простая разбивка по запятым => ключ=значение (ограничение: не обрабатываются вложенные запятые внутри escaped values)
        val parts = dn.split(",")
        for (p in parts) {
            val kv = p.trim().split("=", limit = 2)
            if (kv.size == 2) {
                val k = kv[0].trim()
                val v = kv[1].trim().trim('"')
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    map[k] = v
                }
            }
        }
        return map
    }
}
