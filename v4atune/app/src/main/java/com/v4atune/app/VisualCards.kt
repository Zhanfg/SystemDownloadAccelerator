package com.v4atune.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import kotlin.math.ln

@Composable
fun EqCurveCard(plan: Plan) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val levels = plan.eqLevels
    val freqs = plan.eqFrequencies

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "实际 IIR 校正曲线",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                levels.size.toString() + " 段 · 最大提升 " +
                    formatEq(levels.maxOrNull() ?: 0.0) + " dB · 最大衰减 " +
                    formatEq(levels.minOrNull() ?: 0.0) + " dB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = surface,
                shape = MaterialTheme.shapes.medium,
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                ) {
                    val topDb = 2.0
                    val bottomDb = -8.0

                    fun xFor(freq: Double): Float {
                        val minF = 20.0
                        val maxF = 20000.0
                        val norm = ln(freq.coerceIn(minF, maxF) / minF) / ln(maxF / minF)
                        return (norm * size.width).toFloat()
                    }

                    fun yFor(db: Double): Float {
                        val norm = (topDb - db.coerceIn(bottomDb, topDb)) / (topDb - bottomDb)
                        return (norm * size.height).toFloat()
                    }

                    listOf(2.0, 0.0, -2.0, -4.0, -6.0, -8.0).forEach { db ->
                        val y = yFor(db)
                        drawLine(
                            color = grid,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = if (db == 0.0) 2f else 1f,
                        )
                    }

                    listOf(20.0, 100.0, 1000.0, 10000.0, 20000.0).forEach { freq ->
                        val x = xFor(freq)
                        drawLine(
                            color = grid,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1f,
                        )
                    }

                    if (levels.isNotEmpty() && freqs.size == levels.size) {
                        val path = Path()
                        levels.indices.forEach { i ->
                            val x = xFor(freqs[i])
                            val y = yFor(levels[i])
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(
                            path = path,
                            color = primary,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("20", style = MaterialTheme.typography.labelSmall)
                Text("100", style = MaterialTheme.typography.labelSmall)
                Text("1k", style = MaterialTheme.typography.labelSmall)
                Text("10k", style = MaterialTheme.typography.labelSmall)
                Text("20k Hz", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun SceneStrategyCard(options: TuneOptions) {
    val items = when (options.scene) {
        Scene.Reference -> listOf("低染色", "高动态", "31 段校正", "按需 FIR")
        Scene.Music -> listOf("均衡耐听", "轻低频", "声像稳定", "保留瞬态")
        Scene.Movie -> listOf("对白增强", "宽声场", "低频氛围", "中等延迟")
        Scene.Game -> listOf("低延迟", "定位优先", "关闭长 FIR", "关闭混响")
        Scene.Voice -> listOf("中频优先", "对白清晰", "低频克制", "快速测量")
        Scene.Outdoor -> listOf("高响度", "多段压缩", "抗环境噪声", "心理低频")
        Scene.Night -> listOf("低响度", "轻压缩", "对白优先", "低频克制")
        Scene.Custom -> listOf("手动配置", "保持覆盖", "逐组件控制", "自定义链路")
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "场景策略",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items.take(2).forEach { item ->
                    StrategyPill(
                        modifier = Modifier.weight(1f),
                        text = item,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items.drop(2).forEach { item ->
                    StrategyPill(
                        modifier = Modifier.weight(1f),
                        text = item,
                    )
                }
            }
        }
    }
}

@Composable
private fun StrategyPill(
    modifier: Modifier,
    text: String,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun formatEq(value: Double): String =
    java.lang.String.format(java.util.Locale.US, "%+.1f", value)
