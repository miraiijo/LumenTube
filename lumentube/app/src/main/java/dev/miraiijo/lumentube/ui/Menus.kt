@file:OptIn(ExperimentalTvMaterial3Api::class)

package dev.miraiijo.lumentube.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import dev.miraiijo.lumentube.Pairing
import dev.miraiijo.lumentube.data.Video

/**
 * Long press on a card: what YouTube's own "⋮" menu offers. Account actions only once signed in;
 * "Pas intéressé" / "Ne pas recommander" only on the cards YouTube allows it for (recommendations).
 */
@Composable
fun CardMenuDialog(
    video: Video,
    signedIn: Boolean,
    canGiveFeedback: Boolean,
    onPlay: () -> Unit,
    onWatchLater: () -> Unit,
    onChannel: () -> Unit,
    onNotInterested: (channel: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    LumenDialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        Column(
            Modifier
                .width(520.dp)
                .background(Palette.Panel, DialogShape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Palette.Tile)) {
                    Thumbnail(video.videoId, video.thumbnail, Modifier.matchParentSize(), custom = video.customThumbnail, small = true)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(video.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                    if (video.channel.isNotBlank()) Text(video.channel, fontSize = 13.sp, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            DialogRow("Lire", icon = LumenIcon.PLAY, modifier = Modifier.focusRequester(first), onClick = onPlay)
            if (signedIn && video.videoId != null) DialogRow("À regarder plus tard", icon = LumenIcon.HISTORY, onClick = onWatchLater)
            if (video.raw.channelId != null) DialogRow("Ouvrir la chaîne", icon = LumenIcon.USER, onClick = onChannel)
            if (signedIn && canGiveFeedback) {
                DialogRow("Pas intéressé", icon = LumenIcon.EYE_OFF) { onNotInterested(false) }
                if (video.raw.feedbackToken2 != null || video.raw.feedbackEndpoint != null) {
                    DialogRow("Ne pas recommander cette chaîne", icon = LumenIcon.CLOSE) { onNotInterested(true) }
                }
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            first.tryRequestFocus()
        }
    }
}

/**
 * "Lier un téléphone": the TV code for the phone's YouTube app (Paramètres › Regarder sur la TV ›
 * Saisir le code TV). Closes by itself once the phone is connected.
 */
@Composable
fun PairingDialog(pairing: Pairing, onRetry: () -> Unit, onDismiss: () -> Unit) {
    LumenDialog(onDismissRequest = onDismiss) {
        val button = remember { FocusRequester() }
        Column(
            Modifier
                .width(640.dp)
                .background(Palette.Panel, DialogShape)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Lier un téléphone", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Dans l’app YouTube du téléphone : photo de profil › Paramètres › Regarder sur la TV › Saisir le code TV, puis tape ce code :",
                color = Palette.TextSecondary,
                fontSize = 15.sp,
                lineHeight = 21.sp,
            )
            // A fixed slot: "Code en cours…", the code or the error, the dialog keeps its height.
            Box(Modifier.fillMaxWidth().height(CODE_SLOT), contentAlignment = Alignment.CenterStart) {
                when {
                    pairing.error != null -> Text(pairing.error, color = Palette.Danger, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    pairing.code == null -> Text("Code en cours…", color = Palette.TextSecondary, fontSize = 28.sp, lineHeight = 34.sp)
                    else -> Text(pairing.code, fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                }
            }
            Text(
                "Ensuite, le bouton Caster du téléphone envoie les vidéos ici (quand LumenTube est ouvert) et le téléphone sert de télécommande.",
                color = Palette.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap)) {
                if (pairing.error != null) LumenButton("Réessayer", icon = LumenIcon.REFRESH, onClick = onRetry)
                LumenButton("Fermer", modifier = Modifier.focusRequester(button), onClick = onDismiss)
            }
        }
        // At opening, and after "Réessayer" (it goes away with the error, focus and all): "Fermer".
        LaunchedEffect(pairing.error == null) {
            withFrameNanos { }
            button.tryRequestFocus()
        }
    }
}

/** A short message at the bottom center ("Ajoutée à À regarder plus tard."), never focusable. */
@Composable
fun Notice(text: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = text != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(300)), modifier = modifier) {
        // Keeps the last text while fading out.
        val shown = remember { mutableListOf("") }.also { if (text != null) it[0] = text }[0]
        Text(
            shown,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.85f), ControlShape).padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}
