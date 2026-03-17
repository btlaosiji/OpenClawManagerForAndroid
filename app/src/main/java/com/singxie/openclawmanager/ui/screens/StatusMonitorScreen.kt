package com.singxie.openclawmanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.singxie.openclawmanager.data.gateway.ConnectionState
import com.singxie.openclawmanager.ui.MainViewModel

@Composable
fun StatusMonitorScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val health by viewModel.health.collectAsState()
    val status by viewModel.status.collectAsState()
    val presence by viewModel.presence.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "状态监控",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Connection status card
        Card(

            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "Gateway 连接",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (connectionState) {
                        is ConnectionState.Connecting,
                        is ConnectionState.WaitingChallenge -> CircularProgressIndicator(
                            modifier = Modifier.padding(4.dp),
                            strokeWidth = 2.dp
                        )
                        else -> Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    when (connectionState) {
                                        is ConnectionState.Connected -> MaterialTheme.colorScheme.primary
                                        is ConnectionState.Error -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.outline
                                    }
                                )
                        )
                    }
                    Text(
                        text = when (connectionState) {
                            is ConnectionState.Connected -> "已连接"
                            is ConnectionState.Disconnected -> "未连接"
                            is ConnectionState.Connecting -> "连接中…"
                            is ConnectionState.WaitingChallenge -> "握手中…"
                            is ConnectionState.Error -> "错误: ${(connectionState as ConnectionState.Error).message}"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Health card
        Card(

            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "健康检查",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (health.isEmpty()) {
                    Text(
                        text = "暂无数据（请先连接 Gateway）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    health.entries.take(10).forEach { (k, v) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = k,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = v?.toString() ?: "—",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        // Status overview card
        Card(

            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "状态概览",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (status.isEmpty()) {
                    Text(
                        text = "暂无数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    status.entries.take(12).forEach { (k, v) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = k,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = v?.toString()?.take(40) ?: "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        // Presence (devices/channels)
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                val maxPresence = 32
                val displayPresence = presence.take(maxPresence)
                Text(
                    text = "在线设备 / 通道 (${presence.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (presence.isEmpty()) {
                    Text(
                        text = "暂无在线设备",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    if (presence.size > maxPresence) {
                        Text(
                            text = "仅显示最近 $maxPresence 条，共 ${presence.size} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    displayPresence.forEach { entry ->
                        val deviceId = entry["deviceId"]?.toString() ?: entry["id"]?.toString() ?: "—"
                        val roles = (entry["roles"] as? List<*>)?.joinToString() ?: entry["roles"]?.toString() ?: "—"
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = deviceId,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = roles,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
