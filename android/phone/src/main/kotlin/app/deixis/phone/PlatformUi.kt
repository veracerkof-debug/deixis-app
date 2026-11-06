package app.deixis.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import app.deixis.core.LogoStyle
import app.deixis.core.ScriptureContent
import app.deixis.core.TextStyle as SlideStyle

// Пульт на телефоне: то, что экраны пульта (pult/) берут у Android.

/** Выбор файлов через систему документов: доступ к ним сохраняется, сами файлы не копируются. */
@Composable
fun rememberFilePicker(multiple: Boolean = false, onPick: (List<String>) -> Unit): FilePicker {
    val many = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) onPick(uris.map { it.toString() }) }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { onPick(listOf(it.toString())) } }
    return remember(multiple) { FilePicker { mimes -> if (multiple) many.launch(mimes) else one.launch(mimes) } }
}

/** Папка вне приложения (флешка, облако, «Документы»). */
@Composable
fun rememberFolderPicker(onPick: (String) -> Unit): FolderPicker {
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { onPick(it.toString()) } }
    return remember { FolderPicker { tree.launch(null) } }
}

/** Кнопка «Назад» Android закрывает шторку и полноэкранный вид. */
@Composable
fun BackHandlerOn(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

/** Знак ΔΕΙΞΙΣ — тем же шрифтом, что на экране. */
val Didot: FontFamily get() = app.deixis.receiver.Didot

@Composable
fun LogoFull(style: LogoStyle = LogoStyle.WORDMARK, color: Color = Color.White) = app.deixis.receiver.LogoFull(style, color)

@Composable
fun TextSlideCanvas(text: String, ref: String?, style: SlideStyle, clear: Boolean = false, overscan: Boolean = false, drawBackground: Boolean = true) =
    app.deixis.receiver.TextSlideCanvas(text, ref, style, clear, overscan, drawBackground = drawBackground)

@Composable
fun ScriptureSlideCanvas(c: ScriptureContent, clear: Boolean = false, overscan: Boolean = false, drawBackground: Boolean = true) =
    app.deixis.receiver.ScriptureSlideCanvas(c, clear, overscan, drawBackground = drawBackground)

@Composable
fun rememberActs(c: Controller, ui: Ui): Acts {
    val context = LocalContext.current
    var then by remember { mutableStateOf<((LibItem) -> Unit)?>(null) }
    var presentation by remember { mutableStateOf(false) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) c.importFiles(uris.map { it.toString() }, presentation) { then?.invoke(it) }
    }
    val bible = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> c.importBible(u.toString()) } }

    // Трансляция экрана: разрешение на уведомление (Android 13+), затем системный запрос на захват.
    var openAfter by remember { mutableStateOf<LargeFile?>(null) }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            ScreenShareService.start(context, r.resultCode, data, sound = c.settings.value.shareSound)
            // Большой файл открывается в проигрывателе телефона, а в зал идёт экран.
            openAfter?.let { f ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(f.uri), f.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            }
        }
        openAfter = null
    }
    val requestCapture = { capture.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { requestCapture() }
    val afterSound = {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else requestCapture()
    }
    // Звук приложений телефона: Android даёт его снимать только с разрешением на запись звука (микрофон не включается).
    val soundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) ui.toast(L.noSoundPermission)
        afterSound()
    }

    return remember(c, ui) {
        Acts(
            pick = { mimes, pres, cb ->
                then = cb; presentation = pres
                runCatching { files.launch(mimes) }.onFailure { ui.toast(L.cantOpenPicker) }
            },
            share = { f ->
                if (c.outputs.value.none { it.state == OutState.ONLINE && it.target.kind != Target.Kind.DLNA }) ui.toast(L.needScreenFirst)
                else {
                    openAfter = f
                    val wantSound = c.settings.value.shareSound && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    if (wantSound && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        soundPermission.launch(Manifest.permission.RECORD_AUDIO)
                    } else afterSound()
                }
            },
            addBible = { runCatching { bible.launch(arrayOf("*/*")) } },
            holyrics = { ui.holyrics = true },
        )
    }
}

