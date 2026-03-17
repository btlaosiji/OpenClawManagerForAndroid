package com.singxie.openclawmanager.ui

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import com.singxie.openclawmanager.CrashLogState
import com.singxie.openclawmanager.data.gateway.ConnectionState
import com.singxie.openclawmanager.ui.screens.ChatScreen
import com.singxie.openclawmanager.ui.screens.ModelConfigScreen
import com.singxie.openclawmanager.ui.screens.SkillManageScreen
import com.singxie.openclawmanager.ui.screens.StatusMonitorScreen

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    var pendingTabSwitch by remember { mutableStateOf<Int?>(null) }
    var showFaqDialog by remember { mutableStateOf(false) }
    val privacyAccepted by viewModel.privacyAccepted.collectAsState()
    var showPrivacyDialog by remember { mutableStateOf(!privacyAccepted) }
    val chatMessagesSize = viewModel.chatMessages.collectAsState().value.size
    val profiles by viewModel.profiles.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }
    var showContactDialog by remember { mutableStateOf(false) }

    LaunchedEffect(pendingTabSwitch) {
        val target = pendingTabSwitch ?: return@LaunchedEffect
        delay(50)
        selectedTab = target
        pendingTabSwitch = null
    }
    LaunchedEffect(selectedTab) {
        CrashLogState.lastSelectedTab = selectedTab
        if (selectedTab == 0) viewModel.restoreChatMessages()
    }
    LaunchedEffect(chatMessagesSize) { CrashLogState.chatMessagesSize = chatMessagesSize }
    val gatewayUrl by viewModel.gatewayUrl.collectAsState()
    val authToken by viewModel.authToken.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val pairingRequestId by viewModel.pairingRequestId.collectAsState()
    val lastConnectErrorCode by viewModel.lastConnectErrorCode.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(privacyAccepted) {
        showPrivacyDialog = !privacyAccepted
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("龙虾管理助手(OpenClaw)") },
                actions = {
                    TextButton(onClick = { menuExpanded = true }) { Text("+") }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("隐私政策") },
                            onClick = {
                                menuExpanded = false
                                context.startActivity(WebViewActivity.intent(context, WebViewActivity.PAGE_PRIVACY_POLICY))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("用户协议") },
                            onClick = {
                                menuExpanded = false
                                context.startActivity(WebViewActivity.intent(context, WebViewActivity.PAGE_USER_AGREEMENT))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("常见问题") },
                            onClick = {
                                menuExpanded = false
                                showFaqDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("联系我们") },
                            onClick = {
                                menuExpanded = false
                                showContactDialog = true
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (profiles.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "当前龙虾：${profiles.firstOrNull { it.id == activeProfileId }?.name ?: profiles.first().name}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (profiles.size > 1) {
                            TextButton(onClick = { viewModel.removeActiveProfile() }) { Text("删除") }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    val hScroll = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(hScroll),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        profiles.forEach { p ->
                            val selected = p.id == activeProfileId
                            Button(
                                onClick = { viewModel.setActiveProfile(p.id) },
                                colors = if (selected) {
                                    ButtonDefaults.buttonColors()
                                } else {
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            ) { Text(text = p.name) }
                        }
                        Button(
                            onClick = { viewModel.addProfileQuick() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) { Text("添加龙虾") }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = "Gateway 地址",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextField(
                    value = gatewayUrl,
                    onValueChange = viewModel::setGatewayUrl,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("ws://host:18789") }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Gateway Token（可选）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextField(
                    value = authToken,
                    onValueChange = viewModel::setAuthToken,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("OPENCLAW_GATEWAY_TOKEN") }
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (connectionState is ConnectionState.Connected) {
                        Button(onClick = viewModel::disconnect) { Text("断开") }
                        Spacer(Modifier.padding(8.dp))
                        TextButton(onClick = viewModel::refreshAll) { Text("刷新全部") }
                    } else {
                        Button(onClick = viewModel::connect) { Text("连接") }
                        Spacer(Modifier.padding(8.dp))
                        TextButton(onClick = { showFaqDialog = true }) { Text("常见问题") }
                    }
                }
                errorMessage?.let { msg ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = viewModel::clearError) { Text("清除") }
                }
                val errState = connectionState as? ConnectionState.Error
                val errMsg = errState?.message ?: ""
                if (errMsg.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    val (brief, hint) = ConnectErrorHelper.toChinese(errMsg, lastConnectErrorCode)
                    Text(
                        text = brief,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (hint != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (pairingRequestId != null) {
                    Spacer(Modifier.height(10.dp))
                    PairingRequestIdRow(
                        requestId = pairingRequestId!!,
                        onCopy = {
                            copyToClipboard(context, it)
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { if (selectedTab != 0) selectedTab = 0 },
                    text = { Text("对话") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        if (selectedTab == 0) { viewModel.cacheAndClearChatMessages(); pendingTabSwitch = 1 } else selectedTab = 1
                    },
                    text = { Text("状态监控") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = {
                        if (selectedTab == 0) { viewModel.cacheAndClearChatMessages(); pendingTabSwitch = 2 } else selectedTab = 2
                    },
                    text = { Text("模型配置") }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = {
                        if (selectedTab == 0) { viewModel.cacheAndClearChatMessages(); pendingTabSwitch = 3 } else selectedTab = 3
                    },
                    text = { Text("Skill 搜索") }
                )
            }

            key(selectedTab) {
                when (selectedTab) {
                    0 -> ChatScreen(viewModel = viewModel, modifier = Modifier.weight(1f))
                    1 -> StatusMonitorScreen(viewModel = viewModel, modifier = Modifier.weight(1f))
                    2 -> ModelConfigScreen(viewModel = viewModel, modifier = Modifier.weight(1f))
                    3 -> SkillManageScreen(viewModel = viewModel, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("隐私政策") },
            text = {
                Column {
                    Text(
                        text = "欢迎使用 OpenClaw 管理。请阅读并知悉我们的隐私政策与用户协议。使用本应用即表示您同意相关条款。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        TextButton(onClick = { context.startActivity(WebViewActivity.intent(context, WebViewActivity.PAGE_USER_AGREEMENT)) }) { Text("《用户协议》") }
                        TextButton(onClick = { context.startActivity(WebViewActivity.intent(context, WebViewActivity.PAGE_PRIVACY_POLICY)) }) { Text("《隐私政策》") }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.acceptPrivacy()
                    showPrivacyDialog = false
                }) { Text("同意") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        (context as? Activity)?.finish()
                    }
                ) { Text("拒绝") }
            }
        )
    }

    if (showContactDialog) {
        AlertDialog(
            onDismissRequest = { showContactDialog = false },
            title = { Text("联系我们") },
            text = {
                Text(
                    text = "龙虾管理助手（OpenClawManager） 是一个用于连接与管理 OpenClaw Gateway 的轻量工具。\n\n项目已开源:欢迎star,fork\n\n邮箱：singxie@qq.com",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                Button(onClick = { showContactDialog = false }) { Text("知道了") }
            },
            dismissButton = {
                Button(
                    onClick = {
                        // 跳转到GitHub项目页面
                        val uri = Uri.parse("https://github.com/你的用户名/OpenClawManager") // 请替换为实际的GitHub URL
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                ) {
                    Text("GitHub")
                }
            }
        )
    }

    if (showFaqDialog) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showFaqDialog = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = FaqContent.TITLE,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(16.dp))
                FaqContent.sections.forEachIndexed { index, (q, a) ->
                    if (index > 0) Spacer(Modifier.height(16.dp))
                    Text(
                        text = q,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = a,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { showFaqDialog = false },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("关闭") }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("OpenClaw", text))
}

@Composable
private fun PairingRequestIdRow(
    requestId: String,
    onCopy: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "配对请求 ID（用于在电脑执行 openclaw devices approve）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    text = requestId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.padding(4.dp))
            TextButton(onClick = { onCopy(requestId) }) { Text("复制") }
        }
    }
}
