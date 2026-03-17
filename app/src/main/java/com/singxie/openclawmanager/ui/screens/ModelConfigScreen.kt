package com.singxie.openclawmanager.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.singxie.openclawmanager.data.gateway.ConnectionState
import com.singxie.openclawmanager.ui.MainViewModel

/** 常用模型选项（格式 provider/model，见 https://docs.openclaw.ai/concepts/model-providers） */
private val PRESET_MODELS = listOf(
    "openai/gpt-5.4" to "openai/gpt-5.4",
    "openai/gpt-5-mini" to "openai/gpt-5-mini",
    "google/gemini-3.1-pro-preview" to "google/gemini-3.1-pro-preview",
    "google/gemini-3-flash-preview" to "google/gemini-3-flash-preview",
    "anthropic/claude-opus-4-6" to "anthropic/claude-opus-4-6",
    "anthropic/claude-sonnet-4-6" to "anthropic/claude-sonnet-4-6",
    "deepseek/deepseek-chat" to "deepseek/deepseek-chat",
    "deepseek/deepseek-reasoner" to "deepseek/deepseek-reasoner",
    "openrouter/deepseek/deepseek-chat" to "openrouter/deepseek/deepseek-chat",
    "opencode/claude-opus-4-6" to "opencode/claude-opus-4-6",
    "ollama/llama3.3" to "ollama/llama3.3",
)

@Composable
fun ModelConfigScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val currentPrimaryModel by viewModel.currentPrimaryModel.collectAsState()
    val currentModelKeyStatus by viewModel.currentModelKeyStatus.collectAsState()
    val configSetError by viewModel.configSetError.collectAsState()
    val saveSuccessToast by viewModel.saveSuccessToast.collectAsState()
    val context = LocalContext.current

    var expanded by remember { mutableStateOf(false) }
    var selectedLabel by remember(currentPrimaryModel) {
        mutableStateOf(
            PRESET_MODELS.find { it.second == currentPrimaryModel }?.first ?: currentPrimaryModel ?: ""
        )
    }
    var keyInput by remember { mutableStateOf("") }
    var saveKeyHint by remember { mutableStateOf<String?>(null) }
    val selectedRef = PRESET_MODELS.find { it.first == selectedLabel }?.second ?: currentPrimaryModel ?: ""
    val focusManager = LocalFocusManager.current

    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) viewModel.refreshConfig()
    }
    LaunchedEffect(currentPrimaryModel) {
        selectedLabel = PRESET_MODELS.find { it.second == currentPrimaryModel }?.first ?: currentPrimaryModel ?: ""
    }
    LaunchedEffect(saveSuccessToast) {
        saveSuccessToast?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearSaveSuccessToast()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "模型配置",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (connectionState !is ConnectionState.Connected) {
            Text(
                text = "请先连接 Gateway 后再查看或修改模型。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // 选择模型 + 保存模型按钮同一行
            Text(
                text = "选择模型",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = selectedLabel.ifEmpty { "请选择模型" },
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { Text("▼", style = MaterialTheme.typography.bodySmall) }
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { expanded = true }
                    )
                }
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        if (selectedRef.isNotBlank()) viewModel.setPrimaryModelAndApply(selectedRef)
                    },
                    enabled = selectedRef.isNotBlank()
                ) {
                    Text("保存模型")
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                PRESET_MODELS.forEach { (label, ref) ->
                    DropdownMenuItem(
                        text = { Text("$label  ($ref)") },
                        onClick = {
                            selectedLabel = label
                            expanded = false
                        }
                    )
                }
            }

            // API Key 输入框 + 保存 Key 按钮同一行
            Text(
                text = "API Key（当前模型）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = {
                        Text(
                            when (currentModelKeyStatus) {
                                "已配置", "已配置（已脱敏）" -> "•••••••• 已配置，输入新 Key 可覆盖"
                                else -> "未配置时请填写；已配置可留空或输入新 Key 覆盖"
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        saveKeyHint = null
                        if (keyInput.isNotBlank()) {
                            viewModel.setCurrentModelApiKey(keyInput.trim())
                            keyInput = ""
                        } else {
                            saveKeyHint = "请先输入 API Key 后再保存"
                        }
                    }
                ) {
                    Text("保存 Key")
                }
            }
            if (selectedRef.isBlank()) {
                Text(
                    text = "请先从上方下拉中选择一个模型后再保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            configSetError?.let { err ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = viewModel::clearConfigSetError) { Text("清除") }
                }
            }
            Text(
                text = "切换模型后点「保存模型」即可；若新模型未配置 Key，在上方填写后点「保存 Key」。连接后会自动获取配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            saveKeyHint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                text = "Key 仅对当前使用模型生效；保存后若连接断开多为 Gateway 已应用，请重新连接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
