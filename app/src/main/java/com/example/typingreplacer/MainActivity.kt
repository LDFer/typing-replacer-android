package com.example.typingreplacer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 主界面：编辑替换规则、测试替换、跳转系统无障碍设置。
 */
class MainActivity : Activity() {

    private lateinit var repository: ReplacementRepository
    private lateinit var appSettings: AppSettings
    private lateinit var ruleContainer: LinearLayout
    private lateinit var serviceStatus: TextView
    private lateinit var modeGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ReplacementRepository(this)
        appSettings = AppSettings(this)
        setContentView(buildContentView())
        renderRules()
        startKeepAlive()
    }

    private fun startKeepAlive() {
        startForegroundService(Intent(this, KeepAliveService::class.java))
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun buildContentView(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "打字替换（无障碍版）"
            textSize = 22f
        })

        root.addView(TextView(this).apply {
            text = "开启服务后，可选择“仅发送时替换”或“实时替换”。仅发送时替换平时不会改输入框，不怕删除后自动补全。"
            textSize = 14f
            setPadding(0, 8, 0, 8)
        })

        serviceStatus = TextView(this)
        root.addView(serviceStatus)

        root.addView(Button(this).apply {
            text = "开启 / 管理无障碍服务"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        root.addView(TextView(this).apply {
            text = "测试区"
            textSize = 18f
            setPadding(0, 32, 0, 8)
        })

        val testInput = EditText(this).apply {
            hint = "在这里输入：我 今天很开心"
            minLines = 2
        }
        root.addView(testInput)

        root.addView(Button(this).apply {
            text = "执行替换测试"
            setOnClickListener {
                val original = testInput.text.toString()
                val rules = repository.loadRules()
                testInput.setText(TextReplacer.replace(original, rules))
            }
        })

        root.addView(TextView(this).apply {
            text = "处理模式"
            textSize = 18f
            setPadding(0, 32, 0, 8)
        })

        val rbSend = RadioButton(this).apply {
            id = View.generateViewId()
            text = "仅发送时替换（推荐）"
        }
        val rbRealtime = RadioButton(this).apply {
            id = View.generateViewId()
            text = "实时替换（输入框会变）"
        }

        modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(rbSend)
            addView(rbRealtime)
        }

        if (appSettings.mode == AppSettings.MODE_SEND) {
            rbSend.isChecked = true
        } else {
            rbRealtime.isChecked = true
        }
        root.addView(modeGroup)

        val lockCheck = CheckBox(this).apply {
            text = "锁定替换（替换后不可删除/修改，删除会自动恢复）"
            isChecked = appSettings.lockReplacement
        }
        root.addView(lockCheck)

        root.addView(TextView(this).apply {
            text = "替换规则"
            textSize = 18f
            setPadding(0, 32, 0, 8)
        })

        ruleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(ruleContainer)

        root.addView(Button(this).apply {
            text = "添加规则"
            setOnClickListener {
                addRuleRow("", "", true)
            }
        })

        root.addView(Button(this).apply {
            text = "保存设置"
            setOnClickListener {
                saveRulesFromUi()
                val selected = if (modeGroup.checkedRadioButtonId == rbSend.id) {
                    AppSettings.MODE_SEND
                } else {
                    AppSettings.MODE_REALTIME
                }
                appSettings.mode = selected
                appSettings.lockReplacement = lockCheck.isChecked
                Toast.makeText(this@MainActivity, "已保存", Toast.LENGTH_SHORT).show()
            }
        })

        return scroll
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

        val check = CheckBox(this).apply {
            isChecked = enabled
        }
        row.addView(check)

        val sourceEdit = EditText(this).apply {
            hint = "原词"
            setText(source)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(sourceEdit)

        row.addView(TextView(this).apply {
            text = " → "
            textSize = 18f
        })

        val replacementEdit = EditText(this).apply {
            hint = "替换为"
            setText(replacement)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(replacementEdit)

        row.addView(Button(this).apply {
            text = "删"
            setOnClickListener {
                ruleContainer.removeView(row)
            }
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

    private fun updateServiceStatus() {
        val enabled = isReplacementServiceEnabled()
        serviceStatus.text = if (enabled) {
            "状态：已开启（正在全局替换）"
        } else {
            "状态：未开启，请点击下方按钮去系统设置里开启"
        }
        serviceStatus.setTextColor(if (enabled) Color.GREEN else Color.RED)
    }

    private fun isReplacementServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val fullName = "$packageName/${GlobalReplaceService::class.java.name}"
        val shortName = "$packageName/.GlobalReplaceService"

        return enabledServices.split(':').any {
            it.equals(fullName, ignoreCase = true) || it.equals(shortName, ignoreCase = true)
        }
    }
}
