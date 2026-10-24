package app.deixis.phone

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.deixis.receiver.ApkUpdates

/**
 * Отзыв без слежки: Deixis сам ничего не отправляет. Текст с версией и устройством уходит через
 * «Поделиться» — в Telegram, почту или любое приложение, куда выберет человек.
 */
object Feedback {
    fun device(context: Context): String =
        "Deixis ${ApkUpdates.currentVersion(context)} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"

    fun share(context: Context, subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun problem(context: Context) = share(
        context, "Проблема в Deixis",
        "Что случилось:\n\n\nЧто ожидалось:\n\n\nКак повторить:\n\n\n— ${device(context)}",
    )

    fun idea(context: Context) = share(context, "Идея для Deixis", "Идея:\n\n\nЗачем она нужна:\n\n\n— ${device(context)}")

    /** Отчёт о падении: причина уже вписана, остаётся добавить, что делалось перед этим. */
    fun crash(context: Context, report: String) = share(
        context, "Deixis закрылся из-за ошибки",
        "Что делалось перед этим:\n\n\n— Отчёт:\n$report",
    )
}

/** Раздел «Отзыв» в настройках. */
@Composable
fun FeedbackSection() {
    val context = LocalContext.current
    GHead("Отзыв")
    Group {
        Cell("Сообщить о проблеме", "Текст с версией и устройством уже заполнен", onClick = { Feedback.problem(context) })
        Sep(T.gutter)
        Cell("Идея", "Что добавить или сделать удобнее", onClick = { Feedback.idea(context) })
    }
    Note("Отправка через «Поделиться»: в Telegram, почту или любое приложение. Deixis сам ничего не отправляет.")
}
