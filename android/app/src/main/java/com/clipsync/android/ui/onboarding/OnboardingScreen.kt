package com.clipsync.android.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipsync.android.ui.theme.CharterMotion
import com.clipsync.android.ui.theme.CharterShapes
import com.clipsync.android.ui.theme.ClipSyncIcons
import com.clipsync.android.ui.theme.ClipSyncTheme
import com.clipsync.android.ui.theme.ClipSyncType
import com.clipsync.android.ui.theme.LocalReducedMotion
import com.clipsync.android.ui.theme.charterCard
import com.clipsync.android.ui.theme.clipSyncColors

/**
 * First-run tutorial (extends the ui-gap-audit P1 one-pager): five quiet
 * steps — welcome, pairing, the read path (特权直读 recommended), permissions,
 * send-off. Shown once ([FirstRunStore]), replayable from 偏好 · 帮助.
 * Charter styled throughout: serif greeting, flow-blue single primary per
 * screen, ghost secondaries, no green, day/night from the shared palette.
 * Never a trap: every step but the last carries 稍后设置.
 */
@Composable
fun OnboardingScreen(
    onPair: () -> Unit,
    onOpenWizard: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val step = OnboardingContent.steps[stepIndex]
    // System back walks the tutorial back before it can leave the screen.
    BackHandler(enabled = stepIndex > 0) { stepIndex = OnboardingContent.previous(stepIndex) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        StepChrome(
            stepIndex = stepIndex,
            onBack = { stepIndex = OnboardingContent.previous(stepIndex) },
            onSkip = onSkip,
            // The last step's ghost action already leaves; no duplicate exit.
            showSkip = step != OnboardingStep.FINISH,
        )
        Crossfade(
            targetState = step,
            animationSpec = if (LocalReducedMotion.current) {
                snap()
            } else {
                CharterMotion.spec(CharterMotion.DUR_STANDARD_MS)
            },
            label = "onboarding-step",
            modifier = Modifier.weight(1f),
        ) { current ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (current) {
                    OnboardingStep.WELCOME -> WelcomeStep()
                    OnboardingStep.PAIR -> PairStep()
                    OnboardingStep.READ_ROUTES -> ReadRoutesStep()
                    OnboardingStep.PERMISSIONS -> PermissionsStep()
                    OnboardingStep.FINISH -> FinishStep()
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (step == OnboardingStep.FINISH) {
            // The send-off ladder: solid pair (the step everything waits on),
            // tinted wizard deep-dive, ghost "look around first".
            PrimaryAction(
                label = stringResource(OnboardingContent.ACTION_PAIR),
                onClick = onPair,
            )
            Spacer(Modifier.height(10.dp))
            TintedAction(
                label = stringResource(OnboardingContent.ACTION_WIZARD),
                onClick = onOpenWizard,
            )
            Spacer(Modifier.height(10.dp))
            GhostAction(
                label = stringResource(OnboardingContent.ACTION_SKIP),
                onClick = onSkip,
            )
        } else {
            PrimaryAction(
                label = stringResource(OnboardingContent.ACTION_NEXT),
                onClick = { stepIndex = OnboardingContent.next(stepIndex) },
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Top strip: 上一步 on the left, progress dots centred, 稍后设置 on the right. */
@Composable
private fun StepChrome(
    stepIndex: Int,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    showSkip: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
    ) {
        if (stepIndex > 0) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clip(CharterShapes.control)
                    .clickable(onClick = onBack)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "‹", fontSize = 15.sp, color = c.t3)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(OnboardingContent.ACTION_BACK),
                    fontSize = 12.sp,
                    color = c.t3,
                )
            }
        }
        StepDots(
            stepIndex = stepIndex,
            modifier = Modifier.align(Alignment.Center),
        )
        if (showSkip) {
            Text(
                text = stringResource(OnboardingContent.ACTION_SKIP_FOR_NOW),
                fontSize = 12.sp,
                color = c.t3,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .clip(CharterShapes.control)
                    .clickable(onClick = onSkip)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
    }
}

/** Progress as quiet dots — the walked path fills, the rest stays as line. */
@Composable
private fun StepDots(
    stepIndex: Int,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    val progress = stringResource(
        OnboardingContent.STEP_PROGRESS,
        stepIndex + 1,
        OnboardingContent.steps.size,
    )
    Row(
        modifier = modifier.semantics { contentDescription = progress },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OnboardingContent.steps.indices.forEach { index ->
            Box(
                modifier = Modifier
                    .size(if (index == stepIndex) 7.dp else 6.dp)
                    .background(
                        color = if (index <= stepIndex) c.flow else c.ln2,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Step 1 · welcome — the original one-page introduction, kept verbatim
// ---------------------------------------------------------------------------

@Composable
private fun WelcomeStep() {
    val c = clipSyncColors
    // The three dock icons, positionally matched to OnboardingContent.tabs.
    val icons = listOf(ClipSyncIcons.History, ClipSyncIcons.Conduit, ClipSyncIcons.Prefs)
    Spacer(Modifier.height(18.dp))
    Icon(
        imageVector = ClipSyncIcons.Conduit,
        contentDescription = null,
        tint = c.flow,
        modifier = Modifier.size(34.dp),
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(OnboardingContent.TITLE),
        style = ClipSyncType.brand,
        color = c.t1,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(OnboardingContent.SUBTITLE),
        style = ClipSyncType.caption,
        color = c.t3,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(22.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .charterCard(),
    ) {
        OnboardingContent.tabs.forEachIndexed { index, entry ->
            if (index > 0) CardDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icons[index],
                    contentDescription = null,
                    tint = c.flow,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(entry.title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.t1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(entry.description),
                        style = ClipSyncType.caption,
                        color = c.t3,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    QuietNote(
        header = stringResource(OnboardingContent.HONESTY_HEADER),
        body = stringResource(OnboardingContent.HONESTY_BODY),
    )
}

// ---------------------------------------------------------------------------
// Step 2 · pair with the PC
// ---------------------------------------------------------------------------

@Composable
private fun PairStep() {
    val c = clipSyncColors
    StepHeading(
        icon = ClipSyncIcons.Monitor,
        title = stringResource(OnboardingContent.PAIR_TITLE),
        body = stringResource(OnboardingContent.PAIR_BODY),
    )
    Spacer(Modifier.height(18.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .charterCard()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OnboardingContent.pairFacts.forEach { fact ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(7.dp)
                        .background(color = c.flow, shape = CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(fact),
                    style = ClipSyncType.caption,
                    color = c.t2,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Step 3 · the read path — 特权直读 recommended
// ---------------------------------------------------------------------------

@Composable
private fun ReadRoutesStep() {
    val c = clipSyncColors
    StepHeading(
        icon = ClipSyncIcons.Conduit,
        title = stringResource(OnboardingContent.READ_TITLE),
        body = stringResource(OnboardingContent.READ_BODY),
    )
    Spacer(Modifier.height(18.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .charterCard(),
    ) {
        OnboardingContent.routes.forEachIndexed { index, route ->
            if (index > 0) CardDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(route.title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.t1,
                    )
                    if (route.recommended) {
                        Spacer(Modifier.width(8.dp))
                        RecommendedChip()
                    }
                    Spacer(Modifier.weight(1f))
                    QualityDots(filled = route.quality)
                }
                Text(
                    text = stringResource(route.cost),
                    style = ClipSyncType.caption,
                    color = c.t3,
                )
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    QuietNote(body = stringResource(OnboardingContent.WIRELESS_DEBUG_HINT))
}

/** The recommendation is a quiet flow-tinted chip, not a shout. */
@Composable
private fun RecommendedChip(modifier: Modifier = Modifier) {
    val c = clipSyncColors
    val shape = CharterShapes.control
    Text(
        text = stringResource(OnboardingContent.ROUTE_RECOMMENDED),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = c.flow,
        modifier = modifier
            .clip(shape)
            .background(c.flowBg)
            .border(1.dp, c.flowLn, shape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** Route quality on the conduit wizard's own ●●● scale. */
@Composable
private fun QualityDots(
    filled: Int,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        color = if (index < filled) c.flow else c.ln2,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Step 4 · permissions overview
// ---------------------------------------------------------------------------

@Composable
private fun PermissionsStep() {
    val c = clipSyncColors
    StepHeading(
        icon = ClipSyncIcons.Service,
        title = stringResource(OnboardingContent.PERMS_TITLE),
        body = stringResource(OnboardingContent.PERMS_BODY),
    )
    Spacer(Modifier.height(18.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .charterCard(),
    ) {
        OnboardingContent.permissions.forEachIndexed { index, permission ->
            if (index > 0) CardDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(permission.title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.t1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(permission.description),
                    style = ClipSyncType.caption,
                    color = c.t3,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Step 5 · send-off
// ---------------------------------------------------------------------------

@Composable
private fun FinishStep() {
    val c = clipSyncColors
    Spacer(Modifier.height(38.dp))
    Icon(
        imageVector = ClipSyncIcons.Conduit,
        contentDescription = null,
        tint = c.flow,
        modifier = Modifier.size(34.dp),
    )
    Spacer(Modifier.height(14.dp))
    Text(
        text = stringResource(OnboardingContent.FINISH_TITLE),
        style = ClipSyncType.pageTitle,
        color = c.t1,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(OnboardingContent.FINISH_BODY),
        style = ClipSyncType.caption,
        color = c.t3,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

/** Icon + serif title + honest body: the head of every explaining step. */
@Composable
private fun StepHeading(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    val c = clipSyncColors
    Spacer(Modifier.height(18.dp))
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = c.flow,
        modifier = Modifier.size(28.dp),
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text = title,
        style = ClipSyncType.pageTitle,
        color = c.t1,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = body,
        style = ClipSyncType.caption,
        color = c.t3,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** Hairline between rows of the same card (the one sanctioned divider). */
@Composable
private fun CardDivider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(clipSyncColors.ln),
    )
}

/** A stated fact on a quiet face — honesty is not a warning, so no ochre here. */
@Composable
private fun QuietNote(
    body: String,
    modifier: Modifier = Modifier,
    header: String? = null,
) {
    val c = clipSyncColors
    val shape = CharterShapes.control
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.sf3)
            .border(1.dp, c.ln, shape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (header != null) {
            Text(
                text = header,
                style = ClipSyncType.groupHeader,
                color = c.t4,
            )
        }
        Text(
            text = body,
            style = ClipSyncType.caption,
            color = c.t3,
        )
    }
}

/** The page's one solid button (charter: a single protagonist per screen). */
@Composable
private fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    val shape = CharterShapes.control
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.flow)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$label ›",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.onFlow,
        )
    }
}

/** Flow-tinted secondary: promoted above ghost, but never the protagonist. */
@Composable
private fun TintedAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    val shape = CharterShapes.control
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.flowBg)
            .border(1.dp, c.flowLn, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.flow,
        )
    }
}

/** Ghost family: quiet outline, no fill — skipping is allowed, not promoted. */
@Composable
private fun GhostAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    val shape = CharterShapes.control
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, c.ln2, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = c.t3,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenPreview() {
    ClipSyncTheme {
        OnboardingScreen(onPair = {}, onOpenWizard = {}, onSkip = {})
    }
}
