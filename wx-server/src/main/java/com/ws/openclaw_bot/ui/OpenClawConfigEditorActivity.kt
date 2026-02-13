package com.ws.wx_server.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ws.wx_server.R
import java.io.File

class OpenClawConfigEditorActivity : AppCompatActivity() {
    private lateinit var pathText: TextView
    private lateinit var editor: EditText
    private lateinit var saveBtn: Button
    private lateinit var reloadBtn: Button
    private var targetFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.openclaw_activity_config_editor)

        pathText = findViewById(R.id.tv_config_path)
        editor = findViewById(R.id.et_config_content)
        saveBtn = findViewById(R.id.btn_config_save)
        reloadBtn = findViewById(R.id.btn_config_reload)

        reloadBtn.setOnClickListener { loadConfigFile() }
        saveBtn.setOnClickListener { saveConfigFile() }

        loadConfigFile()
    }

    private fun loadConfigFile() {
        val file = resolveTargetConfigFile()
        targetFile = file
        pathText.text = "文件路径: ${file.absolutePath}"
        val content = runCatching {
            if (file.isFile) {
                file.readText(Charsets.UTF_8)
            } else {
                "{\n}\n"
            }
        }.getOrElse {
            Toast.makeText(this, "读取失败: ${it.message}", Toast.LENGTH_SHORT).show()
            "{\n}\n"
        }
        editor.setText(content)
    }

    private fun saveConfigFile() {
        val file = targetFile ?: resolveTargetConfigFile().also { targetFile = it }
        val parent = file.parentFile
        if (parent == null) {
            Toast.makeText(this, "保存失败: 无效目录", Toast.LENGTH_SHORT).show()
            return
        }
        val backup = File(parent, "${file.name}.1")
        val newContent = editor.text?.toString() ?: ""

        runCatching {
            if (!parent.exists() && !parent.mkdirs()) {
                error("无法创建目录: ${parent.absolutePath}")
            }

            if (file.exists()) {
                if (backup.exists() && !backup.delete()) {
                    error("无法删除旧备份: ${backup.absolutePath}")
                }
                if (!file.renameTo(backup)) {
                    file.copyTo(backup, overwrite = true)
                    if (!file.delete()) {
                        error("无法重命名原文件为备份")
                    }
                }
            }

            file.writeText(newContent, Charsets.UTF_8)
        }.onSuccess {
            Toast.makeText(this, "保存成功，已备份为 ${backup.name}", Toast.LENGTH_SHORT).show()
            loadConfigFile()
        }.onFailure { err ->
            if (!file.exists() && backup.exists()) {
                runCatching { backup.copyTo(file, overwrite = true) }
            }
            Toast.makeText(this, "保存失败: ${err.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun resolveTargetConfigFile(): File {
        val home = resolveTermuxHomeDir()
        val configDir = File(home, ".openclaw")
        val primary = File(configDir, "openclaw.json")
        if (primary.exists()) return primary

        val fallback = File("/data/data/com.termux/files/home/.openclaw/openclaw.json")
        if (fallback.exists()) return fallback
        return primary
    }

    private fun resolveTermuxHomeDir(): File {
        val candidates = linkedSetOf<File>()
        candidates.add(File(applicationInfo.dataDir, "files/home"))
        candidates.add(File("/data/data/com.termux/files/home"))
        candidates.add(File("/data/user/0/com.termux/files/home"))
        for (candidate in candidates) {
            if (candidate.exists() || candidate.parentFile?.exists() == true) {
                return candidate
            }
        }
        return File(applicationInfo.dataDir, "files/home")
    }
}
