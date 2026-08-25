package com.clipsync.android.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipsync.android.R
import com.clipsync.android.i18n.string
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.ui.theme.CharterShapes
import com.clipsync.android.ui.theme.ClipSyncType
import com.clipsync.android.ui.theme.charterCard
import com.clipsync.android.ui.theme.clipSyncColors

/**
 * The capability wizard (charter §4.1): not seven chores but three routes to
 * open the read segment, each shown as quality / cost / steps remaining. The
 * user picks by "能不能用、代价是什么", never by permission names.
 */
@Composable
fun CapabilityWizard(
    routes: List<ReadRouteUi>,
    onRouteAction: (ReadRouteUi, RouteActionId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.wizard_title),
            style = ClipSyncType.sectionTitle,
            color = c.t1,
            modifier = Modifier.padding(start = 2.dp, top = 4.dp),
        )
        Text(
            text = stringResource(R.string.wizard_subtitle),
            style = ClipSyncType.caption,
            color = c.t3,
            modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
        )
        routes.forEach { route ->
            RouteCard(route = route, onAction = { action -> onRouteAction(route, action) })
        }
    }
}

@Composable
private fun RouteCard(
    route: ReadRouteUi,
    onAction: (RouteActionId) -> Unit,
) {
    val c = clipSyncColors
    val surface =
        if (route.preferred) {
            Modifier
                .charterCard(corner = 16.dp)
                .border(1.5.dp, c.flowLn, CharterShapes.card)
        } else {
            Modifier.charterCard(corner = 16.dp)
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(surface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = route.title.string(),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.t1,
            )
            QualityDots(filled = route.quality)
        }
        Text(
            text = stringResource(R.string.wizard_cost, route.cost.string()),
            style = ClipSyncType.caption,
            color = c.t3,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(c.ln),
        )
        route.steps.forEach { step -> StepRow(step) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = routeProgressLabel(route),
                style = ClipSyncType.meta,
                color = if (route.stepsRemaining == 0) c.flow else c.t3,
            )
            if (route.preferred) {
                Text(
                    text = stringResource(R.string.wizard_preferred),
                    style = ClipSyncType.meta,
                    fontWeight = FontWeight.SemiBold,
                    color = c.flow,
                )
            }
        }
        route.errorCode?.let { code ->
            Text(
                text = code,
                style = ClipSyncType.meta,
                color = c.t4,
            )
        }
        route.nextAction?.let { action ->
            RouteActionButton(
                label = routeActionLabel(action).string(),
                primary = action != RouteActionId.SET_PREFERRED,
                onClick = { onAction(action) },
            )
        }
        route.readTestAction?.let { action ->
            RouteActionButton(
                label = routeActionLabel(action).string(),
                primary = true,
                onClick = { onAction(action) },
            )
        }
    }
}

@Composable
private fun RouteActionButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val c = clipSyncColors
    val shape = CharterShapes.control
    val surface =
        if (primary) {
            Modifier
                .background(c.flow)
        } else {
            Modifier
                .background(c.flowBg)
                .border(1.dp, c.flowLn, shape)
        }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .then(surface)
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) c.onFlow else c.flow,
        )
    }
}

@Composable
private fun StepRow(step: RouteStep) {
    val c = clipSyncColors
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .background(
                        color = if (step.satisfied) c.flow else c.ln2,
                        shape = CircleShape,
                    ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = step.label.string(),
            style = ClipSyncType.caption,
            color = if (step.satisfied) c.t2 else c.t3,
        )
    }
}

@Composable
private fun QualityDots(filled: Int) {
    val c = clipSyncColors
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            Box(
                modifier =
                    Modifier
                        .size(7.dp)
                        .background(
                            color = if (index < filled) c.flow else c.ln2,
                            shape = CircleShape,
                        ),
            )
        }
    }
}

@Composable
private fun routeProgressLabel(route: ReadRouteUi): String =
    when {
        route.stepsRemaining > 0 ->
            pluralStringResource(R.plurals.wizard_steps_remaining, route.stepsRemaining, route.stepsRemaining)
        route.readState == CapabilityState.DEGRADED -> stringResource(R.string.wizard_ready_pending_test)
        else -> stringResource(R.string.wizard_ready)
    }
