package app.deixis.desktop

import java.util.Locale

/**
 * Строки окон самой программы на компьютере: русский (основной), узбекский латиницей, английский —
 * по языку системы. Пульт внутри окна переводится сам (его язык выбирают в настройках пульта).
 */
object Words {
    /** Как у пульта: узбекский и английский — по языку системы, остальные — русский (основной). */
    private val lang: Int = when (Locale.getDefault().language) {
        "uz" -> 1
        "en" -> 2
        else -> 0
    }

    private fun t(ru: String, uz: String, en: String) = arrayOf(ru, uz, en)[lang]

    val logFolder get() = t("Журнал программы…", "Dastur jurnali…", "App log…")
    val operatorTitle get() = t("Deixis — пульт", "Deixis — pult", "Deixis — remote")
    val newConnect get() = t("Новое подключение", "Yangi ulanish", "New connection")
    val newConnectAsk get() = t(
        "Это устройство ввело верный PIN и просит управлять экраном. Пустить?",
        "Bu qurilma to‘g‘ri PIN kiritdi va ekranni boshqarishni so‘ramoqda. Ruxsat berilsinmi?",
        "This device entered the correct PIN and asks to control the screen. Let it in?",
    )
    val allow get() = t("Разрешить", "Ruxsat berish", "Allow")
    val reject get() = t("Отклонить", "Rad etish", "Reject")
    val browser get() = t("Браузер", "Brauzer", "Browser")
    fun moreWaiting(n: Int) = t("Ещё ждут: $n.", "Yana kutmoqda: $n.", "Also waiting: $n.")
}
