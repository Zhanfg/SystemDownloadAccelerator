package com.v4atune.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val viewModel: TuneViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    V4ATuneApp(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun V4ATuneApp(viewModel: TuneViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.runTune()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("V4ATune")
                        Text(
                            "ViPER4Android RE 自适应声学校准",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 8.dp,
                bottom = 28.dp,
            ),
        ) {
            item {
                EnvironmentCard(state)
            }

            item {
                SectionCard(Icons.Default.Settings, "调音目标") {
                    SelectorRow(
                        label = "目标",
                        current = state.options.target,
                        values = Target.entries,
                        labelOf = { it.label },
                        enabled = !state.running,
                        onSelect = viewModel::setTarget,
                    )
                    SelectorRow(
                        label = "测试模式",
                        current = state.options.mode,
                        values = TestMode.entries,
                        labelOf = { it.label },
                        enabled = !state.running,
                        onSelect = viewModel::setMode,
                    )
                }
            }

            item {
                SectionCard(Icons.Default.GraphicEq, "核心校正") {
                    Text(
                        "IIR 是驱动原生图形均衡；FIR 是卷积器脉冲响应。二者独立工作。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    SelectorRow(
                        label = "IIR 频段",
                        current = state.options.eqBands,
                        values = listOf(10, 15, 25, 31),
                        labelOf = { it.toString() + " 段" },
                        enabled = !state.running,
                        onSelect = viewModel::setEqBands,
                    )
                    SelectorRow(
                        label = "FIR 长度",
                        current = state.options.firTaps,
                        values = listOf(1024, 2048, 4096, 8192),
                        labelOf = { it.toString() + " taps" },
                        enabled = !state.running,
                        onSelect = viewModel::setFirTaps,
                    )
                    Text(
                        "默认 31 段 + 4096 taps：4096 对齐 ViPERDSP 单个卷积分区，兼顾低频分辨率和实时负载。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionHeader(
                    icon = Icons.Default.Memory,
                    title = "ViPER 部件策略",
                    subtitle = "自动：按实测决定；开启并优化：强制开启并计算真实参数；关闭：完全绕过。",
                )
            }

            items(Component.entries, key = { it.name }) { component ->
                val policy = state.options.policies[component] ?: Policy.Auto
                Card(modifier = Modifier.fillMaxWidth()) {
                    SelectorRow(
                        label = component.label,
                        current = policy,
                        values = Policy.entries,
                        labelOf = { it.label },
                        enabled = !state.running,
                        onSelect = { viewModel.setPolicy(component, it) },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            item {
                if (state.running) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator()
                                Column {
                                    Text(state.status, fontWeight = FontWeight.Medium)
                                    Text(
                                        (state.progress * 100).toInt().toString() + "%",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.running && state.rootReady && state.driverReady,
                    onClick = {
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.runTune()
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                ) {
                    Icon(Icons.Default.Science, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text("一键测量并调到最佳")
                }
            }

            item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.running && state.rootReady,
                    onClick = viewModel::restoreLast,
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text("恢复最近一次校准前配置")
                }
            }

            state.result?.let { result ->
                item {
                    ResultCard(result)
                }
            }

            state.error?.let { error ->
                item {
                    MessageCard(
                        icon = Icons.Default.Error,
                        title = "失败",
                        message = error,
                    )
                }
            }

            state.restoreMessage?.let { message ->
                item {
                    MessageCard(
                        icon = Icons.Default.Restore,
                        title = "恢复",
                        message = message,
                    )
                }
            }

            item {
                Text(
                    "测量说明：本机扬声器 → 本机麦克风属于相对自校准。标准模式用全频指数扫频得到稠密响应，" +
                        "再用 100 Hz / 1 kHz / 8 kHz 失真探针、左右声道和平动态探针补充判断；" +
                        "第二轮只做短扫频验证残差，因此比逐频点测试更快。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EnvironmentCard(state: TuneUiState) {
    SectionCard(Icons.Default.Speed, "环境") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = {},
                label = { Text(if (state.rootReady) "Root ✓" else "Root ✗") },
                leadingIcon = {
                    Icon(
                        if (state.rootReady) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                    )
                },
            )
            AssistChip(
                onClick = {},
                label = { Text(if (state.driverReady) "ViPER AIDL ✓" else "ViPER AIDL ✗") },
                leadingIcon = {
                    Icon(
                        if (state.driverReady) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                    )
                },
            )
        }
        Text(state.status, style = MaterialTheme.typography.bodyMedium)
        state.driver?.let {
            Text(
                "驱动 " + it.versionName + " · " + it.arch + " · " + it.sampleRate + " Hz",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResultCard(result: TuneResult) {
    SectionCard(Icons.Default.CheckCircle, "校准结果") {
        MetricRow("频响 RMS", result.before.responseRmsDb, result.after.responseRmsDb, "dB")
        MetricRow("最大偏差", result.before.maxDeviationDb, result.after.maxDeviationDb, "dB")
        Text(
            "低频缺口 " + format(result.before.lowDeficitDb) + " dB · " +
                "THD 中位 " + format(result.before.medianThd * 100.0) + "% · " +
                "左右差 " + format(result.before.channelDeltaDb) + " dB",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "IIR: " + result.plan.eqLevels.size + " 段 · FIR: " +
                (result.plan.kernel?.size?.toString() ?: "关闭") + " taps · 持久化: " +
                if (result.persistenceOk) "成功" else "仅实时 DSP",
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        result.plan.decisions.forEach { (component, decision) ->
            Text(
                (if (decision.first) "✓ " else "— ") + component.label + " · " + decision.second,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "报告目录：" + result.outputDir.absolutePath,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MetricRow(label: String, before: Double, after: Double, unit: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Text(format(before) + " → " + format(after) + " " + unit, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MessageCard(icon: ImageVector, title: String, message: String) {
    SectionCard(icon, title) {
        Text(message)
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String, subtitle: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    ) {
        Icon(icon, contentDescription = null)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCard(
    icon: ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, contentDescription = null)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun <T> SelectorRow(
    label: String,
    current: T,
    values: List<T>,
    labelOf: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Box {
            TextButton(
                enabled = enabled,
                onClick = { expanded = true },
            ) {
                Text(labelOf(current))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                values.forEach { value ->
                    DropdownMenuItem(
                        text = { Text(labelOf(value)) },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}

private fun format(value: Double): String =
    java.lang.String.format(java.util.Locale.US, "%.2f", value)
