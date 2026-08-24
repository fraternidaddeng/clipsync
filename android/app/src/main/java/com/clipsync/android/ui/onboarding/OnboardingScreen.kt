package com.clipsync.android.ui.onboarding

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipsync.android.ui.theme.ClipSyncIcons
import com.clipsync.android.ui.theme.ClipSyncTheme
import com.clipsync.android.ui.theme.ClipSyncType
import com.clipsync.android.ui.theme.charterCard
import com.clipsync.android.ui.theme.clipSyncColors

/**
 * First-run introduction (ui-gap-audit P1 空状态/首次运行): one quiet page,
 * shown once. It names the three places, points at the pairing entrance under
 * 通路, and states the capability limits honestly before the user meets them.
 * Charter styled: serif greeting, flow-blue primary, ghost secondary, no green.
 */
@Composable
fun OnboardingScreen(
    onPair: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    // The three dock icons, positionally matched to OnboardingContent.tabs.
    val icons = listOf(ClipSyncIcons.History, ClipSyncIcons.Conduit, ClipSyncIcons.Prefs)
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(
            imageVector = ClipSyncIcons.Conduit,
            contentDescription = null,
            tint = c.flow,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = OnboardingContent.TITLE,
            style = ClipSyncType.brand,
            color = c.t1,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = OnboardingContent.SUBTITLE,
            style = ClipSyncType.caption,
            color = c.t3,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(26.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            OnboardingContent.tabs.forEachIndexed { index, entry ->
                if (index > 0) {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .height(1.dp)
                            .background(c.ln),
                    )
                }
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
                            text = entry.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.t1,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = entry.description,
                            style = ClipSyncType.caption,
                            color = c.t3,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        HonestyNote()
        Spacer(Modifier.height(24.dp))
        PrimaryAction(label = OnboardingContent.ACTION_PAIR, onClick = onPair)
        Spacer(Modifier.height(10.dp))
        GhostAction(label = OnboardingContent.ACTION_SKIP, onClick = onSkip)
        Spacer(Modifier.height(12.dp))
    }
}

/** A stated fact on a quiet face — honesty is not a warning, so no ochre here. */
@Composable
private fun HonestyNote(modifier: Modifier = Modifier) {
    val c = clipSyncColors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.sf3)
            .border(1.dp, c.ln, shape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = OnboardingContent.HONESTY_HEADER,
            style = ClipSyncType.groupHeader,
            color = c.t4,
        )
        Text(
            text = OnboardingContent.HONESTY_BODY,
            style = ClipSyncType.caption,
            color = c.t3,
        )
    }
}

/** The page's one solid button: pairing is the step everything else waits on. */
@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = clipSyncColors
    val shape = RoundedCornerShape(12.dp)
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

/** Ghost family: quiet outline, no fill — skipping is allowed, not promoted. */
@Composable
private fun GhostAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = clipSyncColors
    val shape = RoundedCornerShape(12.dp)
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
        OnboardingScreen(onPair = {}, onSkip = {})
    }
}
