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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.tv.material3.Border
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
    /** "Qui regarde ?" (account at the top). */
    onAccount: () -> Unit,
    /** Réglages (bottom). */
    onSettings: () -> Unit,
    /** Back to the video playing in the small window. */
    onRestorePlayer: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (open) RAIL_OPEN_WIDTH else RAIL_WIDTH, tween(220), label = "rail-width")
    val scrim by animateFloatAsState(if (open) 0.6f else 0f, tween(220), label = "rail-scrim")
    val sections = Section.entries.filter { !it.account || state.signedIn }
    val requesters = remember { Section.entries.associateWith { FocusRequester() } }
    val playerItem = remember { FocusRequester() }
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
                    // Entering the menu lands on "Lecture en cours" when a video is in the small
                    // window (Back then OK: full screen again), else on the current section.
                    .focusProperties { enter = { if (state.minimized) playerItem else requesters[state.section] ?: FocusRequester.Default } }
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
                        RailItem("Lecture en cours", open, icon = LumenIcon.PIP, current = true, requester = playerItem, onClick = onRestorePlayer)
                    }
                    sections.forEach { section ->
                        RailItem(section.label, open, icon = section.icon, current = section == state.section && state.opened == null, requester = requesters.getValue(section)) {
                            onSection(section)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                RailItem("Réglages", open, icon = LumenIcon.SETTINGS, onClick = onSettings)
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
private fun Avatar(url: String?, name: String?) = AvatarPicture(url, name, 32.dp, 15.sp)

/** Round account picture (sharp: asked in 256 px), else the name's initial. */
@Composable
private fun AvatarPicture(url: String?, name: String?, size: androidx.compose.ui.unit.Dp, initialSize: androidx.compose.ui.unit.TextUnit) {
    Box(Modifier.size(size).clip(CircleShape).background(Palette.ControlIdle), contentAlignment = Alignment.Center) {
        if (url != null) {
            GlideImage(model = avatarUrl(url), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (name != null) {
            Text(name.take(1).uppercase(), fontSize = initialSize, fontWeight = FontWeight.SemiBold)
        } else {
            Icon(LumenIcon.PLAY, size = size / 2, color = Palette.Danger)
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
 * "Qui regarde ?": the linked accounts as round avatars, and "Ajouter". Opened by the account at
 * the top of the menu, and at launch when several accounts are linked. OK switches account.
 */
@Composable
fun ProfileDialog(accounts: List<Account>, onChoose: (Account) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        Column(
            Modifier
                .background(Palette.Panel, DialogShape)
                .padding(horizontal = 40.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Qui regarde ?", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(28.dp))
            Row(
                Modifier.widthIn(max = 840.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // The current account first takes the focus.
                val focusIndex = accounts.indexOfFirst { it.selected }.coerceAtLeast(0)
                accounts.forEachIndexed { index, account ->
                    ProfileTile(
                        name = account.name,
                        caption = if (account.selected) "Compte actuel" else null,
                        modifier = if (index == focusIndex) Modifier.focusRequester(first) else Modifier,
                        onClick = { onChoose(account) },
                    ) { AvatarPicture(account.avatar, account.name, PROFILE_AVATAR, 34.sp) }
                }
                ProfileTile(
                    name = "Ajouter",
                    caption = null,
                    modifier = if (accounts.isEmpty()) Modifier.focusRequester(first) else Modifier,
                    onClick = onAdd,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(LumenIcon.PLUS, size = 36.dp) }
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
private fun ProfileTile(name: String, caption: String?, modifier: Modifier, onClick: () -> Unit, picture: @Composable () -> Unit) {
    Column(Modifier.width(PROFILE_TILE), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(PROFILE_AVATAR),
            shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = FocusScale.Avatar),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White), shape = CircleShape),
            ),
            colors = ClickableSurfaceDefaults.colors(containerColor = Palette.ControlIdle, focusedContainerColor = Palette.Selected),
        ) { picture() }
        Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        Text(caption.orEmpty(), fontSize = 12.sp, color = Palette.TextSecondary, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/**
 * Réglages (bottom of the menu): the app's switches, and signing out of the current account. A
 * centered panel that never touches the top or bottom of the screen; its list scrolls if needed.
 */
@Composable
fun SettingsDialog(settings: Settings, accountName: String?, onSettings: (Settings) -> Unit, onPair: () -> Unit, onSignOut: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
        Column(
            Modifier
                .width(600.dp)
                .heightIn(max = maxHeight)
                .background(Palette.Panel, DialogShape)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Réglages", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, bottom = 10.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("LECTURE")
                DialogRow("Lecture automatique", if (settings.autoplay) "Activée" else "Désactivée", modifier = Modifier.focusRequester(first)) {
                    onSettings(settings.copy(autoplay = !settings.autoplay))
                }
                DialogRow("SponsorBlock", if (settings.sponsorBlock) "Activé" else "Désactivé") { onSettings(settings.copy(sponsorBlock = !settings.sponsorBlock)) }
                DialogRow("Volume stable", if (settings.stableVolume) "Activé" else "Désactivé") { onSettings(settings.copy(stableVolume = !settings.stableVolume)) }
                SectionLabel("AFFICHAGE")
                DialogRow("DeArrow (titres et miniatures sans racolage)", if (settings.deArrow) "Activé" else "Désactivé") { onSettings(settings.copy(deArrow = !settings.deArrow)) }
                SectionLabel("TÉLÉPHONE")
                DialogRow("Lier un téléphone (Regarder sur la TV)", icon = LumenIcon.PLUS, onClick = onPair)
                DialogRow("Commande depuis le téléphone", if (settings.remote) "Activée" else "Désactivée") { onSettings(settings.copy(remote = !settings.remote)) }
                if (accountName != null) {
                    SectionLabel("COMPTE")
                    DialogRow("Se déconnecter de $accountName", icon = LumenIcon.CLOSE, onClick = onSignOut)
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
internal fun SectionLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextSecondary, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp))
}

private val PROFILE_AVATAR = 96.dp
private val PROFILE_TILE = 128.dp

@Composable
internal fun DialogRow(
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

