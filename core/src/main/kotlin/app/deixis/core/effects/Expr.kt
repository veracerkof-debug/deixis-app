package app.deixis.core.effects

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Ошибка в формуле: [message] — понятная автору фраза (её видно в редакторе и нейросети). */
class ExprException(message: String) : Exception(message)

fun interface ExprNode {
    fun eval(v: (String) -> Double): Double
}

/**
 * Безопасная формула для привязки параметра (`base*(0.8+0.4*sin(i*0.9+t))`). Это не язык программирования:
 * только числа, переменные, `+ - * / % ^`, сравнения и фиксированный набор функций. Нет циклов, присваиваний,
 * доступа к файлам и сети. Размер ограничен ([MAX_LENGTH] знаков, [MAX_NODES] узлов), поэтому время
 * вычисления известно заранее, даже если формулу написала нейросеть.
 *
 * Деление на ноль и остаток от деления на ноль дают 0; результат не конечное число — [Double.NaN]
 * (вызывающий возвращается к базовому значению параметра).
 */
class Expr private constructor(val source: String, private val root: ExprNode, val nodes: Int) {
    fun eval(vars: (String) -> Double): Double {
        val r = root.eval(vars)
        return if (r.isFinite()) r else Double.NaN
    }

    fun eval(vars: Map<String, Double>): Double = eval { vars[it] ?: 0.0 }

    companion object {
        const val MAX_NODES = 64
        const val MAX_LENGTH = 200

        /** Переменные, которые есть всегда; источники движения сцены (`lfo1`, `noise1`) добавляются снаружи. */
        val BUILTIN_VARS: Set<String> = setOf("t", "energy", "beat", "pi", "base", "i", "n")

        private val FN1: Map<String, (Double) -> Double> = mapOf(
            "sin" to { x -> sin(x) }, "cos" to { x -> cos(x) }, "abs" to { x -> abs(x) }, "sqrt" to { x -> sqrt(max(x, 0.0)) },
            "floor" to { x -> floor(x) }, "fract" to { x -> SceneMath.fract(x) }, "noise" to { x -> SceneMath.valueNoise(x) },
            "sign" to { x -> sign(x) },
        )
        private val FN2: Map<String, (Double, Double) -> Double> = mapOf(
            "min" to { a, b -> min(a, b) }, "max" to { a, b -> max(a, b) }, "pow" to { a, b -> a.pow(b) },
        )
        private val FN3: Map<String, (Double, Double, Double) -> Double> = mapOf(
            "clamp" to { x, a, b -> min(max(x, a), b) }, "mix" to { a, b, t -> a + (b - a) * t },
            "smooth" to { a, b, x -> SceneMath.smoothstep(a, b, x) }, "if" to { c, a, b -> if (c != 0.0) a else b },
        )
        val FUNCTIONS: Set<String> = FN1.keys + FN2.keys + FN3.keys

        /**
         * Разобрать формулу. [vars] — допустимые имена переменных; null — не проверять (для тестов).
         * @throws ExprException с понятным описанием
         */
        fun compile(source: String, vars: Set<String>? = BUILTIN_VARS): Expr {
            if (source.isBlank()) throw ExprException("Формула пустая")
            if (source.length > MAX_LENGTH) throw ExprException("Формула длиннее $MAX_LENGTH знаков")
            val parser = Parser(tokenize(source), vars)
            val root = parser.parse()
            return Expr(source, root, parser.nodes)
        }

        private sealed interface Tok {
            data class Num(val v: Double) : Tok
            data class Id(val s: String) : Tok
            data class Op(val s: String) : Tok
        }

        private fun tokenize(src: String): List<Tok> {
            val out = ArrayList<Tok>()
            var i = 0
            while (i < src.length) {
                val c = src[i]
                when {
                    c.isWhitespace() -> i++
                    c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                        val st = i
                        while (i < src.length && src[i].isDigit()) i++
                        if (i < src.length && src[i] == '.') { i++; while (i < src.length && src[i].isDigit()) i++ }
                        out += Tok.Num(src.substring(st, i).toDouble())
                    }
                    c.isLetter() || c == '_' -> {
                        val st = i
                        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                        out += Tok.Id(src.substring(st, i))
                    }
                    else -> {
                        val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
                        if (two in setOf("<=", ">=", "==", "!=")) { out += Tok.Op(two); i += 2 }
                        else if (c in "+-*/%^(),<>") { out += Tok.Op(c.toString()); i++ }
                        else throw ExprException("Неожиданный знак «$c»")
                    }
                }
            }
            return out
        }

        private class Parser(val toks: List<Tok>, val vars: Set<String>?) {
            var p = 0
            var nodes = 0

            fun parse(): ExprNode {
                val e = cmp()
                if (p < toks.size) throw ExprException("Лишнее в конце формулы: «${show(toks[p])}»")
                return e
            }

            private fun show(t: Tok) = when (t) { is Tok.Num -> t.v.toString(); is Tok.Id -> t.s; is Tok.Op -> t.s }

            private fun count() { if (++nodes > MAX_NODES) throw ExprException("Формула длиннее $MAX_NODES узлов") }

            private fun peekOp(): String? = (toks.getOrNull(p) as? Tok.Op)?.s

            private fun cmp(): ExprNode {
                var l = add()
                while (true) {
                    val o = peekOp()
                    if (o == null || o !in CMP) break
                    p++
                    val r = add(); count()
                    val left = l
                    l = ExprNode { v ->
                        val a = left.eval(v); val b = r.eval(v)
                        val ok = when (o) { "<" -> a < b; ">" -> a > b; "<=" -> a <= b; ">=" -> a >= b; "==" -> a == b; else -> a != b }
                        if (ok) 1.0 else 0.0
                    }
                }
                return l
            }

            private fun add(): ExprNode {
                var l = mul()
                while (true) {
                    val o = peekOp()
                    if (o != "+" && o != "-") break
                    p++
                    val r = mul(); count()
                    val left = l
                    l = if (o == "+") ExprNode { v -> left.eval(v) + r.eval(v) } else ExprNode { v -> left.eval(v) - r.eval(v) }
                }
                return l
            }

            private fun mul(): ExprNode {
                var l = unary()
                while (true) {
                    val o = peekOp()
                    if (o != "*" && o != "/" && o != "%") break
                    p++
                    val r = unary(); count()
                    val left = l
                    l = when (o) {
                        "*" -> ExprNode { v -> left.eval(v) * r.eval(v) }
                        "/" -> ExprNode { v -> val d = r.eval(v); if (d == 0.0) 0.0 else left.eval(v) / d }
                        else -> ExprNode { v -> val d = r.eval(v); if (d == 0.0) 0.0 else left.eval(v) % d }
                    }
                }
                return l
            }

            /** Минус слабее степени: `-2^2` равно −4. */
            private fun unary(): ExprNode {
                if (peekOp() == "-") {
                    p++; count()
                    val e = unary()
                    return ExprNode { v -> -e.eval(v) }
                }
                return pow()
            }

            private fun pow(): ExprNode {
                val b = prim()
                if (peekOp() == "^") {
                    p++; count()
                    val e = unary()
                    return ExprNode { v -> b.eval(v).pow(e.eval(v)) }
                }
                return b
            }

            private fun prim(): ExprNode {
                count()
                val t = toks.getOrNull(p++) ?: throw ExprException("Формула оборвана")
                when (t) {
                    is Tok.Num -> { val x = t.v; return ExprNode { x } }
                    is Tok.Op -> {
                        if (t.s == "(") {
                            val e = cmp()
                            if ((toks.getOrNull(p++) as? Tok.Op)?.s != ")") throw ExprException("Нет закрывающей скобки")
                            return e
                        }
                        throw ExprException("Неожиданный знак «${t.s}»")
                    }
                    is Tok.Id -> {
                        if (peekOp() == "(") return call(t.s)
                        val name = t.s
                        if (vars != null && name !in vars) throw ExprException("Переменной «$name» нет (есть: ${vars.sorted().joinToString(", ")})")
                        return ExprNode { v -> v(name) }
                    }
                }
            }

            private fun call(name: String): ExprNode {
                p++ // «(»
                val args = ArrayList<ExprNode>()
                if (peekOp() != ")") {
                    do { args += cmp() } while (peekOp() == "," && run { p++; true })
                }
                if ((toks.getOrNull(p++) as? Tok.Op)?.s != ")") throw ExprException("Нет закрывающей скобки у функции $name")
                FN1[name]?.let { f -> if (args.size != 1) throw ExprException("Функция $name ждёт 1 аргумент"); val a = args[0]; return ExprNode { v -> f(a.eval(v)) } }
                FN2[name]?.let { f -> if (args.size != 2) throw ExprException("Функция $name ждёт 2 аргумента"); val a = args[0]; val b = args[1]; return ExprNode { v -> f(a.eval(v), b.eval(v)) } }
                FN3[name]?.let { f -> if (args.size != 3) throw ExprException("Функция $name ждёт 3 аргумента"); val a = args[0]; val b = args[1]; val c = args[2]; return ExprNode { v -> f(a.eval(v), b.eval(v), c.eval(v)) } }
                throw ExprException("Функции «$name» нет (есть: ${FUNCTIONS.sorted().joinToString(", ")})")
            }
        }

        private val CMP = setOf("<", ">", "<=", ">=", "==", "!=")
    }
}
