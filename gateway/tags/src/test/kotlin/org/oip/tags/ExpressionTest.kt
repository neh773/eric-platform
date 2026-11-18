package org.oip.tags

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.oip.tags.expr.EvalContext
import org.oip.tags.expr.Expr
import org.oip.tags.expr.Expressions
import org.oip.tags.expr.Value

/**
 * Expression language behavior encoded from the public docs:
 */
class ExpressionTest {
    private fun eval(text: String, tags: Map<String, Any?> = emptyMap()): Value =
        Expressions.eval(text, EvalContext(ref = { raw ->
            if (raw in tags) Value(tags[raw]) else Value(null, Quality(QualityCode.BAD_REFERENCE_NOT_FOUND, raw))
        }))

    private fun num(v: Any?): Double = (v as Number).toDouble()

    @Test
    fun arithmeticPrecedenceAndLiterals() {
        assertEquals(7.0, num(eval("1+2*3").value))
        assertEquals(9.0, num(eval("(1+2)*3").value))
        assertEquals(512.0, num(eval("2^3^2").value))
        assertEquals(1.0, num(eval("-4 + 5").value))
        assertEquals(6.0, num(eval("10 - 4").value))
        assertEquals(1.0, num(eval("10 % 3").value))
        assertEquals(2.5, num(eval("5 / 2").value))
        assertEquals(130000.0, num(eval("1.3e5").value))
        assertEquals(255.0, num(eval("0xFF").value))
        assertEquals(3.0, num(eval("1 + // comment\n2").value))
    }

    @Test
    fun comparisonLogicAndShortCircuit() {
        assertEquals(true, eval("5 = 5").value)
        assertEquals(true, eval("5 == 5").value)
        assertEquals(true, eval("5 != 4").value)
        assertEquals(true, eval("1 < 2 && 2 <= 2").value)
        assertEquals(false, eval("1 && 0").value)
        assertEquals(true, eval("0 || 2").value)
        assertEquals(true, eval("!0").value)
        assertEquals(false, eval("!True").value)
        assertEquals(2.0, num(eval("if(0, 1/0, 2)").value))
        assertEquals(true, eval("0 && (1/0) || 2").value)
        assertEquals(QualityCode.ERROR_EXPRESSION_EVAL, eval("1 && (1/0)").quality.code)
    }

    @Test
    fun ifAndSwitch() {
        assertEquals("Yes", eval("""if(1, "Yes", "No")""").value)
        assertEquals("No", eval("""if(0, "Yes", "No")""").value)
        // switch(value, cases..., returns..., default) — public switch() examples.
        assertEquals(46.0, num(eval("switch(15, 1, 24, 15, 44, 45, 46, -1)").value))
        assertEquals("Running", eval("""switch(1, 0, 1, 2, "Off", "Running", "Fault", "BAD")""").value)
        assertEquals("BAD", eval("""switch(9, 0, 1, 2, "Off", "Running", "Fault", "BAD")""").value)
        assertEquals(QualityCode.ERROR_EXPRESSION_EVAL, eval("switch(1, 2, 3)").quality.code)
    }

    @Test
    fun stringAndMathFunctions() {
        assertEquals(3, eval("""len("abc")""").value)
        assertEquals("abc", eval("""lower("AbC")""").value)
        assertEquals("ABC", eval("""upper("AbC")""").value)
        assertEquals("bc", eval("""substring("abcd", 1, 3)""").value)
        assertEquals("ab", eval("""left("abcd", 2)""").value)
        assertEquals("cd", eval("""right("abcd", 2)""").value)
        assertEquals("a-b", eval("""replace("a.b", ".", "-")""").value)
        assertEquals("ab", eval("""trim(" ab ")""").value)
        assertEquals("The answer is: 42", eval("""concat("The answer is: ", "42")""").value)
        assertEquals(1, eval("""indexOf("abcd", "bc")""").value)
        assertEquals("FirstSecond", eval(""" "First" + "Second" """).value)
        assertEquals("2 alarms", eval("""2 + " alarms" """).value)
        assertEquals(3.0, num(eval("abs(-3)").value))
        assertEquals(3.0, num(eval("sqrt(9)").value))
        assertEquals(8.0, num(eval("pow(2, 3)").value))
        assertEquals(2.0, num(eval("min(4, 2, 9)").value))
        assertEquals(9.0, num(eval("max(4, 2, 9)").value))
        assertEquals(2L, eval("round(1.6)").value)
        assertEquals(1L, eval("floor(1.6)").value)
        assertEquals(2L, eval("ceil(1.2)").value)
        assertEquals(true, eval(""" "Hello" like "H*" """).value)
        assertEquals(true, eval(""" "Hello" like "H%o" """).value)
        assertEquals(false, eval(""" "Hello" like "h*" """).value)
        assertEquals(1L, eval("5 & 3").value)
        assertEquals(7L, eval("5 | 3").value)
        assertEquals(6L, eval("5 xor 3").value)
        assertEquals(true, eval("null = None").value)
    }

    /**
     * The operator table lists unary minus, then power, then multiply, then add. A leading `-`
     * followed by a number negates that number, so `-2^2` is `(-2)^2`. Multiplication binds
     * tighter than addition (`1.8 * tag + 32`).
     */
    @Test
    fun unaryMinusBindsTighterThanPower() {
        assertEquals(4.0, num(eval("-2^2").value))
        assertEquals(-4.0, num(eval("-(2^2)").value))
        assertEquals(50.0, num(eval("1.8 * 10 + 32").value))
    }

    /**
     * `=` tests equality of the two operands and does not coerce a numeric string. Relational
     * operators are defined between numbers; mixed string/number is a type error. Coercion is
     * the toInt/toDouble cast.
     */
    @Test
    fun stringAndNumberAreNotCoercedForComparison() {
        assertEquals(false, eval("""'5' = 5""").value)
        assertEquals(true, eval("""'5' != 5""").value)
        assertEquals(false, eval("""5 = '5'""").value)
        assertEquals(true, eval("""toInt('5') = 5""").value)
        val relational = eval("""'5' > 4""")
        assertNull(relational.value)
        assertEquals(QualityCode.ERROR_TYPE_CONVERSION, relational.quality.code)
    }

    /**
     * Integer math is 64-bit (toLong). Overflow promotes to double (toDouble) instead of wrapping.
     */
    @Test
    fun integerOverflowPromotesToDouble() {
        val sum = eval("9223372036854775807 + 1")
        assertEquals(9223372036854775808.0, sum.value)
        assertEquals(QualityCode.GOOD, sum.quality.code)
        assertEquals(6L, eval("1 + 2 + 3").value)
        val negated = eval("0 - {n}", mapOf("n" to Long.MIN_VALUE))
        assertEquals(9223372036854775808.0, negated.value)
        val absolute = eval("abs({n})", mapOf("n" to Long.MIN_VALUE))
        assertEquals(9223372036854775808.0, absolute.value)
    }

    @Test
    fun arityAndNullIndexAndRuntimeFailuresStayInsideTheValue() {
        for (text in listOf("left()", "right()", "substring()")) {
            val value = eval(text)
            assertNull(value.value, text)
            assertEquals(QualityCode.ERROR_EXPRESSION_EVAL, value.quality.code, text)
        }
        val missingCell = eval("null[1, 2]")
        assertNull(missingCell.value)
        assertEquals(QualityCode.BAD_NOT_FOUND, missingCell.quality.code)

        val boom = object : Number() {
            override fun toByte(): Byte = 0
            override fun toDouble(): Double = throw IllegalStateException("boom")
            override fun toFloat(): Float = 0f
            override fun toInt(): Int = 0
            override fun toLong(): Long = 0
            override fun toShort(): Short = 0
        }
        val guarded = Expressions.eval(Expr.Call("abs", listOf(Expr.Lit(boom))), EvalContext(ref = { Value(null) }))
        assertNull(guarded.value)
        assertEquals(QualityCode.ERROR_EXPRESSION_EVAL, guarded.quality.code)
        assertTrue(guarded.quality.diagnostic!!.contains("boom"))
    }

    @Test
    fun indexErrorsKeepTheWorstInputQuality() {
        val err = Quality.ERROR_CYCLE_DETECTED
        val dataset = Expressions.eval(
            Expr.Index(Expr.Ref("t"), listOf(Expr.Lit(0), Expr.Lit(1))),
            EvalContext(ref = { Value(listOf(10), err) }),
        )
        assertEquals(QualityCode.ERROR_CYCLE_DETECTED, dataset.quality.code)
        val badIndex = Expressions.eval(
            Expr.Index(Expr.Ref("t"), listOf(Expr.Lit("col"))),
            EvalContext(ref = { Value(listOf(10), err) }),
        )
        assertEquals(QualityCode.ERROR_CYCLE_DETECTED, badIndex.quality.code)
        val missing = eval("{Missing}[1, 2]")
        assertEquals(QualityCode.BAD_REFERENCE_NOT_FOUND, missing.quality.code)
    }

    @Test
    fun tagReferenceRecordsARead() {
        val seen = ArrayList<String>()
        val value = Expressions.eval("{Plant/Speed} * 2", EvalContext(ref = { raw ->
            seen += raw
            Value(3)
        }))
        assertEquals(6.0, num(value.value))
        assertEquals(listOf("Plant/Speed"), seen)
        assertEquals(listOf("Plant/Speed"), Expressions.staticRefs(Expressions.parse("{Plant/Speed} * 2")))
    }
}
