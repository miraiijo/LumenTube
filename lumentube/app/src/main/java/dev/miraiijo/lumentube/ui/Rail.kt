@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalGlideComposeApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.miraiijo.lumentube.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import dev.miraiijo.lumentube.UiState
import dev.miraiijo.lumentube.data.Section

/** Collapsed width of the side menu: the pages start after it. */
val RAIL_WIDTH = 80.dp
private val RAIL_OPEN_WIDTH = 220.dp
private val RAIL_ITEM_HEIGHT = 44.dp
private val RAIL_EDGE_FADE = 56.dp

/**
 * Lumen's side menu: the account on top, the sections, Compte at the bottom. With the focus it
 * opens over the page (labels) and the page dims.
 */
@Composable
fun SideRail(
    state: UiState,
    modifier: Modifier,
    onFocusChange: (Boolean) -> Unit,
    onSection: (Section) -> Unit,
    onAccount: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (open) RAIL_OPEN_WIDTH else RAIL_WIDTH, tween(220), label = "rail-width")
    val scrim by animateFloatAsState(if (open) 0.6f else 0f, tween(220), label = "rail-scrim")
    val sections = Section.entries.filter { !it.account || state.signedIn }
    val requesters = remember { Section.entries.associateWith { FocusRequester() } }
    Box(Modifier.fillMaxSize()) {
        if (scrim > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = scrim }.background(Color.Black))
        Row(Modifier.fillMaxHeight()) {
            Column(
                modifier
                    .width(width)
                    .fillMaxHeight()
                    .clipToBounds()
                    .background(Palette.Background)
                    .onFocusChanged {
                        open = it.hasFocus
                        onFocusChange(it.hasFocus)
                    }
                    // Entering the menu always lands on the current section.
                    .focusProperties { enter = { requesters[state.section] ?: FocusRequester.Default } }
                    .focusGroup()
                    .padding(horizontal = 12.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RailItem(
                    label = state.accountName ?: "LumenTube",
                    open = open,
                    leading = { Avatar(state.accountAvatar, state.accountName) },
                    onClick = onAccount,
                )
                Spacer(Modifier.height(24.dp))
                sections.forEach { section ->
                    RailItem(section.label, open, icon = section.icon, current = section == state.section && state.opened == null, requester = requesters.getValue(section)) {
                        onSection(section)
                    }
                }
                Spacer(Modifier.weight(1f))
                RailItem(if (state.signedIn) "Se déconnecter" else "Se connecter", open, icon = LumenIcon.USER, onClick = onAccount)
            }
            Box(
                Modifier
                    .width(RAIL_EDGE_FADE)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(0f to Palette.Background, 0.45f to Palette.Background.copy(alpha = 0.6f), 1f to Color.Transparent)),
            )
        }
    }
}

@Composable
private fun Avatar(url: String?, name: String?) {
    Box(Modifier.size(32.dp).clip(CircleShape).background(Palette.ControlIdle), contentAlignment = Alignment.Center) {
        if (url != null) {
            GlideImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (name != null) {
            Text(name.take(1).uppercase(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Icon(LumenIcon.PLAY, size = 16.dp, color = Palette.Danger)
        }
    }
}

@Composable
private fun RailItem(
    label: String,
    open: Boolean,
    icon: LumenIcon? = null,
    current: Boolean = false,
    requester: FocusRequester? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(RAIL_ITEM_HEIGHT)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(Dimens.RadiusControl)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (current) Palette.ControlIdle else Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (leading != null) leading() else if (icon != null) Icon(icon, size = 20.dp)
            }
            if (open) {
                Spacer(Modifier.width(12.dp))
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** youtube.com/activate code, typed from a phone or a computer; closes by itself once linked. */
@Composable
fun SignInDialog(code: String?, error: String?, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val button = remember { FocusRequester() }
        Column(
            Modifier
                .width(560.dp)
                .background(Palette.Panel, DialogShape)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Connecter ton compte YouTube", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Sur ton téléphone ou ton ordinateur, ouvre youtube.com/activate et saisis ce code :",
                color = Palette.TextSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            when {
                error != null -> Text(error, color = Palette.Danger, fontSize = 15.sp, textAlign = TextAlign.Center)
                code == null -> Text("Code en cours…", color = Palette.TextSecondary, fontSize = 28.sp)
                else -> Text(code, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            }
            Text("LumenTube reste connecté ensuite ; tes abonnements et ton historique apparaissent dans le menu.", color = Palette.TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap)) {
                if (error != null) LumenButton("Réessayer", icon = LumenIcon.REFRESH, onClick = onRetry)
                LumenButton("Annuler", modifier = Modifier.focusRequester(button), onClick = onDismiss)
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            button.tryRequestFocus()
        }
    }
}
