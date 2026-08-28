package com.example.typingreplacer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max

class MainActivity : Activity() {

    private lateinit var repository: ReplacementRepository
    private lateinit var appSettings: AppSettings

    private lateinit var ruleContainer: LinearLayout
    private lateinit var serviceStatus: TextView
    private lateinit var diagnostics: TextView
    private lateinit var compatibilityScanCheck: CheckBox
    private lateinit var lockReplacementCheck: CheckBox

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

    private fun buildContentView(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "打字替换 V2"
            textSize = 24f
        })

        root.addView(TextView(this).apply {
            text =
                "核心服务由 Android 无障碍框架绑定。优先处理文本变化事件，" +
                    "并用焦点扫描兼容后台；微信写入失败时会自动尝试兼容粘贴。"
            textSize = 14f
            setPadding(0, 8, 0, 16)
        })

        serviceStatus = TextView(this).apply { textSize = 16f }
        root.addView(serviceStatus)

        diagnostics = TextView(this).apply {
            textSize = 13f
            setPadding(0, 8, 0, 16)
        }
        root.addView(diagnostics)

        root.addView(Button(this).apply {
            text = "开启 / 管理无障碍服务"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        root.addView(Button(this).apply {
            text = "后台兼容设置（可选）"
            setOnClickListener { openBatteryCompatibilitySettings() }
        })

        compatibilityScanCheck = CheckBox(this).apply {
            text = "兼容扫描：事件停止时每 0.7 秒检查当前焦点输入框"
            isChecked = appSettings.compatibilityScanEnabled
            setOnCheckedChangeListener { _, checked ->
                appSettings.compatibilityScanEnabled = checked
            }
        }
        root.addView(compatibilityScanCheck)

        lockReplacementCheck = CheckBox(this).apply {
            text = "锁定替换：替换后禁止删除，删除会自动补回（发送后会自动解锁）"
            isChecked = appSettings.lockReplacementEnabled
            setOnCheckedChangeListener { _, checked ->
                appSettings.lockReplacementEnabled = checked
            }
        }
        root.addView(lockReplacementCheck)

        root.addView(TextView(this).apply {
            text = "微信提示：如果 ACTION_SET_TEXT 被拒绝，V2 会自动全选输入框并走剪贴板粘贴回退。"
            textSize = 13f
            setPadding(0, 6, 0, 10)
        })

        root.addView(TextView(this).apply {
            text = "测试区"
            textSize = 18f
            setPadding(0, 28, 0, 8)
        })

        val testInput = EditText(this).apply {
            hint = "例如输入：我 今天很开心"
            minLines = 2
        }
        root.addView(testInput)

        root.addView(Button(this).apply {
            text = "执行替换测试"
            setOnClickListener {
                val original = testInput.text.toString()
                testInput.setText(TextReplacer.replace(original, repository.loadRules()))
                testInput.setSelection(testInput.text.length)
            }
        })

        root.addView(TextView(this).apply {
            text = "替换规则"
            textSize = 18f
            setPadding(0, 28, 0, 8)
        })

        root.addView(TextView(this).apply {
            text = "规则采用单次最长匹配，替换结果不会在同一轮再次触发其他规则。"
            textSize = 13f
        })

        ruleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(ruleContainer)

        root.addView(Button(this).apply {
            text = "添加规则"
            setOnClickListener { addRuleRow("", "", true) }
        })

        root.addView(Button(this).apply {
            text = "保存规则"
            setOnClickListener {
                saveRulesFromUi()
                Toast.makeText(this@MainActivity, "规则已保存", Toast.LENGTH_SHORT).show()
            }
        })

        return scroll
    }

    private fun openBatteryCompatibilitySettings() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                )
            )
            return
        }

        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        } catch (_: Exception) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                )
            )
        }
    }

    private fun updateServiceStatus() {
        val systemEnabled = isReplacementServiceEnabled()
        val snapshot = ServiceRuntimeState.snapshot()
        val heartbeatAge = ageMs(snapshot.lastHeartbeatAt)

        when {
            !systemEnabled -> {
                serviceStatus.text = "状态：无障碍服务未开启"
                serviceStatus.setTextColor(Color.RED)
            }
            snapshot.connected && heartbeatAge in 0..3000L -> {
                serviceStatus.text = "状态：无障碍服务已连接，后台心跳正常"
                serviceStatus.setTextColor(Color.rgb(0, 128, 0))
            }
            else -> {
                serviceStatus.text =
                    "状态：系统开关已开启，但服务心跳异常；可能是服务未重新绑定或 ROM 冻结"
                serviceStatus.setTextColor(Color.rgb(220, 120, 0))
            }
        }

        diagnostics.text = buildString {
            appendLine("服务连接：${if (snapshot.connected) "是" else "否"}")
            appendLine("最近心跳：${ageText(snapshot.lastHeartbeatAt)}")
            appendLine(
                "最近系统事件：${ageText(snapshot.lastEventAt)}" +
                    packageSuffix(snapshot.lastEventPackage)
            )
            appendLine("输入框状态：${snapshot.lastNodeStatus} · ${ageText(snapshot.lastNodeAt)}")
            appendLine(
                "最近替换：${ageText(snapshot.lastReplacementAt)}" +
                    packageSuffix(snapshot.lastReplacementPackage)
            )
            if (snapshot.lastError.isNotEmpty()) append("最近错误：${snapshot.lastError}")
        }.trim()
    }

    private fun packageSuffix(packageName: String): String =
        if (packageName.isBlank()) "" else " · $packageName"

    private fun ageMs(timestamp: Long): Long =
        if (timestamp <= 0L) Long.MAX_VALUE
        else max(0L, System.currentTimeMillis() - timestamp)

    private fun ageText(timestamp: Long): String {
        if (timestamp <= 0L) return "无"
        val delta = max(0L, System.currentTimeMillis() - timestamp)
        return when {
            delta < 1000L -> "${delta}ms 前"
            delta < 60_000L -> "${delta / 1000L}s 前"
            else -> "${delta / 60_000L}min 前"
        }
    }

    private fun renderRules() {
        ruleContainer.removeAllViews()
        val rules = repository.loadRules()
        if (rules.isEmpty()) {
            ruleContainer.addView(TextView(this).apply { text = "暂无规则" })
        } else {
            rules.forEach { addRuleRow(it.source, it.replacement, it.enabled) }
        }
    }

    private fun addRuleRow(source: String, replacement: String, enabled: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }

        val check = CheckBox(this).apply { isChecked = enabled }
        row.addView(check)

        val sourceEdit = EditText(this).apply {
            hint = "原词"
            setText(source)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        row.addView(sourceEdit)

        row.addView(TextView(this).apply {
            text = " → "
            textSize = 18f
        })

        val replacementEdit = EditText(this).apply {
            hint = "替换为"
            setText(replacement)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        row.addView(replacementEdit)

        row.addView(Button(this).apply {
            text = "删"
            setOnClickListener { ruleContainer.removeView(row) }
        })

        ruleContainer.addView(row)
    }

    private fun saveRulesFromUi() {
        val rules = mutableListOf<ReplacementRule>()
        for (i in 0 until ruleContainer.childCount) {
            val row = ruleContainer.getChildAt(i) as? LinearLayout ?: continue
            if (row.childCount < 5) continue
            val enabled = (row.getChildAt(0) as? CheckBox)?.isChecked ?: true
            val source = (row.getChildAt(1) as? EditText)?.text?.toString()?.trim().orEmpty()
            val replacement = (row.getChildAt(3) as? EditText)?.text?.toString()?.trim().orEmpty()
            if (source.isNotEmpty()) {
                rules.add(ReplacementRule(source, replacement, enabled))
            }
        }
        repository.saveRules(rules)
    }

    private fun isReplacementServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val fullName = "$packageName/${GlobalReplaceService::class.java.name}"
        val shortName = "$packageName/.GlobalReplaceService"
        return enabledServices.split(':').any {
            it.equals(fullName, ignoreCase = true) ||
                it.equals(shortName, ignoreCase = true)
        }
    }
}
