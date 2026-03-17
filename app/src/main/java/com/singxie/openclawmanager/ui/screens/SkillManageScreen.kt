package com.singxie.openclawmanager.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.singxie.openclawmanager.data.remote.ClawHubSkillItem
import com.singxie.openclawmanager.ui.MainViewModel

@Composable
fun SkillManageScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val clawHubLoading by viewModel.clawHubLoading.collectAsState()
    val clawHubError by viewModel.clawHubError.collectAsState()
    val clawHubResults by viewModel.clawHubResults.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Skill 搜索",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        // 发现 Skill（ClawHub）
        Text(
            text = "发现 Skill（ClawHub）",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "搜索或查看热门 Skill，点击「复制安装命令」后在运行 Gateway 的电脑终端执行。数据来自 Top ClawHub Skills API。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        var searchQuery by remember { mutableStateOf("") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("关键词，如 search、api、browser") },
                singleLine = true
            )
            Button(
                onClick = { viewModel.searchClawHub(searchQuery.trim()) },
                enabled = !clawHubLoading
            ) { Text("搜索") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = { viewModel.loadTopClawHub() },
                enabled = !clawHubLoading
            ) { Text("热门下载") }
        }
        if (clawHubLoading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                Text(
                    text = "加载中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        clawHubError?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
        }
        clawHubResults.forEach { item ->
            ClawHubSkillCard(
                item = item,
                onCopyInstall = {
                    val cmd = "npx clawhub@latest install ${item.slug}"
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("clawhub install", cmd))
                    Toast.makeText(context, "已复制: $cmd", Toast.LENGTH_SHORT).show()
                }
            )
        }

        Spacer(Modifier.height(16.dp))

        // 按名称复制安装命令
        Text(
            text = "按名称复制安装命令",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "输入 Skill 名称后点击「复制安装命令」，在运行 Gateway 的电脑终端执行即可。详见 docs.openclaw.ai/tools/clawhub。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        var installSkillName by remember { mutableStateOf("") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = installSkillName,
                onValueChange = { installSkillName = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("例如 api-gateway、search") },
                singleLine = true
            )
            TextButton(
                onClick = {
                    val name = installSkillName.trim()
                    if (name.isBlank()) {
                        Toast.makeText(context, "请输入 Skill 名称", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val cmd = "npx clawhub@latest install $name"
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("clawhub install", cmd))
                    Toast.makeText(context, "已复制: $cmd", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("复制安装命令")
            }
        }
    }
}

@Composable
private fun ClawHubSkillCard(
    item: ClawHubSkillItem,
    onCopyInstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    item.summary?.take(150)?.let { summary ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "↓ ${formatCount(item.downloads)} · ★ ${item.stars}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (item.isCertified) {
                            Text(
                                text = "已认证",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                TextButton(onClick = onCopyInstall) { Text("复制安装命令") }
            }
        }
    }
}

private fun formatCount(n: Long): String {
    return when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fK".format(n / 1_000.0)
        else -> n.toString()
    }
}
