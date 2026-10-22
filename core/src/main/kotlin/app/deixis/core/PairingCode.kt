package app.deixis.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Адрес для QR-кода на экране приёмника: телефон сканирует его и подключается без ввода адреса. */
data class PairingCode(val host: String, val port: Int, val pin: String, val id: String, val name: String) {

    fun toUri(): String = "deixis://$host:$port?pin=$pin&id=${enc(id)}&name=${enc(name)}"

    companion object {
        fun parse(uri: String): PairingCode? = try {
            val u = URI(uri)
            if (u.scheme != "deixis" || u.host == null || u.port <= 0) null
            else {
                val q = u.rawQuery.orEmpty().split('&').mapNotNull {
                    val i = it.indexOf('=')
                    if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
                }.toMap()
                val pin = q["pin"]
                if (pin == null || !pin.matches(Regex("\\d{4}"))) null
                else PairingCode(u.host, u.port, pin, q["id"].orEmpty(), q["name"].orEmpty())
            }
        } catch (e: Exception) {
            null
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

/** Матрица QR-кода: true — тёмный модуль. Рисует её каждая платформа сама. */
fun qrMatrix(text: String): Array<BooleanArray> {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8")
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
}
