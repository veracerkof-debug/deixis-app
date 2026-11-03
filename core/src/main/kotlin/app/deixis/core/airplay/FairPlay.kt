// Перенесено из UxPlay (https://github.com/FDH2/UxPlay, GPL-3.0), модуль playfair (GPL-3.0),
// исходно shairplay / RPiPlay: lib/playfair/{playfair.c, omg_hax.c, hand_garble.c,
// modified_md5.c, sap_hash.c} и lib/fairplay_playfair.c.
// Авторы: EstebanKubata (playfair, https://github.com/EstebanKubata/playfair),
// Copyright (C) 2018 Juho Vähä-Herttua (shairplay, fairplay_playfair.c), авторы RPiPlay и UxPlay.
// Распространяется на условиях GPL-3.0-or-later вместе с проектом Deixis.
//
// Перенос дословный: порядок действий, таблицы и странности исходника сохранены, результат
// побитно сверен с кодом C (FairPlayTest). Байты внутри лежат в Int значениями 0..255,
// слова uint32_t — в Int с переносом по модулю 2^32; слова собираются из байтов в порядке
// little-endian, как на x86 и ARM, где работает исходник.

package app.deixis.core.airplay

import app.deixis.core.airplay.FairPlayTables as T

/**
 * FairPlay для приёма AirPlay: три шага `fp-setup` и расшифровка ключа потока.
 *
 * Порядок как в UxPlay: [setupReply] на первый запрос (16 байт), [handshakeReply] на второй
 * (164 байта); второй запрос сохраняется вызывающим как сообщение ключа и вместе с `ekey`
 * (72 байта из SETUP) даёт AES-ключ потока через [decryptKey].
 */
object FairPlay {
    const val SETUP_REQUEST_SIZE = 16
    const val SETUP_REPLY_SIZE = 142
    const val HANDSHAKE_REQUEST_SIZE = 164
    const val HANDSHAKE_REPLY_SIZE = 32
    const val EKEY_SIZE = 72
    const val KEY_SIZE = 16

    /** Шаг 1 (`fairplay_setup`): готовый ответ по режиму `request[14]`; null — запрос не FairPlay 3 или режим вне 0..3. */
    fun setupReply(request: ByteArray): ByteArray? {
        if (request.size != SETUP_REQUEST_SIZE || u(request[4]) != 0x03) return null
        val mode = u(request[14])
        if (mode > 3) return null
        return toBytes(T.replyMessage[mode])
    }

    /** Шаг 2 (`fairplay_handshake`): заголовок `fp_header` и байты 144..163 запроса. */
    fun handshakeReply(request: ByteArray): ByteArray? {
        if (request.size != HANDSHAKE_REQUEST_SIZE || u(request[4]) != 0x03) return null
        val reply = ByteArray(HANDSHAKE_REPLY_SIZE)
        for (i in 0 until 12) reply[i] = T.fpHeader[i].toByte()
        request.copyInto(reply, 12, 144, 164)
        return reply
    }

    /**
     * Ключ потока (`fairplay_decrypt` → `playfair_decrypt`). [keyMessage] — весь 164-байтный
     * запрос второго шага fp-setup («message3», в UxPlay `keymsg`), [ekey] — 72 байта.
     * Режим `keyMessage[12]` обязан быть 0..3: иначе исходник читает за границей таблиц.
     */
    fun decryptKey(keyMessage: ByteArray, ekey: ByteArray): ByteArray {
        require(keyMessage.size == HANDSHAKE_REQUEST_SIZE) { "keyMessage: ${keyMessage.size}" }
        require(ekey.size == EKEY_SIZE) { "ekey: ${ekey.size}" }
        require(u(keyMessage[12]) <= 3) { "mode: ${u(keyMessage[12])}" }
        return toBytes(playfairDecrypt(toInts(keyMessage), toInts(ekey)))
    }

    // --- playfair.c ---

    internal fun playfairDecrypt(message3: IntArray, cipherText: IntArray): IntArray {
        val chunk1 = 16
        val chunk2 = 56
        val sapKey = generateSessionKey(defaultSap, message3)
        val keySchedule = generateKeySchedule(sapKey)
        val blockIn = IntArray(16)
        zXor(cipherText, chunk2, blockIn, 0, 1)
        cycle(blockIn, keySchedule)
        val keyOut = IntArray(16) { blockIn[it] xor cipherText[chunk1 + it] }
        xXor(keyOut, 0, keyOut, 0, 1)
        zXor(keyOut, 0, keyOut, 0, 1)
        return keyOut
    }

    // --- omg_hax.c ---

    private fun zXor(input: IntArray, inOff: Int, out: IntArray, outOff: Int, blocks: Int) {
        for (j in 0 until blocks) for (i in 0 until 16) out[outOff + j * 16 + i] = input[inOff + j * 16 + i] xor T.zKey[i]
    }

    private fun xXor(input: IntArray, inOff: Int, out: IntArray, outOff: Int, blocks: Int) {
        for (j in 0 until blocks) for (i in 0 until 16) out[outOff + j * 16 + i] = input[inOff + j * 16 + i] xor T.xKey[i]
    }

    private fun tXor(input: IntArray, out: IntArray) {
        for (i in 0 until 16) out[i] = input[i] xor T.tKey[i]
    }

    private val INDEX_MANGLE = intArrayOf(0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36, 0x6C)

    private fun tableIndex(i: Int): Int = ((31 * i) % 0x28) shl 8

    private fun messageTableIndex(i: Int): Int = (97 * i % 144) shl 8

    private fun permuteTable2(i: Int): Int = ((71 * i) % 144) shl 8

    /**
     * Перестановка байтов блока, общая для всех «permute» исходника: новый байт d получается
     * из прежнего байта [SHUFFLE_SOURCE] d через свою таблицу `f(d, value)`.
     */
    private inline fun shuffle(block: IntArray, f: (Int, Int) -> Int) {
        val old = block.copyOf(16)
        for (d in 0 until 16) block[d] = f(d, old[SHUFFLE_SOURCE[d]])
    }

    private val SHUFFLE_SOURCE = intArrayOf(0, 13, 10, 7, 4, 1, 14, 11, 8, 5, 2, 15, 12, 9, 6, 3)

    /** Смещения (в блоках по 0x100) таблицы `table_s3` для байтов 0..15 в `permute_block_1`. */
    private val PERMUTE1_TABLE = intArrayOf(0, 5, 10, 15, 4, 9, 14, 3, 8, 13, 2, 7, 12, 1, 6, 11)

    private fun permuteBlock1(block: IntArray) =
        shuffle(block) { d, v -> T.tableS3[(PERMUTE1_TABLE[d] shl 8) + v] }

    private fun permuteBlock2(block: IntArray, round: Int) =
        shuffle(block) { d, v -> T.tableS4[permuteTable2(round * 16 + d) + v] }

    private fun word(b: IntArray, off: Int): Int =
        b[off] or (b[off + 1] shl 8) or (b[off + 2] shl 16) or (b[off + 3] shl 24)

    private fun setWord(b: IntArray, off: Int, w: Int) {
        b[off] = w and 0xff
        b[off + 1] = (w ushr 8) and 0xff
        b[off + 2] = (w ushr 16) and 0xff
        b[off + 3] = (w ushr 24) and 0xff
    }

    /** `generate_key_schedule`: 11 раундов по 4 слова, в массиве по индексу `round * 4 + n`. */
    internal fun generateKeySchedule(keyMaterial: IntArray): IntArray {
        val keySchedule = IntArray(44)
        val buffer = IntArray(16) // key_data[4] побайтно
        tXor(keyMaterial, buffer)
        var ti = 0
        for (round in 0 until 11) {
            keySchedule[round * 4] = word(buffer, 0)
            val table1 = tableIndex(ti)
            val table2 = tableIndex(ti + 1)
            val table3 = tableIndex(ti + 2)
            val table4 = tableIndex(ti + 3)
            ti += 4
            buffer[0] = buffer[0] xor T.tableS1[table1 + buffer[0x0d]] xor INDEX_MANGLE[round]
            buffer[1] = buffer[1] xor T.tableS1[table2 + buffer[0x0e]]
            buffer[2] = buffer[2] xor T.tableS1[table3 + buffer[0x0f]]
            buffer[3] = buffer[3] xor T.tableS1[table4 + buffer[0x0c]]
            keySchedule[round * 4 + 1] = word(buffer, 4)
            for (i in 4 until 8) buffer[i] = buffer[i] xor buffer[i - 4]
            keySchedule[round * 4 + 2] = word(buffer, 8)
            for (i in 8 until 12) buffer[i] = buffer[i] xor buffer[i - 4]
            keySchedule[round * 4 + 3] = word(buffer, 12)
            for (i in 12 until 16) buffer[i] = buffer[i] xor buffer[i - 4]
        }
        return keySchedule
    }

    /** `cycle`: блок 16 байт меняется на месте. */
    internal fun cycle(block: IntArray, keySchedule: IntArray) {
        for (n in 0 until 4) setWord(block, n * 4, word(block, n * 4) xor keySchedule[40 + n])
        permuteBlock1(block)
        for (round in 0 until 9) {
            for (n in 0 until 4) {
                val key = keySchedule[(9 - round) * 4 + n]
                val o = n * 4
                val w = T.tableS5[block[o + 3] xor ((key ushr 24) and 0xff)] xor
                    T.tableS6[block[o + 2] xor ((key ushr 16) and 0xff)] xor
                    T.tableS7[block[o + 1] xor ((key ushr 8) and 0xff)] xor
                    T.tableS8[block[o] xor (key and 0xff)]
                setWord(block, o, w)
            }
            permuteBlock2(block, 8 - round)
        }
        for (n in 0 until 4) setWord(block, n * 4, word(block, n * 4) xor keySchedule[n])
    }

    /** `decryptMessage`: 128 расшифрованных байтов из 164-байтного сообщения. */
    internal fun decryptMessage(messageIn: IntArray): IntArray {
        val decryptedMessage = IntArray(128)
        val buffer = IntArray(16)
        val mode = messageIn[12]
        // Исходник здесь ещё строит расписание ключей из initial_session_key, но не использует его.
        for (i in 0 until 8) {
            for (j in 0 until 16) {
                if (mode == 3) {
                    buffer[j] = messageIn[(0x80 - 0x10 * i) + j]
                } else if (mode == 2 || mode == 1 || mode == 0) {
                    buffer[j] = messageIn[(0x10 * (i + 1)) + j]
                }
            }
            for (j in 0 until 9) {
                val base = 0x80 - 0x10 * j
                shuffle(buffer) { d, v ->
                    T.tableS2[messageTableIndex(base + d) + v] xor T.messageKey[mode * 144 + base + d]
                }
                for (n in 0 until 4) {
                    val o = n * 4
                    setWord(
                        buffer, o,
                        T.tableS9[buffer[o]] xor T.tableS9[0x100 + buffer[o + 1]] xor
                            T.tableS9[0x200 + buffer[o + 2]] xor T.tableS9[0x300 + buffer[o + 3]],
                    )
                }
            }
            shuffle(buffer) { d, v -> T.tableS10[(d shl 8) + v] }
            if (mode == 2 || mode == 1 || mode == 0) {
                val out = 0x10 * i
                for (k in 0 until 16) {
                    val prev = if (i > 0) messageIn[0x10 * i + k] else T.messageIv[mode * 16 + k]
                    decryptedMessage[out + k] = buffer[k] xor prev
                }
            } else {
                val out = 0x70 - 0x10 * i
                for (k in 0 until 16) {
                    val prev = if (i < 7) messageIn[0x70 - 0x10 * i + k] else T.messageIv[mode * 16 + k]
                    decryptedMessage[out + k] = buffer[k] xor prev
                }
            }
        }
        return decryptedMessage
    }

    private val STATIC_SOURCE_1 = hex("fa9cad4d4b68268c7ff38899de922e951e")
    private val STATIC_SOURCE_2 = hex(
        "ec4e275efdf2e83097ae70fbe0003f1c39800000000000000000000000000000000000000000001009000000000000",
    )
    private val INITIAL_SESSION_KEY = hex("dcdcf3b90b74dcfb867ff76016729051")

    /** `default_sap` целиком (276 байт); в ключ идут байты 0x80..0xff. */
    internal val defaultSap = hex(
        "0003000000000000797979797979797979797979797979797979797979797979" +
            "7979797979797979797979790000000000000000000000000000000000000000" +
            "7979797979797979797979797979797979797979797979797979797979797979" +
            "7979797900000000000000000000000000000000000000000000000002030253" +
            "0001cc342a5e5b1a6773c20e21b8224df862481864ef810aae2e3703c8819c23" +
            "539de5f5d749bc5b7a266c496283ce7f03937ae1f616de0c15ff338ccaffb09e" +
            "aabbe40f5d5f558fb97f1731f8f7da60a0ec6579c33ea98312c3b67135a6694f" +
            "f82305d9ba5c615fa254d2b1834583cee42d4426c835a7a5f6c8421c0da3f1c7" +
            "0050f2e517f8d0fa778dfb828d40c78e941e1e1e",
    )

    /** `generate_session_key`. */
    internal fun generateSessionKey(oldSap: IntArray, messageIn: IntArray): IntArray {
        val decryptedMessage = decryptMessage(messageIn)
        val newSap = IntArray(320)
        STATIC_SOURCE_1.copyInto(newSap, 0x000, 0, 0x11)
        decryptedMessage.copyInto(newSap, 0x011, 0, 0x80)
        oldSap.copyInto(newSap, 0x091, 0x80, 0x100)
        STATIC_SOURCE_2.copyInto(newSap, 0x111, 0, 0x2f)
        var sessionKey = INITIAL_SESSION_KEY.copyOf()
        for (round in 0 until 5) {
            val base = newSap.copyOfRange(round * 64, round * 64 + 64)
            val md5 = modifiedMd5(base, sessionKey)
            sessionKey = sapHash(base)
            for (n in 0 until 4) setWord(sessionKey, n * 4, word(sessionKey, n * 4) + word(md5, n * 4))
        }
        for (i in 0 until 16 step 4) {
            var t = sessionKey[i]; sessionKey[i] = sessionKey[i + 3]; sessionKey[i + 3] = t
            t = sessionKey[i + 1]; sessionKey[i + 1] = sessionKey[i + 2]; sessionKey[i + 2] = t
        }
        for (i in 0 until 16) sessionKey[i] = sessionKey[i] xor 121
        return sessionKey
    }

    // --- modified_md5.c ---

    private val SHIFT = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    /** `(int)(long long)((1LL << 32) * fabs(sin(i + 1)))`: обычные постоянные MD5, сверены с C. */
    private val SINE = intArrayOf(
        0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db, 0xc1bdceee.toInt(),
        0xf57c0faf.toInt(), 0x4787c62a, 0xa8304613.toInt(), 0xfd469501.toInt(),
        0x698098d8, 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
        0x6b901122, 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821,
        0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51, 0xe9b6c7aa.toInt(),
        0xd62f105d.toInt(), 0x02441453, 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
        0x21e1cde6, 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed,
        0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9, 0x8d2a4c8a.toInt(),
        0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122, 0xfde5380c.toInt(),
        0xa4beea44.toInt(), 0x4bdecfa9, 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
        0x289b7ec6, 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05,
        0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8, 0xc4ac5665.toInt(),
        0xf4292244.toInt(), 0x432aff97, 0xab9423a7.toInt(), 0xfc93a039.toInt(),
        0x655b59c3, 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
        0x6fa87e4f, 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1,
        0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb, 0xeb86d391.toInt(),
    )

    private fun swapWords(block: IntArray, a: Int, b: Int) {
        for (k in 0 until 4) {
            val c = block[a * 4 + k]; block[a * 4 + k] = block[b * 4 + k]; block[b * 4 + k] = c
        }
    }

    /** `modified_md5`: 64 байта блока и 16 байт ключа → 16 байт. */
    internal fun modifiedMd5(originalBlockIn: IntArray, keyIn: IntArray): IntArray {
        val blockIn = originalBlockIn.copyOf(64)
        var a = word(keyIn, 0)
        var b = word(keyIn, 4)
        var c = word(keyIn, 8)
        var d = word(keyIn, 12)
        for (i in 0 until 64) {
            val j = when {
                i < 16 -> i
                i < 32 -> (5 * i + 1) % 16
                i < 48 -> (3 * i + 5) % 16
                else -> 7 * i % 16
            }
            val input = (blockIn[4 * j] shl 24) or (blockIn[4 * j + 1] shl 16) or
                (blockIn[4 * j + 2] shl 8) or blockIn[4 * j + 3]
            var z = a + input + SINE[i]
            z = when {
                i < 16 -> Integer.rotateLeft(z + ((b and c) or (b.inv() and d)), SHIFT[i])
                i < 32 -> Integer.rotateLeft(z + ((b and d) or (c and d.inv())), SHIFT[i])
                i < 48 -> Integer.rotateLeft(z + (b xor c xor d), SHIFT[i])
                else -> Integer.rotateLeft(z + (c xor (b or d.inv())), SHIFT[i])
            }
            z += b
            val tmp = d
            d = c
            c = b
            b = z
            a = tmp
            if (i == 31) {
                swapWords(blockIn, a and 15, b and 15)
                swapWords(blockIn, c and 15, d and 15)
                swapWords(blockIn, (a ushr 4) and 15, (b ushr 4) and 15)
                swapWords(blockIn, (a ushr 8) and 15, (b ushr 8) and 15)
                swapWords(blockIn, (a ushr 12) and 15, (b ushr 12) and 15)
            }
        }
        val out = IntArray(16)
        setWord(out, 0, word(keyIn, 0) + a)
        setWord(out, 4, word(keyIn, 4) + b)
        setWord(out, 8, word(keyIn, 8) + c)
        setWord(out, 12, word(keyIn, 12) + d)
        return out
    }

    // --- sap_hash.c ---

    private fun rol8(input: Int, count: Int): Int {
        val x = input and 0xff
        return ((x shl count) and 0xff) or (x ushr (8 - count))
    }

    private fun rol8x(input: Int, count: Int): Int {
        val x = input and 0xff
        return (x shl count) or (x ushr (8 - count))
    }

    private val I0_INDEX = intArrayOf(18, 22, 23, 0, 5, 19, 32, 31, 10, 21, 30)

    private fun urem(a: Int, b: Int): Int = Integer.remainderUnsigned(a, b)

    /** `sap_hash`: 64 байта → 16 байт. */
    internal fun sapHash(blockIn: IntArray): IntArray {
        val buffer0 = hex("965fc653f846cc18dfbeb2f838d7ec2203d1208f")
        val buffer1 = IntArray(210)
        val buffer2 = hex("4354627a18c3d6b39a56f61c143f0c1d3b3683b139514aaa093efe44afdec3209d423a")
        val buffer3 = IntArray(132)
        val buffer4 = hex("ed25d1bbbc279f02a2a911000cb352c0bde31b49c7")
        for (i in 0 until 210) {
            // Слово читается в порядке little-endian, затем байты берутся со старшего.
            buffer1[i] = blockIn[4 * ((i % 64) shr 2) + (3 - (i % 4))]
        }
        for (i in 0 until 840) {
            // Остатки беззнаковые 32-битные, как в исходнике.
            val x = buffer1[urem(i - 155, 210)]
            val y = buffer1[urem(i - 57, 210)]
            val z = buffer1[urem(i - 13, 210)]
            val w = buffer1[urem(i, 210)]
            buffer1[i % 210] = (rol8(y, 5) + (rol8(z, 3) xor w) - rol8(x, 7)) and 0xff
        }
        garble(buffer0, buffer1, buffer2, buffer3, buffer4)
        val keyOut = IntArray(16) { 0xE1 }
        for (i in 0 until 11) {
            if (i == 3) {
                keyOut[i] = 0x3d
            } else {
                keyOut[i] = (keyOut[i] + buffer3[I0_INDEX[i] * 4]) and 0xff
            }
        }
        for (i in 0 until 20) keyOut[i % 16] = keyOut[i % 16] xor buffer0[i]
        for (i in 0 until 35) keyOut[i % 16] = keyOut[i % 16] xor buffer2[i]
        for (i in 0 until 210) keyOut[i % 16] = keyOut[i % 16] xor buffer1[i]
        for (j in 0 until 16) {
            for (i in 0 until 16) {
                val x = keyOut[urem(i - 7, 16)]
                val y = keyOut[i % 16]
                val z = keyOut[urem(i - 37, 16)]
                val w = keyOut[urem(i - 177, 16)]
                keyOut[i] = rol8(x, 1) xor y xor rol8(z, 6) xor rol8(w, 5)
            }
        }
        return keyOut
    }

    // --- hand_garble.c ---

    private fun weirdRor8(input: Int, count: Int): Int {
        if (count == 0) return 0
        val x = input and 0xff
        return ((x ushr count) and 0xff) or (x shl (8 - count))
    }

    private fun weirdRol8(input: Int, count: Int): Int {
        if (count == 0) return 0
        val x = input and 0xff
        return ((x shl count) and 0xff) or (x ushr (8 - count))
    }

    private fun weirdRol32(input: Int, count: Int): Int {
        if (count == 0) return 0
        val x = input and 0xff
        return (x shl count) xor (x ushr (8 - count))
    }

    /**
     * `garble`, перенесён построчно программой-переводчиком выражений C с явными скобками:
     * приоритеты C (`&` выше `^` выше `|`, сдвиги ниже `+`) в Kotlin расставлены скобками.
     * Переменные `unsigned int` — Int (сложение и умножение по модулю 2^32 совпадают); все
     * деления и остатки здесь над неотрицательными значениями, кроме одного беззнакового
     * деления, перенесённого через [Integer.divideUnsigned]. Запись в байт — `and 0xff`.
     */
    @Suppress("LocalVariableName", "VariableNaming", "ktlint")
    private fun garble(b0: IntArray, b1: IntArray, b2: IntArray, b3: IntArray, b4: IntArray) {
        var tmp: Int; var tmp2: Int; var tmp3: Int
        var A: Int; var B: Int; var C: Int; var D: Int; var E: Int; var M: Int; var J: Int; var G: Int; var F: Int; var H: Int; var K: Int; var R: Int; var S: Int; var T: Int; var U: Int; var V: Int; var W: Int; var X: Int; var Y: Int; var Z: Int
        b2[12] = (0x14 + (((b1[64] and 92) or ((b1[99] / 3) and 35)) and b4[(rol8x(b4[(b1[206] % 21)], 4) % 21)])) and 0xff
        b1[4] = (((b1[99] / 5) * (b1[99] / 5)) * 2) and 0xff
        b2[34] = 0xb8 and 0xff
        b1[153] = (b1[153] xor ((b2[(b1[203] % 35)] * b2[(b1[203] % 35)]) * b1[190])) and 0xff
        b0[3] = (b0[3] - (((b4[(b1[205] % 21)] ushr 1) and 80) or 0xe6440)) and 0xff
        b0[16] = 0x93 and 0xff
        b0[13] = 0x62 and 0xff
        b1[33] = (b1[33] - (b4[(b1[36] % 21)] and 0xf6)) and 0xff
        tmp2 = b2[(b1[67] % 35)]
        b2[12] = 0x07 and 0xff
        tmp = b0[(b1[181] % 20)]
        b1[2] = (b1[2] - 3136) and 0xff
        b0[19] = b4[(b1[58] % 21)] and 0xff
        b3[0] = (92 - b2[(b1[32] % 35)]) and 0xff
        b3[4] = (b2[(b1[15] % 35)] + 0x9e) and 0xff
        b1[34] = (b1[34] + (b4[(((b2[(b1[15] % 35)] + 0x9e) and 0xff) % 21)] / 5)) and 0xff
        b0[19] = (b0[19] + (0xfffffee6.toInt() - ((b0[(b3[4] % 20)] ushr 1) and 102))) and 0xff
        b1[15] = ((3 * (((b1[72] ushr (b4[(b1[190] % 21)] and 7)) xor (b1[72] shl ((7 - (b4[(b1[190] % 21)] - 1)) and 7))) - (3 * b4[(b1[126] % 21)]))) xor b1[15]) and 0xff
        b0[15] = (b0[15] xor ((b2[(b1[181] % 35)] * b2[(b1[181] % 35)]) * b2[(b1[181] % 35)])) and 0xff
        b2[4] = (b2[4] xor (b1[202] / 3)) and 0xff
        A = (92 - b0[(b3[0] % 20)])
        E = (((A and 0xc6) or ((b1[105]).inv() and 0xc6)) or (A and (b1[105]).inv()))
        b2[1] = (b2[1] + ((E * E) * E)) and 0xff
        b0[19] = (b0[19] xor (((224 or (b4[(b1[92] % 21)] and 27)) * b2[(b1[41] % 35)]) / 3)) and 0xff
        b1[140] = (b1[140] + weirdRor8(92, (b1[5] and 7))) and 0xff
        b2[12] = (b2[12] + (((((b1[4]).inv() xor b2[(b1[12] % 35)]) or b1[182]) and 192) or (((b1[4]).inv() xor b2[(b1[12] % 35)]) and b1[182]))) and 0xff
        b1[36] = (b1[36] + 125) and 0xff
        b1[124] = rol8x(((((74 and b1[138]) or ((74 or b1[138]) and b0[15])) and b0[(b1[43] % 20)]) or ((((74 and b1[138]) or ((74 or b1[138]) and b0[15])) or b0[(b1[43] % 20)]) and 95)), 4) and 0xff
        b3[8] = ((((b0[(b3[4] % 20)] and 95) and ((b4[(b1[68] % 21)] and 46) shl 1)) or 16) xor 92) and 0xff
        A = (b1[177] + b4[(b1[79] % 21)])
        D = ((((A ushr 1) or ((3 * b1[148]) / 5)) and b2[1]) or ((A ushr 1) and ((3 * b1[148]) / 5)))
        b3[12] = ((-34) - D) and 0xff
        A = (8 - (b2[22] and 7))
        B = (b1[33] ushr (A and 7))
        C = (b1[33] shl (b2[22] and 7))
        b2[16] = (b2[16] + ((((b2[(b3[0] % 35)] and 159) or b0[(b3[4] % 20)]) or 8) - ((B xor C) or 128))) and 0xff
        b0[14] = (b0[14] xor b2[(b3[12] % 35)]) and 0xff
        A = weirdRol8(b4[(b0[(b1[201] % 20)] % 21)], ((b2[(b1[112] % 35)] shl 1) and 7))
        D = ((b0[(b1[208] % 20)] and 131) or (b0[(b1[164] % 20)] and 124))
        b1[19] = (b1[19] + ((A and (D / 5)) or ((A or (D / 5)) and 37))) and 0xff
        b2[8] = weirdRor8(140, (((b4[(b1[45] % 21)] + 92) * (b4[(b1[45] % 21)] + 92)) and 7)) and 0xff
        b1[190] = 56 and 0xff
        b2[8] = (b2[8] xor b3[0]) and 0xff
        b1[53] = (((b0[(b1[83] % 20)] or 204) / 5)).inv() and 0xff
        b0[13] = (b0[13] + b0[(b1[41] % 20)]) and 0xff
        b0[10] = (((b2[(b3[0] % 35)] and b1[2]) or ((b2[(b3[0] % 35)] or b1[2]) and b3[12])) / 15) and 0xff
        A = ((((56 or (b4[(b1[2] % 21)] and 68)) or b2[(b3[8] % 35)]) and 42) or (((b4[(b1[2] % 21)] and 68) or 56) and b2[(b3[8] % 35)]))
        b3[16] = ((A * A) + 110) and 0xff
        b3[20] = (202 - b3[16]) and 0xff
        b3[24] = b1[151] and 0xff
        b2[13] = (b2[13] xor b4[(b3[0] % 21)]) and 0xff
        B = (((b2[(b1[179] % 35)] - 38) and 177) or (b3[12] and 177))
        C = ((b2[(b1[179] % 35)] - 38) and b3[12])
        b3[28] = (30 + ((B or C) * (B or C))) and 0xff
        b3[32] = (b3[28] + 62) and 0xff
        A = (((b3[20] + (b3[0] and 74)) or (b4[(b3[0] % 21)]).inv()) and 121)
        B = ((b3[20] + (b3[0] and 74)) and (b4[(b3[0] % 21)]).inv())
        tmp3 = (A or B)
        C = (((((A or B) xor 0xffffffa6.toInt()) or b3[0]) and 4) or (((A or B) xor 0xffffffa6.toInt()) and b3[0]))
        b1[47] = ((b2[(b1[89] % 35)] + C) xor b1[47]) and 0xff
        b3[36] = (((rol8(((tmp and 179) + 68), 2) and b0[3]) or (tmp2 and (b0[3]).inv())) - 15) and 0xff
        b1[123] = (b1[123] xor 221) and 0xff
        A = ((b4[(b3[0] % 21)] / 3) - b2[(b3[4] % 35)])
        C = ((((b3[0] and 163) + 92) and 246) or (b3[0] and 92))
        E = (((C or b3[24]) and 54) or (C and b3[24]))
        b3[40] = (A - E) and 0xff
        b3[44] = ((tmp3 xor 81) xor (((b3[0] ushr 1) and 101) + 26)) and 0xff
        b3[48] = (b2[(b3[4] % 35)] and 27) and 0xff
        b3[52] = 27 and 0xff
        b3[56] = 199 and 0xff
        b3[64] = (b3[4] + (((((((b3[40] or b3[24]) and 177) or (b3[40] and b3[24])) and (((b4[(b3[0] % 20)] and 177) or 176) or (b4[(b3[0] % 21)] and (3).inv()))) or ((((b3[40] and b3[24]) or ((b3[40] or b3[24]) and 177)) and 199) or ((((b4[(b3[0] % 21)] and 1) + 176) or (b4[(b3[0] % 21)] and (3).inv())) and b3[56]))) and (b3[52]).inv()) or b3[48])) and 0xff
        b2[33] = (b2[33] xor b1[26]) and 0xff
        b1[106] = (b1[106] xor (b3[20] xor 133)) and 0xff
        b2[30] = (((b3[64] / 3) - (275 or (b3[0] and 247))) xor b0[(b1[122] % 20)]) and 0xff
        b1[22] = ((b2[(b1[90] % 35)] and 95) or 68) and 0xff
        A = ((b4[(b3[36] % 21)] and 184) or (b2[(b3[44] % 35)] and (184).inv()))
        b2[18] = (b2[18] + (((A * A) * A) ushr 1)) and 0xff
        b2[5] = (b2[5] - b4[(b1[92] % 21)]) and 0xff
        A = ((((b1[41] and (24).inv()) or (b2[(b1[183] % 35)] and 24)) and (b3[16] + 53)) or (b3[20] and b2[(b3[20] % 35)]))
        B = ((b1[17] and (b3[44]).inv()) or (b0[(b1[59] % 20)] and b3[44]))
        b2[18] = (b2[18] xor (A * B)) and 0xff
        A = (weirdRor8(b1[11], (b2[(b1[28] % 35)] and 7)) and 7)
        B = ((((b0[(b1[93] % 20)] and (b0[14]).inv()) or (b0[14] and 150)) and (28).inv()) or (b1[7] and 28))
        b2[22] = (((((B or weirdRol8(b2[(b3[0] % 35)], A)) and b2[33]) or (B and weirdRol8(b2[(b3[0] % 35)], A))) + 74) and 0xff) and 0xff
        A = b4[((b0[(b1[39] % 20)] xor 217) % 21)]
        b0[15] = (b0[15] - (((((b3[20] or b3[0]) and 214) or (b3[20] and b3[0])) and A) or (((((b3[20] or b3[0]) and 214) or (b3[20] and b3[0])) or A) and b3[32]))) and 0xff
        B = (((((b2[(b1[57] % 35)] and b0[(b3[64] % 20)]) or ((b0[(b3[64] % 20)] or b2[(b1[57] % 35)]) and 95)) or (b3[64] and 45)) or 82) and 32)
        C = (((b2[(b1[57] % 35)] and b0[(b3[64] % 20)]) or ((b2[(b1[57] % 35)] or b0[(b3[64] % 20)]) and 95)) and ((b3[64] and 45) or 82))
        D = ((((b3[0] / 3) - (b3[64] or b1[22])) xor (b3[28] + 62)) xor (B or C))
        T = b0[((D and 0xff) % 20)]
        b3[68] = ((((b0[(b1[99] % 20)] * b0[(b1[99] % 20)]) * b0[(b1[99] % 20)]) * b0[(b1[99] % 20)]) or b2[(b3[64] % 35)]) and 0xff
        U = b0[(b1[50] % 20)]
        W = b2[(b1[138] % 35)]
        X = b4[(b1[39] % 21)]
        Y = b0[(b1[4] % 20)]
        Z = b4[(b1[202] % 21)]
        V = b0[(b1[151] % 20)]
        S = b2[(b1[14] % 35)]
        R = b0[(b1[145] % 20)]
        A = ((b2[(b3[68] % 35)] and b0[(b1[209] % 20)]) or ((b2[(b3[68] % 35)] or b0[(b1[209] % 20)]) and 24))
        B = weirdRol8(b4[(b1[127] % 21)], (b2[(b3[68] % 35)] and 7))
        C = ((A and b0[10]) or (B and (b0[10]).inv()))
        D = (7 xor (b4[(b2[(b3[36] % 35)] % 21)] shl 1))
        b3[72] = ((C and 71) or (D and (71).inv())) and 0xff
        b2[2] = (b2[2] + ((((b0[(b3[20] % 20)] shl 1) and 159) or (b4[(b1[190] % 21)] and (159).inv())) and ((((b4[(b3[64] % 21)] and 110) or (b0[(b1[25] % 20)] and (110).inv())) and (150).inv()) or (b1[25] and 150)))) and 0xff
        b2[14] = (b2[14] - (((b2[(b3[20] % 35)] and (b3[72] xor b2[(b1[100] % 35)])) and (34).inv()) or (b1[97] and 34))) and 0xff
        b0[17] = 115 and 0xff
        b1[23] = (b1[23] xor ((((((b4[(b1[17] % 21)] or b0[(b3[20] % 20)]) and b3[72]) or (b4[(b1[17] % 21)] and b0[(b3[20] % 20)])) and (b1[50] / 3)) or (((((b4[(b1[17] % 21)] or b0[(b3[20] % 20)]) and b3[72]) or (b4[(b1[17] % 21)] and b0[(b3[20] % 20)])) or (b1[50] / 3)) and 246)) shl 1)) and 0xff
        b0[13] = ((((((b0[(b3[40] % 20)] or b1[10]) and 82) or (b0[(b3[40] % 20)] and b1[10])) and 209) or ((b0[(b1[39] % 20)] shl 1) and 46)) ushr 1) and 0xff
        b2[33] = (b2[33] - (b1[113] and 9)) and 0xff
        b2[28] = (b2[28] - ((((2 or (b1[110] and 222)) ushr 1) and (223).inv()) or (b3[20] and 223))) and 0xff
        J = weirdRol8((V or Z), (U and 7))
        A = ((b2[16] and T) or (W and (b2[16]).inv()))
        B = ((b1[33] and 17) or (X and (17).inv()))
        E = (((Y or ((A + B) / 5)) and 147) or (Y and ((A + B) / 5)))
        M = ((b3[40] and b4[((((b3[8] + J) + E) and 0xff) % 21)]) or ((b3[40] or b4[((((b3[8] + J) + E) and 0xff) % 21)]) and b2[23]))
        b0[15] = (((((b4[(b3[20] % 21)] - 48) and (b1[184]).inv()) or ((b4[(b3[20] % 21)] - 48) and 189)) or (189 and (b1[184]).inv())) and ((M * M) * M)) and 0xff
        b2[22] = (b2[22] + b1[183]) and 0xff
        b3[76] = ((3 * b4[(b1[1] % 21)]) xor b3[0]) and 0xff
        A = b2[(((b3[8] + (J + E)) and 0xff) % 35)]
        F = ((((b4[(b1[178] % 21)] and A) or ((b4[(b1[178] % 21)] or A) and 209)) * b0[(b1[13] % 20)]) * (b4[(b1[26] % 21)] ushr 1))
        G = ((((F + 0x733ffff9) * 198) - ((((F + 0x733ffff9) * 396) + 212) and 212)) + 85)
        b3[80] = (((b3[36] + (G xor 148)) + ((G xor 107) shl 1)) - 127) and 0xff
        b3[84] = ((b2[(b3[64] % 35)] and 245) or (b2[(b3[20] % 35)] and 10)) and 0xff
        A = (b0[(b3[68] % 20)] or 81)
        b2[18] = (b2[18] - ((((A * A) * A) and (b0[15]).inv()) or ((b3[80] / 15) and b0[15]))) and 0xff
        b3[88] = ((((b3[8] + J) + E) - b0[(b1[160] % 20)]) + (b4[(b0[((((b3[8] + J) + E) and 255) % 20)] % 21)] / 3)) and 0xff
        B = (((R xor b3[72]) and (198).inv()) or ((S * S) and 198))
        F = ((b4[(b1[69] % 21)] and b1[172]) or ((b4[(b1[69] % 21)] or b1[172]) and ((b3[12] - B) + 77)))
        b0[16] = (147 - ((b3[72] and ((F and 251) or 1)) or (((F and 250) or b3[72]) and 198))) and 0xff
        C = (((b4[(b1[168] % 21)] and b0[(b1[29] % 20)]) and 7) or ((b4[(b1[168] % 21)] or b0[(b1[29] % 20)]) and 6))
        F = ((b4[(b1[155] % 21)] and b1[105]) or ((b4[(b1[155] % 21)] or b1[105]) and 141))
        b0[3] = (b0[3] - b4[(weirdRol32(F, C) % 21)]) and 0xff
        b1[5] = (weirdRor8(b0[12], ((b0[(b1[61] % 20)] / 5) and 7)) xor Integer.divideUnsigned(((b2[(b3[84] % 35)]).inv() and 0xffffffff.toInt()), 5)) and 0xff
        b1[198] = (b1[198] + b1[3]) and 0xff
        A = (162 or b2[(b3[64] % 35)])
        b1[164] = (b1[164] + ((A * A) / 5)) and 0xff
        G = weirdRor8(139, (b3[80] and 7))
        C = ((((b4[(b3[64] % 21)] * b4[(b3[64] % 21)]) * b4[(b3[64] % 21)]) and 95) or (b0[(b3[40] % 20)] and (95).inv()))
        b3[92] = ((((G and 12) or (b0[(b3[20] % 20)] and 12)) or (G and b0[(b3[20] % 20)])) or C) and 0xff
        b2[12] = (b2[12] + ((((b1[103] and 32) or (b3[92] and (b1[103] or 60))) or 16) / 3)) and 0xff
        b3[96] = b1[143] and 0xff
        b3[100] = 27 and 0xff
        b3[104] = ((((b3[40] and (b2[8]).inv()) or (b1[35] and b2[8])) and b3[64]) xor 119) and 0xff
        b3[108] = (238 and ((((b3[40] and (b2[8]).inv()) or (b1[35] and b2[8])) and b3[64]) shl 1)) and 0xff
        b3[112] = (((b3[64]).inv() and (b3[84] / 3)) xor 49) and 0xff
        b3[116] = (98 and (((b3[64]).inv() and (b3[84] / 3)) shl 1)) and 0xff
        A = ((b1[35] and b2[8]) or (b3[40] and (b2[8]).inv()))
        B = ((A and b3[64]) or ((b3[84] / 3) and (b3[64]).inv()))
        b1[143] = (b3[96] - ((B and (86 + ((b1[172] and 64) ushr 1))) or (((((b1[172] and 65) ushr 1) xor 86) or (((b3[64]).inv() and (b3[84] / 3)) or (((b3[40] and (b2[8]).inv()) or (b1[35] and b2[8])) and b3[64]))) and b3[100]))) and 0xff
        b2[29] = 162 and 0xff
        A = (((b4[(b3[88] % 21)] and 160) or (b0[(b1[125] % 20)] and 95)) ushr 1)
        B = (b2[(b1[149] % 35)] xor (b1[43] * b1[43]))
        b0[15] = (b0[15] + ((B and A) or ((A or B) and 115))) and 0xff
        b3[120] = (b3[64] - b0[(b3[40] % 20)]) and 0xff
        b1[95] = b4[(b3[20] % 21)] and 0xff
        A = weirdRor8(b2[(b3[80] % 35)], (((b2[(b1[17] % 35)] * b2[(b1[17] % 35)]) * b2[(b1[17] % 35)]) and 7))
        b0[7] = (b0[7] - (A * A)) and 0xff
        b2[8] = ((b2[8] - b1[184]) + ((b4[(b1[202] % 21)] * b4[(b1[202] % 21)]) * b4[(b1[202] % 21)])) and 0xff
        b0[16] = ((b2[(b1[102] % 35)] shl 1) and 132) and 0xff
        b3[124] = ((b4[(b3[40] % 21)] ushr 1) xor b3[68]) and 0xff
        b0[7] = (b0[7] - (b0[(b1[191] % 20)] - (((b4[(b1[80] % 21)] shl 1) and (177).inv()) or (b4[(b4[(b3[88] % 21)] % 21)] and 177)))) and 0xff
        b0[6] = b0[(b1[119] % 20)] and 0xff
        A = ((b4[(b1[190] % 21)] and (209).inv()) or (b1[118] and 209))
        B = (b0[(b3[120] % 20)] * b0[(b3[120] % 20)])
        b0[12] = ((b0[(b3[84] % 20)] xor (b2[(b1[71] % 35)] + b2[(b1[15] % 35)])) and ((A and B) or ((A or B) and 27))) and 0xff
        B = ((b1[32] and b2[(b3[88] % 35)]) or ((b1[32] or b2[(b3[88] % 35)]) and 23))
        D = (((b4[(b1[57] % 21)] * 231) and 169) or (B and 86))
        F = ((((b0[(b1[82] % 20)] and (29).inv()) or (b4[(b3[124] % 21)] and 29)) and 190) or (b4[((D / 5) % 21)] and (190).inv()))
        H = ((b0[(b3[40] % 20)] * b0[(b3[40] % 20)]) * b0[(b3[40] % 20)])
        K = (((H and b1[82]) or (H and 92)) or (b1[82] and 92))
        b3[128] = (((F and K) or ((F or K) and 192)) xor (D / 5)) and 0xff
        b2[25] = (b2[25] xor (((b0[(b3[120] % 20)] shl 1) * b1[5]) - (weirdRol8(b3[76], (b4[(b3[124] % 21)] and 7)) and (b3[20] + 110)))) and 0xff
    }

    // --- общее ---

    private fun u(b: Byte): Int = b.toInt() and 0xff

    private fun toInts(b: ByteArray): IntArray = IntArray(b.size) { b[it].toInt() and 0xff }

    private fun toBytes(a: IntArray): ByteArray = ByteArray(a.size) { a[it].toByte() }

    private fun hex(s: String): IntArray = IntArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16) }
}
