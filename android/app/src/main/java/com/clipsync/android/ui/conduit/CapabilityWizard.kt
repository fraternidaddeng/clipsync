package com.clipsync.android.ui.conduit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clipsync.android.platform.clipboard.CapabilityState

/**
 * The capability wizard (charter §4.1): not seven chores but three routes to open the read
 * segment, each shown as quality / cost / steps remaining. The user picks by "能不能用、代价
 * 是什么", never by permission names.
 */
@Composable
fun CapabilityWizard(
    routes: List<RouteUi>,
    onRouteAction: (RouteUi, RouteActionId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "打通后台读取的三条路线",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "任选一条完成即可；质量越高，复制到同步的延迟越小。授权状态每次回到本页都会重新探测。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        routes.forEach { route ->
            RouteCard(route = route, onAction = { action -> onRouteAction(route, action) })
        }
    }
}

@Composable
private fun RouteCard(
    route: RouteUi,
    onAction: (RouteActionId) -> Unit,
) {
    val border = if (route.preferred) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        null
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = border,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = route.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                QualityDots(filled = route.quality)
            }
            Text(
                text = "代价：${route.cost}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            route.steps.forEach { step -> StepRow(step) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = routeProgressLabel(route),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (route.stepsRemaining == 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (route.preferred) {
                    Text(
                        text = "当前首选",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            route.errorCode?.let { code ->
                Text(
                    text = code,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            route.nextAction?.let { action ->
                if (action == RouteActionId.SET_PREFERRED) {
                    OutlinedButton(
                        onClick = { onAction(action) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(routeActionLabel(action))
                    }
                } else {
                    Button(
                        onClick = { onAction(action) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(routeActionLabel(action))
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: RouteStep) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (step.satisfied) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = CircleShape,
                ),
        )
        Text(
            text = step.label,
            style = MaterialTheme.typography.bodySmall,
            color = if (step.satisfied) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun QualityDots(filled: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        color = if (index < filled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

private fun routeProgressLabel(route: RouteUi): String = when {
    route.stepsRemaining > 0 -> "还差 ${route.stepsRemaining} 步"
    route.readState == CapabilityState.DEGRADED -> "前提已就绪，等待实测验证"
    else -> "前提已就绪"
}

fun routeActionLabel(action: RouteActionId): String = when (action) {
    RouteActionId.INSTALL_SHIZUKU -> "获取 Shizuku"
    RouteActionId.LAUNCH_SHIZUKU -> "打开 Shizuku"
    RouteActionId.REQUEST_SHIZUKU_PERMISSION -> "请求 Shizuku 授权"
    RouteActionId.COPY_ADB_READ_LOGS_COMMAND -> "复制 adb 命令"
    RouteActionId.OPEN_OVERLAY_SETTINGS -> "去设置悬浮窗"
    RouteActionId.OPEN_BATTERY_SETTINGS -> "去设置电池"
    RouteActionId.SET_PREFERRED -> "设为首选路线"
}
