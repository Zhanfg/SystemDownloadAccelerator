package com.v4atune.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val viewModel: TuneViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            V4ATuneTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    V4ATuneApp(viewModel)
                }
            }
        }
    }
}

private enum class AppPage(val label: String, val icon: ImageVector) {
    Tune("调校", Icons.Default.Home),
    Components("部件", Icons.Default.Tune),
    Report("报告", Icons.Default.Assessment),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun V4ATuneApp(viewModel: TuneViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    val page = AppPage.entries[pageIndex]

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
                        Text(
                            when (page) {
                                AppPage.Tune -> "V4ATune"
                                AppPage.Components -> "ViPER 部件"
                                AppPage.Report -> "校准报告"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            when (page) {
                                AppPage.Tune -> state.options.scene.label + " · " + state.options.target.label
                                AppPage.Components -> "自动 / 开启并优化 / 关闭"
                                AppPage.Report -> if (state.result != null) "最近一次校准" else "等待首次校准"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (page == AppPage.Tune) {
                        FilledTonalButton(
                            onClick = viewModel::refreshEnvironment,
                            enabled = !state.running,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(5.dp))
                            Text("刷新")
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
            ) {
                AppPage.entries.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = pageIndex == index,
                        onClick = { pageIndex = index },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (page) {
            AppPage.Tune -> TunePage(
                state = state,
                modifier = Modifier.padding(padding),
                onScene = viewModel::setScene,
                onTarget = viewModel::setTarget,
                onMode = viewModel::setMode,
                onEqBands = viewModel::setEqBands,
                onFirTaps = viewModel::setFirTaps,
                onStart = {
                    if (
                        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        viewModel.runTune()
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onRestore = viewModel::restoreLast,
                onOpenReport = { pageIndex = AppPage.Report.ordinal },
            )

            AppPage.Components -> ComponentsPage(
                state = state,
                modifier = Modifier.padding(padding),
                onPolicy = viewModel::setPolicy,
            )

            AppPage.Report -> ReportPage(
                state = state,
                modifier = Modifier.padding(padding),
                onTune = { pageIndex = AppPage.Tune.ordinal },
            )
        }
    }
}

@Composable
private fun TunePage(
    state: TuneUiState,
    modifier: Modifier,
    onScene: (Scene) -> Unit,
    onTarget: (Target) -> Unit,
    onMode: (TestMode) -> Unit,
    onEqBands: (Int) -> Unit,
    onFirTaps: (Int) -> Unit,
    onStart: () -> Unit,
    onRestore: () -> Unit,
    onOpenReport: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HeroCard(state, onStart) }

        item {
            SectionTitle(
                title = "使用场景",
                subtitle = "场景会真实改变目标曲线、延迟预算、动态和空间处理",
            )
        }

        item {
            SceneRail(
                current = state.options.scene,
                enabled = !state.running,
                onScene = onScene,
            )
        }

        item {
            SceneStrategyCard(state.options)
        }

        if (state.options.scene == Scene.Custom) {
            item {
                AssistChip(
                    onClick = {},
                    label = { Text("自定义参数已覆盖场景默认值") },
                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                )
            }
        }

        item {
            CoreSettingsCard(
                state = state,
                onTarget = onTarget,
                onMode = onMode,
                onEqBands = onEqBands,
                onFirTaps = onFirTaps,
            )
        }

        item {
            AnimatedVisibility(visible = state.running) {
                ProgressCard(state)
            }
        }

        state.error?.let { error ->
            item { MessageCard(Icons.Default.Error, "校准失败", error) }
        }

        state.restoreMessage?.let { message ->
            item { MessageCard(Icons.Default.Restore, "恢复", message) }
        }

        state.result?.let {
            item { QuickResultCard(it, onOpenReport) }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !state.running && state.rootReady,
                    onClick = onRestore,
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("恢复")
                }
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    enabled = !state.running && state.result != null,
                    onClick = onOpenReport,
                ) {
                    Icon(Icons.Default.Assessment, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("查看报告")
                }
            }
        }

        item {
            Text(
                "测量链：ViPER 管理器进程保持运行，只临时关闭当前服务内的 Master 来释放 Global / Per-App effects；" +
                    "RAW 测量不经过 ViPER，PROCESSED 验证只给检测 AudioTrack 的 session 临时挂 effect。" +
                    "最终提交才短暂重启管理器，并原样保留 Global Mode、主开关、自动启动和排除应用设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeroCard(state: TuneUiState, onStart: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.options.scene.label,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        state.options.scene.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Icon(
                    sceneIcon(state.options.scene),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(
                    ok = state.rootReady,
                    text = if (state.rootReady) "Root 已就绪" else "等待 Root",
                )
                StatusPill(
                    ok = state.driverReady,
                    text = if (state.driverReady) "ViPER AIDL 在线" else "ViPER AIDL 离线",
                )
                state.driver?.let {
                    StatusPill(ok = true, text = it.sampleRate.toString() + " Hz")
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.running && state.rootReady && state.driverReady,
                onClick = onStart,
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.running) "正在校准…" else "一键测量并调到最佳")
            }
        }
    }
}

@Composable
private fun StatusPill(ok: Boolean, text: String) {
    AssistChip(
        onClick = {},
        label = { Text(text) },
        leadingIcon = {
            Icon(
                if (ok) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
            )
        },
    )
}

@Composable
private fun SceneRail(
    current: Scene,
    enabled: Boolean,
    onScene: (Scene) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(end = 10.dp),
    ) {
        items(Scene.entries.filter { it != Scene.Custom }, key = { it.name }) { scene ->
            val selected = current == scene
            Card(
                onClick = { if (enabled) onScene(scene) },
                modifier = Modifier.width(156.dp),
                border = if (selected) {
                    BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                } else {
                    null
                },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(sceneIcon(scene), contentDescription = null)
                    Text(scene.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        scene.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun CoreSettingsCard(
    state: TuneUiState,
    onTarget: (Target) -> Unit,
    onMode: (TestMode) -> Unit,
    onEqBands: (Int) -> Unit,
    onFirTaps: (Int) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null)
                Column {
                    Text("核心校正", fontWeight = FontWeight.SemiBold)
                    Text(
                        "IIR 与 FIR 分开配置；高级改动会切换为自定义场景",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ChoiceRail(
                title = "测量",
                current = state.options.mode,
                values = TestMode.entries,
                labelOf = { it.label.substringBefore(" ·") },
                enabled = !state.running,
                onSelect = onMode,
            )

            ChoiceRail(
                title = "目标",
                current = state.options.target,
                values = Target.entries,
                labelOf = { it.label.substringBefore(" /") },
                enabled = !state.running,
                onSelect = onTarget,
            )

            ChoiceRail(
                title = "IIR",
                current = state.options.eqBands,
                values = listOf(10, 15, 25, 31),
                labelOf = { it.toString() + " 段" },
                enabled = !state.running,
                onSelect = onEqBands,
            )

            ChoiceRail(
                title = "FIR",
                current = state.options.firTaps,
                values = listOf(1024, 2048, 4096, 8192),
                labelOf = {
                    when (it) {
                        1024 -> "1K"
                        2048 -> "2K"
                        4096 -> "4K"
                        else -> "8K"
                    }
                },
                enabled = !state.running,
                onSelect = onFirTaps,
            )
        }
    }
}

@Composable
private fun <T> ChoiceRail(
    title: String,
    current: T,
    values: List<T>,
    labelOf: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = value == current,
                    onClick = { if (enabled) onSelect(value) },
                    enabled = enabled,
                    label = { Text(labelOf(value)) },
                )
            }
        }
    }
}

@Composable
private fun ProgressCard(state: TuneUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.Science, contentDescription = null)
                Column {
                    Text(state.status, fontWeight = FontWeight.SemiBold)
                    Text(
                        (state.progress * 100).toInt().toString() + "%",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun QuickResultCard(result: TuneResult, onOpenReport: () -> Unit) {
    Card(
        onClick = onOpenReport,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Assessment, contentDescription = null)
                Text("最近一次结果", fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniMetric(
                    modifier = Modifier.weight(1f),
                    label = "频响 RMS",
                    value = format(result.after.responseRmsDb) + " dB",
                    delta = format(result.before.responseRmsDb) + " →",
                )
                MiniMetric(
                    modifier = Modifier.weight(1f),
                    label = "最大偏差",
                    value = format(result.after.maxDeviationDb) + " dB",
                    delta = format(result.before.maxDeviationDb) + " →",
                )
            }
            Text(
                "IIR " + result.plan.eqLevels.size + " 段 · FIR " +
                    (result.plan.kernel?.size ?: 0) + " taps · " +
                    if (result.persistenceOk) "已持久化" else "实时 DSP",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (result.safetyAdjusted) {
                    "破音保护已介入 · 压力测试 THD " +
                        format(distortionMedian(result.distortionBefore) * 100.0) + "% → " +
                        format(distortionMedian(result.distortionAfter) * 100.0) + "%"
                } else {
                    "破音保护通过 · 压力测试 THD " +
                        format(distortionMedian(result.distortionAfter) * 100.0) + "%"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (result.safetyAdjusted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun MiniMetric(
    modifier: Modifier,
    label: String,
    value: String,
    delta: String,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(delta, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ComponentsPage(
    state: TuneUiState,
    modifier: Modifier,
    onPolicy: (Component, Policy) -> Unit,
) {
    val groups = listOf(
        "核心校正" to listOf(
            Component.SpeakerCorrection,
            Component.Equalizer,
            Component.Convolver,
            Component.DynamicEq,
            Component.Ddc,
        ),
        "动态与响度" to listOf(
            Component.PlaybackGain,
            Component.Lufs,
            Component.FetCompressor,
            Component.MultibandCompressor,
        ),
        "音色与低频" to listOf(
            Component.Spectrum,
            Component.PsychoBass,
            Component.Bass,
            Component.BassMono,
            Component.Clarity,
        ),
        "空间" to listOf(
            Component.FieldSurround,
            Component.DiffSurround,
            Component.StereoImager,
            Component.HeadphoneSurround,
            Component.Reverb,
        ),
        "染色与其他" to listOf(
            Component.DynamicSystem,
            Component.Cure,
            Component.Tube,
            Component.AnalogX,
        ),
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Memory, contentDescription = null)
                        Text("完整 ViPER 链", fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "当前场景：" + state.options.scene.label +
                            "。任何手动修改都会进入“自定义”，但仍保留其他参数。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        groups.forEach { (title, components) ->
            item {
                SectionTitle(title, componentGroupSubtitle(title))
            }
            items(components, key = { it.name }) { component ->
                ComponentPolicyCard(
                    component = component,
                    policy = state.options.policies[component] ?: Policy.Auto,
                    enabled = !state.running,
                    onPolicy = { onPolicy(component, it) },
                )
            }
        }
    }
}

@Composable
private fun ComponentPolicyCard(
    component: Component,
    policy: Policy,
    enabled: Boolean,
    onPolicy: (Policy) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(component.label, fontWeight = FontWeight.Medium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Policy.entries.forEach { item ->
                    FilterChip(
                        selected = item == policy,
                        onClick = { if (enabled) onPolicy(item) },
                        enabled = enabled,
                        label = { Text(item.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportPage(
    state: TuneUiState,
    modifier: Modifier,
    onTune: () -> Unit,
) {
    val result = state.result
    if (result == null) {
        Box(
            modifier = modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Default.Assessment, contentDescription = null)
                Text("还没有校准报告", style = MaterialTheme.typography.titleLarge)
                Text(
                    "完成一次测量后，这里会显示校准前后指标、DSP 链和最终部件决策。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onTune) {
                    Icon(Icons.Default.Science, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("去校准")
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Text("闭环校准完成", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "驱动 " + result.driverAfter.versionName + " · " +
                            result.driverAfter.sampleRate + " Hz · " +
                            if (result.persistenceOk) "持久化成功" else "实时 DSP",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            SectionTitle("关键指标", "数值越低通常代表频响残差越小")
        }

        item {
            MetricComparisonCard("频响 RMS", result.before.responseRmsDb, result.after.responseRmsDb, "dB")
        }
        item {
            MetricComparisonCard("最大偏差", result.before.maxDeviationDb, result.after.maxDeviationDb, "dB")
        }

        item {
            EqCurveCard(result.plan)
        }

        item {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("测量摘要", fontWeight = FontWeight.SemiBold)
                    MetricLine("低频缺口", format(result.before.lowDeficitDb) + " dB")
                    MetricLine("高频缺口", format(result.before.highDeficitDb) + " dB")
                    MetricLine(
                        "压力测试 THD",
                        format(distortionMedian(result.distortionBefore) * 100.0) + "% → " +
                            format(distortionMedian(result.distortionAfter) * 100.0) + "%",
                    )
                    MetricLine(
                        "破音保护",
                        if (result.safetyAdjusted) "已自动回退增益" else "通过",
                    )
                    MetricLine("左右声道差", format(result.before.channelDeltaDb) + " dB")
                    MetricLine("压缩量", format(result.before.compressionDb) + " dB")
                    MetricLine("IIR", result.plan.eqLevels.size.toString() + " 段")
                    MetricLine("FIR", (result.plan.kernel?.size ?: 0).toString() + " taps")
                }
            }
        }

        item {
            SectionTitle("最终 DSP 决策", "开启的模块均已经计算参数，不是只切换开关")
        }

        items(result.plan.decisions.entries.toList(), key = { it.key.name }) { entry ->
            val enabled = entry.value.first
            Card {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        if (enabled) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column {
                        Text(entry.key.label, fontWeight = FontWeight.Medium)
                        Text(
                            entry.value.second,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Text(
                "报告目录：" + result.outputDir.absolutePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricComparisonCard(
    title: String,
    before: Double,
    after: Double,
    unit: String,
) {
    Card {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                format(before) + "  →  " + format(after) + " " + unit,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            val improved = after <= before
            Text(
                if (improved) {
                    "残差下降 " + format(before - after) + " " + unit
                } else {
                    "本轮增加 " + format(after - before) + " " + unit + "，建议用深度模式复测"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (improved) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MessageCard(icon: ImageVector, title: String, message: String) {
    Card {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null)
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun sceneIcon(scene: Scene): ImageVector = when (scene) {
    Scene.Reference -> Icons.Default.GraphicEq
    Scene.Music -> Icons.Default.MusicNote
    Scene.Movie -> Icons.Default.Movie
    Scene.Game -> Icons.Default.SportsEsports
    Scene.Voice -> Icons.Default.Mic
    Scene.Outdoor -> Icons.Default.VolumeUp
    Scene.Night -> Icons.Default.DarkMode
    Scene.Custom -> Icons.Default.Settings
}

private fun componentGroupSubtitle(title: String): String = when (title) {
    "核心校正" -> "频响、卷积、动态 EQ 与设备校正"
    "动态与响度" -> "响度目标、增益控制与压缩"
    "音色与低频" -> "低频体感、高频延伸与清晰度"
    "空间" -> "宽度、深度、延迟与混响"
    else -> "耳机取向和模拟染色类处理"
}

private fun distortionMedian(values: List<DistortionProbe>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.map { it.thd }.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle]
    else (sorted[middle - 1] + sorted[middle]) / 2.0
}

private fun format(value: Double): String =
    java.lang.String.format(java.util.Locale.US, "%.2f", value)
