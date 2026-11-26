package app.deixis.core.effects

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExprTest {
    private fun eval(src: String, vars: Map<String, Double> = emptyMap()): Double = Expr.compile(src, null).eval(vars)

    @Test
    fun arithmeticAndPrecedence() {
        assertEquals(7.0, eval("1+2*3"), 1e-9)
        assertEquals(9.0, eval("(1+2)*3"), 1e-9)
        assertEquals(2.5, eval("10/4"), 1e-9)
        assertEquals(1.0, eval("7%3"), 1e-9)
        assertEquals(8.0, eval("2^3"), 1e-9)
        assertEquals(512.0, eval("2^3^2"), 1e-9) // степень правоассоциативна
        assertEquals(-4.0, eval("-2^2"), 1e-9)   // минус слабее степени
        assertEquals(0.25, eval("2^-2"), 1e-9)
        assertEquals(3.0, eval("5 - 2"), 1e-9)
        assertEquals(0.5, eval(".5"), 1e-9)
    }

    @Test
    fun comparisonsAndIf() {
        assertEquals(1.0, eval("3>2"), 1e-9)
        assertEquals(0.0, eval("3<=2"), 1e-9)
        assertEquals(1.0, eval("2==2"), 1e-9)
        assertEquals(1.0, eval("2!=3"), 1e-9)
        assertEquals(10.0, eval("if(energy>0.5,10,20)", mapOf("energy" to 0.8)), 1e-9)
        assertEquals(20.0, eval("if(energy>0.5,10,20)", mapOf("energy" to 0.2)), 1e-9)
    }

    @Test
    fun functions() {
        assertEquals(sin(1.0), eval("sin(1)"), 1e-12)
        assertEquals(3.0, eval("abs(-3)"), 1e-9)
        assertEquals(2.0, eval("min(2,5)"), 1e-9)
        assertEquals(5.0, eval("max(2,5)"), 1e-9)
        assertEquals(0.5, eval("clamp(0.5,0,1)"), 1e-9)
        assertEquals(1.0, eval("clamp(9,0,1)"), 1e-9)
        assertEquals(15.0, eval("mix(10,20,0.5)"), 1e-9)
        assertEquals(3.0, eval("sqrt(9)"), 1e-9)
        assertEquals(2.0, eval("floor(2.9)"), 1e-9)
        assertEquals(0.25, eval("fract(2.25)"), 1e-9)
        assertEquals(0.5, eval("smooth(0,1,0.5)"), 1e-9)
        assertEquals(-1.0, eval("sign(-7)"), 1e-9)
        assertEquals(0.0, eval("sqrt(-4)"), 1e-9)
    }

    @Test
    fun variablesFromTheScene() {
        val v = mapOf("base" to 0.3, "t" to 2.0, "i" to 3.0, "n" to 12.0, "energy" to 0.5)
        val e = "base*(0.82+0.36*sin(i*0.9+t*1.1))"
        val expected = 0.3 * (0.82 + 0.36 * sin(3 * 0.9 + 2 * 1.1))
        assertEquals(expected, eval(e, v), 1e-12)
        assertEquals(PI, eval("pi", mapOf("pi" to PI)), 1e-12)
    }

    @Test
    fun noiseIsDeterministicAndInRange() {
        val a = eval("noise(3.7)")
        assertEquals(a, eval("noise(3.7)"), 0.0)
        assertEquals(SceneMath.valueNoise(3.7), a, 1e-12)
        for (x in listOf(-5.5, 0.0, 1.3, 99.9)) assertTrue(eval("noise($x)") in 0.0..1.0)
    }

    @Test
    fun divisionByZeroIsZeroAndNonFiniteIsNaN() {
        assertEquals(0.0, eval("1/0"), 0.0)
        assertEquals(0.0, eval("5%0"), 0.0)
        assertTrue(eval("pow(0-1,0.5)").isNaN())
        assertTrue(eval("pow(10,1000)").isNaN())
    }

    @Test
    fun errorsAreExplained() {
        fun msg(src: String, vars: Set<String>? = Expr.BUILTIN_VARS) = assertFailsWith<ExprException> { Expr.compile(src, vars) }.message.orEmpty()
        assertTrue("оборвана" in msg("1+"))
        assertTrue("скобки" in msg("(1+2"))
        assertTrue("скобки" in msg("sin(1"))
        assertTrue("Неожиданный знак" in msg("1 # 2"))
        assertTrue("Функции «foo» нет" in msg("foo(1)"))
        assertTrue("ждёт 1 аргумент" in msg("sin(1,2)"))
        assertTrue("ждёт 3 аргумента" in msg("clamp(1,2)"))
        assertTrue("Переменной «zzz» нет" in msg("zzz+1"))
        assertTrue("Лишнее" in msg("1 2"))
        assertTrue("пустая" in msg("   "))
    }

    @Test
    fun customVariablesAreAllowedWhenListed() {
        Expr.compile("base+lfo1*0.1+noise2", Expr.BUILTIN_VARS + setOf("lfo1", "noise2"))
        assertFailsWith<ExprException> { Expr.compile("base+lfo1", Expr.BUILTIN_VARS) }
    }

    @Test
    fun sizeIsLimitedSoEvaluationTimeIsBounded() {
        val long = "1" + "+1".repeat(Expr.MAX_LENGTH)
        assertTrue("длиннее ${Expr.MAX_LENGTH}" in assertFailsWith<ExprException> { Expr.compile(long) }.message.orEmpty())
        val nodes = (1..Expr.MAX_NODES).joinToString("+") { "1" } // длина в пределах, узлов больше предела
        assertTrue(nodes.length <= Expr.MAX_LENGTH)
        assertTrue("узлов" in assertFailsWith<ExprException> { Expr.compile(nodes) }.message.orEmpty())
        // Предел по узлам не мешает обычным формулам.
        Expr.compile("base*(0.9+0.2*sin(i*1.3+t*1.4))*(1-0.4*abs(i-(n-1)/2)/((n-1)/2))", null)
    }

    @Test
    fun deepNestingDoesNotOverflowTheStack() {
        val deep = "(".repeat(150) + "1" + ")".repeat(150)
        // Предел по узлам срабатывает раньше, чем стек: ошибка понятна, падения нет.
        assertFailsWith<ExprException> { Expr.compile(deep) }
    }
}
