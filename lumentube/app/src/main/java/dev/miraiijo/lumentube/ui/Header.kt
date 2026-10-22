@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package dev.miraiijo.lumentube.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.miraiijo.lumentube.data.Section
import kotlinx.coroutines.delay

/**
 * The redesign's header (Lumen's): the section's name in large, the sections as tabs under it, and
 * on the right the round buttons (search, account, settings) above the time and, with a video in
 * the small window, "Lecture en cours". Replaces the side menu. OK on a tab opens that section.
 */
@Composable
fun TopHeader(
    section: Section,
    signedIn: Boolean,
    accountName: String?,
    accountAvatar: String?,
    minimized: Boolean,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit,
    onSection: (Section) -> Unit,
    onAccount: () -> Unit,
    onSettings: () -> Unit,
    onRestorePlayer: () -> Unit,
) {
    val sections = Section.entries.filter { it != Section.SEARCH && (!it.account || signedIn) }
    val requesters = remember { Section.entries.associateWith { FocusRequester() } }
    val playerButton = remember { FocusRequester() }
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { onFocusChange(it.hasFocus) }
            // Entering the header (Up from the rows, Back) lands on the current section's tab, or on
            // "Lecture en cours" with a video in the small window (Back then OK: full screen again).
            .focusProperties { enter = { if (minimized) playerButton else if (section in sections) requesters.getValue(section) else FocusRequester.Default } }
            .focusGroup()
            .padding(start = Dimens.Gutter, end = Dimens.Gutter, top = 28.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (section == Section.HOME) "Accueil" else section.label,
                fontSize = 44.sp,
                lineHeight = 50.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.03).em,
                color = Palette.TextPrimary,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                sections.forEach { item ->
                    Tab(
                        label = if (item == Section.HOME) "Pour toi" else item.label,
                        current = item == section,
                        modifier = Modifier.focusRequester(requesters.getValue(item)),
                        onClick = { onSection(item) },
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The clock in the top right corner, at the end of the buttons' line.
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(onClick = { onSection(Section.SEARCH) }) { Icon(LumenIcon.SEARCH, size = 16.dp) }
                RoundButton(onClick = onAccount, filled = false) { AvatarPicture(accountAvatar, accountName, HEADER_BUTTON, 17.sp) }
                RoundButton(onClick = onSettings) { Icon(LumenIcon.SETTINGS, size = 16.dp) }
                HeaderClock(Modifier.padding(start = 7.dp))
            }
            if (minimized) {
                Row {
                    // White by day with a light shadow, like the round buttons (translucent at night).
                    LumenButton(
                        "Lecture en cours",
                        modifier = Modifier.dayShadow().focusRequester(playerButton),
                        icon = LumenIcon.PIP,
                        containerColor = if (Palette.colors.isLight) Color.White else Palette.ControlIdle,
                        onClick = onRestorePlayer,
                    )
                }
            }
        }
    }
}

/** A section tab: bold when it's the current one, a solid pill when focused. */
@Composable
private fun Tab(label: String, current: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (current) Palette.TextPrimary else Palette.TextSecondary,
            focusedContainerColor = Palette.Focus,
            focusedContentColor = Palette.OnFocus,
            pressedContainerColor = Palette.Focus,
            pressedContentColor = Palette.OnFocus,
        ),
    ) {
        // fillMaxHeight, not fillMaxSize: in the tabs' Row, a full-width tab pushed all the others out.
        Box(Modifier.fillMaxHeight().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 15.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Medium, maxLines = 1, softWrap = false)
        }
    }
}

/** Lumen's round header button (white by day, translucent at night; solid focus color when focused). */
@Composable
private fun RoundButton(onClick: () -> Unit, filled: Boolean = true, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.then(if (filled) Modifier.dayShadow() else Modifier).size(HEADER_BUTTON),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = FocusScale.Avatar),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (!filled) Color.Transparent else if (Palette.colors.isLight) Color.White else Palette.ControlIdle,
            contentColor = Palette.TextPrimary,
            focusedContainerColor = Palette.Focus,
            focusedContentColor = Palette.OnFocus,
            pressedContainerColor = Palette.Focus,
            pressedContentColor = Palette.OnFocus,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Palette.FocusRing), inset = 3.dp, shape = CircleShape),
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun HeaderClock(modifier: Modifier = Modifier) {
    var time by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE)
        while (true) {
            time = format.format(java.util.Date())
            delay(10_000)
        }
    }
    Text(time, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.TextSecondary, modifier = modifier)
}

/**
 * The redesign's background: the page color with two soft lights (warm top right, cool bottom
 * left), drawn once per size change — no layer, no animation.
 */
fun Modifier.lumenBackground(): Modifier = drawBehind {
    val colors = Palette.colors
    drawRect(colors.background)
    // Lights fading out at a quarter of the width (the redesign's 860 px glow, transparent at 70 %).
    drawRect(Brush.radialGradient(listOf(colors.glowWarm, Color.Transparent), center = Offset(size.width * 0.78f, 0f), radius = size.width * GLOW_RADIUS))
    drawRect(Brush.radialGradient(listOf(colors.glowCool, Color.Transparent), center = Offset(0f, size.height), radius = size.width * GLOW_RADIUS))
}

private val HEADER_BUTTON = 38.dp
private const val GLOW_RADIUS = 0.25f

/** By day, the header's white buttons get a soft shadow (none at night, on the dark page). */
@Composable
private fun Modifier.dayShadow(): Modifier =
    if (Palette.colors.isLight) shadow(10.dp, CircleShape, clip = false, ambientColor = Color(0x14121317), spotColor = Color(0x14121317)) else this
