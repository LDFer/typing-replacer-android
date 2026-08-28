package com.example.typingreplacer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {

    private lateinit var repository: ReplacementRepository
    private lateinit var appSettings: AppSettings
    private lateinit var ruleContainer: LinearLayout
    private lateinit var serviceStatus: TextView
    private lateinit var serviceHint: TextView
    private lateinit var diagnostics: TextView
    private lateinit var diagnosticSummaryView: TextView
    private lateinit var diagnosticLogView: TextView
    private lateinit var diagnosticPanel: LinearLayout
    private lateinit var compatibilityScanCheck: Switch
    private lateinit var lockReplacementCheck: Switch

    private var pendingDiagnosticExport = ""

    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateServiceStatus()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ReplacementRepository(this)
        appSettings = AppSettings(this)
        window.statusBarColor = color(R.color.app_background)
        window.navigationBarColor = color(R.color.surface)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContentView(buildContentView())
        renderRules()
    }

    override fun onResume() {
        super.onResume()
        uiHandler.removeCallbacks(refreshRunnable)
        uiHandler.post(refreshRunnable)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_DIAGNOSTIC || resultCode != RESULT_OK) return

        val uri = data?.data ?: return
        val report = pendingDiagnosticExport
        pendingDiagnosticExport = ""
        if (report.isBlank()) {
            toast("没有可导出的诊断报告")
            return
        }

        try {
            contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8).use { writer ->
                requireNotNull(writer) { "无法打开导出文件" }
                writer.write(report)
            }
            toast("诊断报告 TXT 已导出")
        } catch (t: Throwable) {
            Toast.makeText(this, "导出失败：${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildContentView(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(color(R.color.app_background))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }
        scroll.addView(root)

        addHeader(root)
        addStatusCard(root)
        addSettingsCard(root)
        addTestCard(root)
        addRulesCard(root)
        addDiagnosticPanel(root)
        addFooter(root)

        return scroll
    }

    private fun addHeader(root: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(18))
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_app_icon)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(54), dp(54)))

        val textWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        textWrap.addView(TextView(this).apply {
            text = "TextFlow"
            textSize = 27f
            setTextColor(color(R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
        })
        textWrap.addView(TextView(this).apply {
            text = "全局智能文字替换"
            textSize = 14f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(textWrap, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
    }

    private fun addStatusCard(root: LinearLayout) {
        val card = card()
        card.addView(label("运行状态"))

        serviceStatus = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        }
        card.addView(serviceStatus)

        serviceHint = TextView(this).apply {
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            setLineSpacing(0f, 1.15f)
            setPadding(0, 0, 0, dp(14))
        }
        card.addView(serviceHint)

        card.addView(primaryButton("管理无障碍服务") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        addCard(root, card)
    }

    private fun addSettingsCard(root: LinearLayout) {
        val card = card()
        card.addView(sectionTitle("功能设置"))
        card.addView(sectionDescription("保持核心功能简洁，兼容选项可按设备情况调整。"))

        compatibilityScanCheck = Switch(this).apply {
            isChecked = appSettings.compatibilityScanEnabled
            setOnCheckedChangeListener { _, checked -> appSettings.compatibilityScanEnabled = checked }
        }
        card.addView(settingRow(
            title = "兼容扫描",
            description = "事件异常时自动检查当前输入框，提高不同 App 的兼容性",
            toggle = compatibilityScanCheck,
        ))

        card.addView(divider())

        lockReplacementCheck = Switch(this).apply {
            isChecked = appSettings.lockReplacementEnabled
            setOnCheckedChangeListener { _, checked -> appSettings.lockReplacementEnabled = checked }
        }
        card.addView(settingRow(
            title = "锁定替换结果",
            description = "替换完成后禁止手动删除；发送消息后自动解锁",
            toggle = lockReplacementCheck,
        ))

        card.addView(divider())
        card.addView(textButton("后台兼容设置") { openBatteryCompatibilitySettings() })
        addCard(root, card)
    }

    private fun addTestCard(root: LinearLayout) {
        val card = card()
        card.addView(sectionTitle("快速测试"))
        card.addView(sectionDescription("在这里验证规则本身，不依赖其他 App。"))

        val testInput = EditText(this).apply {
            hint = "例如：我今天很开心"
            textSize = 15f
            minLines = 2
            maxLines = 4
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(color(R.color.app_background), 14, color(R.color.divider), 1)
        }
        card.addView(testInput, fullWidthParams(top = 8, bottom = 10))

        card.addView(secondaryButton("执行替换测试") {
            val original = testInput.text.toString()
            testInput.setText(TextReplacer.replace(original, repository.loadRules()))
            testInput.setSelection(testInput.text.length)
        })
        addCard(root, card)
    }

    private fun addRulesCard(root: LinearLayout) {
        val card = card()
        card.addView(sectionTitle("替换规则"))
        card.addView(sectionDescription("优先匹配更长的规则，替换结果不会在同一轮被再次处理。"))

        ruleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        card.addView(ruleContainer, fullWidthParams(top = 8, bottom = 8))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val add = secondaryButton("＋ 添加规则") { addRuleRow("", "", true) }
        val save = primaryButton("保存更改") {
            saveRulesFromUi()
            toast("规则已保存")
        }
        actions.addView(add, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        actions.addView(save, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        card.addView(actions, fullWidthParams(top = 8))
        addCard(root, card)
    }

    private fun addDiagnosticPanel(root: LinearLayout) {
        diagnosticPanel = card().apply {
            visibility = View.GONE
            background = rounded(Color.rgb(249, 249, 252), 20, color(R.color.divider), 1)
        }
        diagnosticPanel.addView(sectionTitle("高级诊断"))
        diagnosticPanel.addView(sectionDescription("仅排查问题时使用。诊断不会记录聊天正文。"))

        diagnostics = TextView(this).apply {
            textSize = 12f
            setTextColor(color(R.color.text_secondary))
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(10))
        }
        diagnosticPanel.addView(diagnostics)

        diagnosticPanel.addView(secondaryButton("开始新的诊断会话") {
            DiagnosticLog.startSession()
            updateServiceStatus()
            toast("诊断会话已开始")
        })
        diagnosticPanel.addView(secondaryButton("结束并复制完整报告") {
            DiagnosticLog.stopSession()
            copyDiagnosticReport()
            updateServiceStatus()
        }, fullWidthParams(top = 8))
        diagnosticPanel.addView(secondaryButton("导出诊断报告 TXT") {
            exportDiagnosticReport()
        }, fullWidthParams(top = 8))
        diagnosticPanel.addView(textButton("清空诊断数据") {
            DiagnosticLog.clear()
            updateServiceStatus()
            toast("诊断数据已清空")
        })

        diagnosticSummaryView = TextView(this).apply {
            textSize = 11f
            setTextColor(color(R.color.text_secondary))
            setTextIsSelectable(true)
            setPadding(0, dp(10), 0, dp(8))
        }
        diagnosticPanel.addView(diagnosticSummaryView)

        diagnosticLogView = TextView(this).apply {
            textSize = 9f
            setTextColor(Color.rgb(90, 94, 106))
            setTextIsSelectable(true)
            setPadding(0, dp(4), 0, 0)
        }
        diagnosticPanel.addView(diagnosticLogView)
        addCard(root, diagnosticPanel)
    }

    private fun addFooter(root: LinearLayout) {
        val footer = TextView(this).apply {
            text = "TextFlow · 2.0"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(150, 154, 166))
            setPadding(0, dp(8), 0, dp(4))
            setOnLongClickListener {
                diagnosticPanel.visibility = if (diagnosticPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                toast(if (diagnosticPanel.visibility == View.VISIBLE) "高级诊断已显示" else "高级诊断已隐藏")
                true
            }
        }
        root.addView(footer, fullWidthParams(top = 2))
    }

    private fun buildDiagnosticReport(): String {
        val rules = repository.loadRules()
        return DiagnosticMetrics.buildReport(
            context = this,
            ruleCount = rules.count { it.enabled && it.source.isNotEmpty() },
            compatibilityScan = appSettings.compatibilityScanEnabled,
            lockReplacement = appSettings.lockReplacementEnabled,
            verboseTrace = DiagnosticLog.isVerbose(),
            trace = DiagnosticLog.snapshot(),
        )
    }

    private fun copyDiagnosticReport() {
        val report = buildDiagnosticReport()
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("textflow-diagnostic-report", report))
        toast("完整诊断报告已复制")
    }

    private fun exportDiagnosticReport() {
        pendingDiagnosticExport = buildDiagnosticReport()
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "textflow-diagnostic-$timestamp.txt")
        }
        try {
            startActivityForResult(intent, REQUEST_EXPORT_DIAGNOSTIC)
        } catch (t: Throwable) {
            pendingDiagnosticExport = ""
            Toast.makeText(this, "无法打开文件保存界面：${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openBatteryCompatibilitySettings() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun updateServiceStatus() {
        val systemEnabled = isReplacementServiceEnabled()
        val snapshot = ServiceRuntimeState.snapshot()
        val heartbeatAge = ageMs(snapshot.lastHeartbeatAt)

        when {
            !systemEnabled -> {
                serviceStatus.text = "需要开启服务"
                serviceStatus.setTextColor(color(R.color.status_warning))
                serviceHint.text = "TextFlow 需要无障碍权限才能在其他 App 的输入框中执行替换。"
            }
            snapshot.connected && heartbeatAge in 0..3000L -> {
                serviceStatus.text = "运行正常"
                serviceStatus.setTextColor(color(R.color.status_success))
                serviceHint.text = "服务已连接，规则会在支持的输入框中自动生效。"
            }
            else -> {
                serviceStatus.text = "服务连接异常"
                serviceStatus.setTextColor(color(R.color.status_warning))
                serviceHint.text = "系统开关已开启，但后台心跳异常。可尝试重新开启无障碍服务。"
            }
        }

        diagnostics.text = buildString {
            appendLine("服务连接：${if (snapshot.connected) "是" else "否"}")
            appendLine("最近心跳：${ageText(snapshot.lastHeartbeatAt)}")
            appendLine("最近系统事件：${ageText(snapshot.lastEventAt)}${packageSuffix(snapshot.lastEventPackage)}")
            appendLine("输入框状态：${snapshot.lastNodeStatus} · ${ageText(snapshot.lastNodeAt)}")
            appendLine("最近替换：${ageText(snapshot.lastReplacementAt)}${packageSuffix(snapshot.lastReplacementPackage)}")
            if (snapshot.lastError.isNotEmpty()) append("最近错误：${snapshot.lastError}")
        }.trim()

        diagnosticSummaryView.text = buildString {
            appendLine("详细诊断：${if (DiagnosticLog.isVerbose()) "进行中" else "关闭"}")
            append(DiagnosticMetrics.summary())
        }
        val trace = DiagnosticLog.snapshot(40)
        diagnosticLogView.text = if (trace.isBlank()) "暂无关键 Trace" else trace
    }

    private fun renderRules() {
        ruleContainer.removeAllViews()
        val rules = repository.loadRules()
        if (rules.isEmpty()) {
            ruleContainer.addView(TextView(this).apply {
                text = "还没有规则，点击下方“添加规则”开始。"
                textSize = 13f
                setTextColor(color(R.color.text_secondary))
                setPadding(dp(2), dp(12), dp(2), dp(12))
            })
        } else {
            rules.forEach { addRuleRow(it.source, it.replacement, it.enabled) }
        }
    }

    private fun addRuleRow(source: String, replacement: String, enabled: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = rounded(color(R.color.app_background), 14, color(R.color.divider), 1)
        }

        val check = CheckBox(this).apply {
            isChecked = enabled
            buttonTintList = android.content.res.ColorStateList.valueOf(color(R.color.brand_primary))
        }
        row.addView(check, LinearLayout.LayoutParams(dp(42), dp(48)))

        val sourceEdit = EditText(this).apply {
            hint = "原词"
            setText(source)
            textSize = 14f
            singleLine = true
            setPadding(dp(8), 0, dp(8), 0)
        }
        row.addView(sourceEdit, LinearLayout.LayoutParams(0, dp(48), 1f))

        row.addView(TextView(this).apply {
            text = "→"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.brand_primary))
        }, LinearLayout.LayoutParams(dp(30), dp(48)))

        val replacementEdit = EditText(this).apply {
            hint = "替换为"
            setText(replacement)
            textSize = 14f
            singleLine = true
            setPadding(dp(8), 0, dp(8), 0)
        }
        row.addView(replacementEdit, LinearLayout.LayoutParams(0, dp(48), 1f))

        row.addView(Button(this).apply {
            text = "×"
            textSize = 18f
            isAllCaps = false
            setTextColor(color(R.color.danger))
            background = rounded(Color.TRANSPARENT, 12)
            setOnClickListener { ruleContainer.removeView(row) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))

        ruleContainer.addView(row, fullWidthParams(bottom = 8))
    }

    private fun saveRulesFromUi() {
        val rules = mutableListOf<ReplacementRule>()
        for (i in 0 until ruleContainer.childCount) {
            val row = ruleContainer.getChildAt(i) as? LinearLayout ?: continue
            if (row.childCount < 5) continue
            val enabled = (row.getChildAt(0) as? CheckBox)?.isChecked ?: true
            val source = (row.getChildAt(1) as? EditText)?.text?.toString()?.trim().orEmpty()
            val replacement = (row.getChildAt(3) as? EditText)?.text?.toString()?.trim().orEmpty()
            if (source.isNotEmpty()) rules.add(ReplacementRule(source, replacement, enabled))
        }
        repository.saveRules(rules)
    }

    private fun isReplacementServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val fullName = "$packageName/${GlobalReplaceService::class.java.name}"
        val shortName = "$packageName/.GlobalReplaceService"
        return enabledServices.split(':').any {
            it.equals(fullName, ignoreCase = true) || it.equals(shortName, ignoreCase = true)
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(color(R.color.surface), 20, color(R.color.divider), 1)
        elevation = dp(1).toFloat()
    }

    private fun addCard(root: LinearLayout, card: View) {
        root.addView(card, fullWidthParams(bottom = 14))
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.text_primary))
    }

    private fun sectionDescription(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(color(R.color.text_secondary))
        setPadding(0, dp(5), 0, dp(8))
        setLineSpacing(0f, 1.15f)
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(color(R.color.text_secondary))
    }

    private fun settingRow(title: String, description: String, toggle: Switch): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(this).apply {
            text = title
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text_primary))
        })
        copy.addView(TextView(this).apply {
            text = description
            textSize = 12f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(3), dp(10), 0)
        })
        row.addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(toggle)
        return row
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(color(R.color.divider))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun primaryButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 14f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        background = rounded(color(R.color.brand_primary), 14)
        setOnClickListener { onClick() }
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(16), 0, dp(16), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
    }

    private fun secondaryButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 14f
        isAllCaps = false
        setTextColor(color(R.color.brand_primary))
        background = rounded(color(R.color.brand_soft), 14)
        setOnClickListener { onClick() }
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(16), 0, dp(16), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
    }

    private fun textButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 13f
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextColor(color(R.color.brand_primary))
        background = rounded(Color.TRANSPARENT, 10)
        setOnClickListener { onClick() }
        minHeight = 0
        minimumHeight = 0
        setPadding(0, dp(6), 0, 0)
    }

    private fun rounded(fillColor: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null && strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun fullWidthParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(top), 0, dp(bottom))
        }

    private fun color(id: Int): Int = resources.getColor(id, theme)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun packageSuffix(packageName: String): String = if (packageName.isBlank()) "" else " · $packageName"
    private fun ageMs(timestamp: Long): Long = if (timestamp <= 0L) Long.MAX_VALUE else max(0L, System.currentTimeMillis() - timestamp)

    private fun ageText(timestamp: Long): String {
        if (timestamp <= 0L) return "无"
        val delta = max(0L, System.currentTimeMillis() - timestamp)
        return when {
            delta < 1000L -> "${delta}ms 前"
            delta < 60_000L -> "${delta / 1000L}s 前"
            else -> "${delta / 60_000L}min 前"
        }
    }

    private companion object {
        const val REQUEST_EXPORT_DIAGNOSTIC = 4201
    }
}
