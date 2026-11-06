package app.deixis.phone

import app.deixis.core.DeixisJson
import app.deixis.core.FileRef
import app.deixis.core.TextStyle
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Библиотека, прочитанная из файла, и сколько в нём было непонятного (из более новой версии Deixis). */
data class ReadStore(val data: StoreData, val skipped: Int)

/**
 * Чтение библиотеки, которое переживает файл более новой версии Deixis (после отката версии или с копии
 * с другого телефона). Сначала строго; не вышло — по частям: каждый элемент, сетлист, тема и запись отдельно,
 * непонятное пропускается, неизвестные значения перечислений берут значение по умолчанию. Так старая версия
 * открывает почти всю библиотеку, а не пустую. null — это вообще не библиотека Deixis.
 */
object StoreRead {
    private val lenient = Json(from = DeixisJson) { coerceInputValues = true }

    fun read(text: String): ReadStore? {
        runCatching { return ReadStore(DeixisJson.decodeFromString(StoreData.serializer(), text), 0) }
        val root = runCatching { DeixisJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        var skipped = 0
        fun <T> list(key: String, s: KSerializer<T>): JsonArray? {
            val arr = root[key] as? JsonArray ?: return null
            val good = arr.filter { e -> runCatching { lenient.decodeFromJsonElement(s, e) }.isSuccess }
            skipped += arr.size - good.size
            return JsonArray(good)
        }
        fun <T> map(key: String, s: KSerializer<T>): JsonObject? {
            val obj = root[key] as? JsonObject ?: return null
            val good = obj.filter { (_, v) -> runCatching { lenient.decodeFromJsonElement(s, v) }.isSuccess }
            skipped += obj.size - good.size
            return JsonObject(good)
        }
        val fixed = root.toMutableMap<String, JsonElement>()
        list("items", LibItem.serializer())?.let { fixed["items"] = it }
        list("setlists", Setlist.serializer())?.let { fixed["setlists"] = it }
        list("themes", Theme.serializer())?.let { fixed["themes"] = it }
        map("styles", TextStyle.serializer())?.let { fixed["styles"] = it }
        map("hashes", FileRef.serializer())?.let { fixed["hashes"] = it }
        map("conversions", Conversion.serializer())?.let { fixed["conversions"] = it }
        // Остальные поля: непонятное — по умолчанию, а не вся библиотека в пустоту.
        for (key in fixed.keys.toList()) {
            val probe = JsonObject(mapOf(key to fixed.getValue(key)))
            if (runCatching { lenient.decodeFromJsonElement(StoreData.serializer(), probe) }.isFailure) { fixed.remove(key); skipped++ }
        }
        val data = runCatching { lenient.decodeFromJsonElement(StoreData.serializer(), JsonObject(fixed)) }.getOrNull() ?: return null
        return ReadStore(data, skipped)
    }

    @Suppress("unused") private val keepListSerializer = ListSerializer(LibItem.serializer())
}
