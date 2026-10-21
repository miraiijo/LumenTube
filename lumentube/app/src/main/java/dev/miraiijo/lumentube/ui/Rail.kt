@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalGlideComposeApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.miraiijo.lumentube.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.LocalContentColor
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.miraiijo.lumentube.Settings
import dev.miraiijo.lumentube.data.Account
import dev.miraiijo.lumentube.UiState
import dev.miraiijo.lumentube.data.Section

/** Collapsed width of the side menu: the pages start after it. */
val RAIL_WIDTH = 80.dp
private val RAIL_OPEN_WIDTH = 220.dp
private val RAIL_ITEM_HEIGHT = 40.dp
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
    /** Account / settings dialog. */
    onAccount: () -> Unit,
    /** Back to the video playing in the small window. */
    onRestorePlayer: () -> Unit = {},
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
                    .padding(horizontal = 12.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RailItem(
                    label = state.accountName ?: "LumenTube",
                    open = open,
                    leading = { Avatar(state.accountAvatar, state.accountName) },
                    onClick = onAccount,
                )
                Spacer(Modifier.height(16.dp))
                // The sections scroll when they don't fit (account on top, settings at the bottom stay).
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (state.minimized) {
                        RailItem("Lecture en cours", open, icon = LumenIcon.PIP, current = true, onClick = onRestorePlayer)
                    }
                    sections.forEach { section ->
                        RailItem(section.label, open, icon = section.icon, current = section == state.section && state.opened == null, requester = requesters.getValue(section)) {
                            onSection(section)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                RailItem("Compte et réglages", open, icon = LumenIcon.SETTINGS, onClick = onAccount)
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

/**
 * Sign-in: scan the QR code with a phone (the code is already filled in), or open
 * youtube.com/activate and type it. Closes by itself once the account is linked.
 */
@Composable
fun SignInDialog(code: String?, error: String?, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val button = remember { FocusRequester() }
        Row(
            Modifier
                .width(760.dp)
                .background(Palette.Panel, DialogShape)
                .padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Connecter un compte YouTube", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Scanne le QR code avec ton téléphone, ou ouvre youtube.com/activate et saisis ce code :",
                    color = Palette.TextSecondary,
                    fontSize = 15.sp,
                )
                when {
                    error != null -> Text(error, color = Palette.Danger, fontSize = 15.sp)
                    code == null -> Text("Code en cours…", color = Palette.TextSecondary, fontSize = 28.sp)
                    else -> Text(code, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                }
                Text("LumenTube reste connecté ensuite. Tu peux lier plusieurs comptes et choisir lequel à l'ouverture.", color = Palette.TextSecondary, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap), modifier = Modifier.padding(top = 6.dp)) {
                    if (error != null) LumenButton("Réessayer", icon = LumenIcon.REFRESH, onClick = onRetry)
                    LumenButton("Annuler", modifier = Modifier.focusRequester(button), onClick = onDismiss)
                }
            }
            Box(Modifier.size(220.dp).clip(ControlShape).background(Color.White), contentAlignment = Alignment.Center) {
                // YouTube's activation link with the code in it: the phone lands on the confirmation.
                if (code != null && error == null) QrCode(QR_SIGN_IN_URL + code.trim().replace(' ', '-'), Modifier.fillMaxSize().padding(10.dp))
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            button.tryRequestFocus()
        }
    }
}

/** Same address as SmartTube's QR sign-in. */
private const val QR_SIGN_IN_URL = "https://youtube.com/qr/activate/"

/** A QR code drawn on the device (ZXing): the sign-in code never goes through a QR web service. */
@Composable
private fun QrCode(content: String, modifier: Modifier) {
    val matrix = remember(content) {
        runCatching { QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1)) }.getOrNull()
    } ?: return
    Canvas(modifier) {
        val cell = size.minDimension / matrix.width
        val left = (size.width - cell * matrix.width) / 2
        val top = (size.height - cell * matrix.height) / 2
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) drawRect(Color.Black, topLeft = Offset(left + x * cell, top + y * cell), size = Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/**
 * "Qui regarde ?" at launch ([launch] = true, several accounts) or "Compte et réglages" from the
 * menu: the linked accounts (OK switches), add one, sign out, and the app's switches.
 */
@Composable
fun AccountDialog(
    launch: Boolean,
    accounts: List<Account>,
    signedIn: Boolean,
    settings: Settings,
    onChoose: (Account) -> Unit,
    onAdd: () -> Unit,
    onSignOut: () -> Unit,
    onSettings: (Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        Column(
            Modifier
                .width(620.dp)
                .heightIn(max = 600.dp)
                .background(Palette.Panel, DialogShape)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (launch) "Qui regarde ?" else "Compte et réglages", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                accounts.forEachIndexed { index, account ->
                    DialogRow(
                        label = account.name,
                        value = if (account.selected) "Compte actuel" else null,
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                        leading = { Avatar(account.avatar, account.name) },
                        onClick = { onChoose(account) },
                    )
                }
                DialogRow("Ajouter un compte", modifier = if (accounts.isEmpty()) Modifier.focusRequester(first) else Modifier, icon = LumenIcon.PLUS, onClick = onAdd)
                if (!launch) {
                    if (signedIn) DialogRow("Se déconnecter du compte actuel", icon = LumenIcon.CLOSE, onClick = onSignOut)
                    Spacer(Modifier.height(10.dp))
                    Text("LECTURE", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextSecondary, modifier = Modifier.padding(start = 12.dp))
                    DialogRow("Lecture automatique", if (settings.autoplay) "Activée" else "Désactivée") { onSettings(settings.copy(autoplay = !settings.autoplay)) }
                    DialogRow("SponsorBlock", if (settings.sponsorBlock) "Activé" else "Désactivé") { onSettings(settings.copy(sponsorBlock = !settings.sponsorBlock)) }
                    DialogRow("Volume stable", if (settings.stableVolume) "Activé" else "Désactivé") { onSettings(settings.copy(stableVolume = !settings.stableVolume)) }
                    DialogRow("DeArrow (titres et miniatures sans racolage)", if (settings.deArrow) "Activé" else "Désactivé") { onSettings(settings.copy(deArrow = !settings.deArrow)) }
                }
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            first.tryRequestFocus()
        }
    }
}

@Composable
private fun DialogRow(
    label: String,
    value: String? = null,
    modifier: Modifier = Modifier,
    icon: LumenIcon? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = ClickableSurfaceDefaults.shape(shape = ControlShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.TextPrimary,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (leading != null) leading() else if (icon != null) Icon(icon, size = 20.dp)
            }
            Spacer(Modifier.width(12.dp))
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (value != null) Text(value, fontSize = 14.sp, color = LocalContentColor.current.copy(alpha = 0.65f), modifier = Modifier.padding(start = 12.dp))
        }
    }
}

