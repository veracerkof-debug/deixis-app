package app.deixis.desktop

import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * Свой виртуальный экран на Маке без драйвера: система (macOS 11+) создаёт настоящий дисплей 1920×1080
 * через CGVirtualDisplay — тот же механизм, что у открытой программы DeskPad (MIT); её код не копировался,
 * вызовы — по заголовкам CoreGraphics. Программы видят второй монитор и выводят туда «проектор», Deixis
 * снимает его (avfoundation) и раздаёт. Механизм не описан Apple официально, поэтому [create] может не удаться
 * на будущих версиях macOS — тогда остаётся HDMI-заглушка. Дисплей живёт, пока жив объект; убирается при выходе.
 */
object MacVirtualScreen {
    const val WIDTH = 1920
    const val HEIGHT = 1080

    val supported: Boolean get() = System.getProperty("os.name").orEmpty().lowercase().contains("mac") &&
        runCatching { objc.getFunction("objc_getClass").invokePointer(arrayOf("CGVirtualDisplay")) != null }.getOrDefault(false)

    /** id созданного дисплея (CGDirectDisplayID) или null. */
    @Volatile var displayId: Int? = null
        private set

    private var display: Pointer? = null
    private val objc by lazy { NativeLibrary.getInstance("objc") }
    private val send by lazy { objc.getFunction("objc_msgSend") }
    private val cg by lazy { NativeLibrary.getInstance("CoreGraphics") }

    private fun cls(name: String): Pointer = objc.getFunction("objc_getClass").invokePointer(arrayOf(name)) ?: error("нет класса $name")
    private fun sel(name: String): Pointer = objc.getFunction("sel_registerName").invokePointer(arrayOf(name))
    private fun msg(target: Pointer, selector: String, vararg args: Any?): Pointer? = send.invokePointer(arrayOf(target, sel(selector), *args))
    private fun msgVoid(target: Pointer, selector: String, vararg args: Any?) = send.invokeVoid(arrayOf(target, sel(selector), *args))
    private fun new(name: String): Pointer = msg(msg(cls(name), "alloc")!!, "init") ?: error("$name не создан")

    @Structure.FieldOrder("width", "height")
    class CGSize(@JvmField var width: Double = 0.0, @JvmField var height: Double = 0.0) : Structure(), Structure.ByValue

    @Synchronized
    fun create(): Result<Int> = runCatching {
        displayId?.let { return@runCatching it }
        val d = new("CGVirtualDisplayDescriptor")
        val queue = NativeLibrary.getInstance("System").getFunction("dispatch_queue_create").invokePointer(arrayOf("app.deixis.virtual", null))
        msgVoid(d, "setQueue:", queue)
        msgVoid(d, "setName:", msg(cls("NSString"), "stringWithUTF8String:", "Deixis"))
        msgVoid(d, "setMaxPixelsWide:", WIDTH)
        msgVoid(d, "setMaxPixelsHigh:", HEIGHT)
        // Размер в миллиметрах — как у монитора 24″: системе он нужен для масштаба.
        msgVoid(d, "setSizeInMillimeters:", CGSize(531.0, 299.0))
        msgVoid(d, "setVendorID:", 0xDE1)
        msgVoid(d, "setProductID:", 0x1)
        msgVoid(d, "setSerialNum:", 0x1)
        val vd = msg(msg(cls("CGVirtualDisplay"), "alloc")!!, "initWithDescriptor:", d) ?: error("система не создала дисплей")
        val settings = new("CGVirtualDisplaySettings")
        msgVoid(settings, "setHiDPI:", 0)
        val mode = send.invokePointer(arrayOf(msg(cls("CGVirtualDisplayMode"), "alloc"), sel("initWithWidth:height:refreshRate:"), WIDTH.toLong(), HEIGHT.toLong(), 30.0))
            ?: error("режим не создан")
        msgVoid(settings, "setModes:", msg(cls("NSArray"), "arrayWithObject:", mode))
        val ok = send.invokeInt(arrayOf(vd, sel("applySettings:"), settings)) and 0xFF
        if (ok == 0) error("система не приняла режим 1920×1080")
        display = vd
        val id = send.invokeInt(arrayOf(vd, sel("displayID")))
        displayId = id
        id
    }

    /** Номер дисплея для avfoundation («Capture screen N»): порядок — как в списке активных дисплеев системы. */
    fun captureIndex(id: Int): Int = runCatching {
        val ids = IntArray(16)
        val count = IntArray(1)
        cg.getFunction("CGGetActiveDisplayList").invokeInt(arrayOf(16, ids, count))
        ids.take(count[0]).indexOf(id).takeIf { it >= 0 }
    }.getOrNull() ?: 0

    @Synchronized
    fun remove() {
        val vd = display ?: return
        runCatching { msgVoid(vd, "release") }
        display = null
        displayId = null
    }
}
