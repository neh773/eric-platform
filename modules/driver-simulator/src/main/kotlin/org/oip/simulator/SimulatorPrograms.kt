package org.oip.simulator

/**
 * Preloaded Generic, Dairy, and SLC programs.
 *
 * The instruction text is written from the public simulator pages. It is not a copy of an the reference platform
 * program file. Tag names, ranges, and periods for Generic and the SLC file layout come from the
 * deprecated simulator page. Dairy uses the folders and item paths that page and the programmable
 * simulator page name (Overview, Refrigeration, Compressor, Tank, Motor, HOA, Writeable/Agitator1Mode).
 * Value sources for Dairy are this driver's functions chosen to match those descriptions.
 *
 * Legacy item paths: loading one of these programs sets [SimulatorDeviceConfig.legacyItemPaths], so
 * each address is also `_Meta:` plus the browse path. The prefix is not stored in the instruction
 * and [ProgramCsv] strips it on export.
 *
 *
 * Assumptions, where the pages do not give a value source:
 *
 * - Generic browse paths are `<group>/<tag>` (`Realistic\Realistic3` on the deprecated page).
 * - The base rate is 1000 ms, so a period in seconds is that many intervals. Ramp6's 12.5 s and
 *   Ramp7's 17.5 s are rounded to 13 and 18 intervals. The ramp period is the existing cycle length
 *   (the value reaches max on the last step and resets on the next).
 * - Sine7's low value is printed as `.-20`. That is read as -20.
 * - ReadOnlyString1 / ReadOnlyString2 are `ABCDEFG` and `ZYXWVUT`. The table draws quotes around the
 *   cells; those quotes are not part of the value.
 * - Writable tags are constants at interval 0. A user program republishes a constant each time
 *   that instruction fires, including every base rate when the span is 1, and a write lasts until
 *   that publish. Loading the Generic, Dairy, or SLC program marks its writable constants on
 *   [SimulatorDeviceConfig.persistWrites], so a write is kept across later intervals until the
 *   next write. Reset starts the program over and publishes interval 0 again. The flag is not a
 *   CSV column.
 *   doc source: FEATURES DEV-028
 * - Random tags use `random` with the function defaults (0..100, or ignored bounds for booleans)
 *   and repeat true, so they draw every base rate. The legacy per-tag update rate is not a
 *   parameter of `random`, and the note that the rate is a Java Random seed is not reproduced.
 *   Each address keeps the driver's own seeded stream.
 * - Realistic tags call `realistic()` (set point 100, the documented PID defaults). The legacy
 *   random walk between -50 and 50 is not a value-source function.
 * - Dairy member values are a reconstruction: HOA and agitator mode are writable integers,
 *   compressor and motor amps and tank level and temperature move, and one agitator tag sits four
 *   levels down so the tree matches "multiple levels deep".
 * - SLC sample `C53.ACC` is read as `C5:3.ACC`. Structured members use the sample shape
 *   `T4/T4:3/T4:3.ACC`. ACC, PRE, LEN, and POS are Int16. The other structured members are
 *   booleans. F8 is Double. Every SLC address is a writable 0.
 */
object SimulatorPrograms {
    fun instructions(name: String): List<SimInstruction> = when (name) {
        PROGRAM_GENERIC -> generic()
        PROGRAM_DAIRY -> dairy()
        PROGRAM_SLC -> slc()
        else -> throw IllegalArgumentException("unknown simulator program '$name'")
    }

    private fun generic(): List<SimInstruction> {
        val rows = ArrayList<SimInstruction>(80)
        fun add(folder: String, name: String, source: String, type: String) {
            rows += SimInstruction(0, "$folder/$name", source, type)
        }
        add("ReadOnly", "ReadOnlyBoolean1", "readonly(false)", "Boolean")
        add("ReadOnly", "ReadOnlyBoolean2", "readonly(true)", "Boolean")
        add("ReadOnly", "ReadOnlyShort1", "readonly(1)", "Int16")
        add("ReadOnly", "ReadOnlyShort2", "readonly(2)", "Int16")
        for (n in 1..5) add("ReadOnly", "ReadOnlyInteger$n", "readonly($n)", "Int32")
        add("ReadOnly", "ReadOnlyLong1", "readonly(1)", "Int64")
        add("ReadOnly", "ReadOnlyLong2", "readonly(2)", "Int64")
        add("ReadOnly", "ReadOnlyFloat1", "readonly(1.1)", "Float")
        add("ReadOnly", "ReadOnlyFloat2", "readonly(1.2)", "Float")
        add("ReadOnly", "ReadOnlyDouble1", "readonly(1.1)", "Double")
        add("ReadOnly", "ReadOnlyDouble2", "readonly(1.2)", "Double")
        add("ReadOnly", "ReadOnlyString1", "readonly(\"ABCDEFG\")", "String")
        add("ReadOnly", "ReadOnlyString2", "readonly(\"ZYXWVUT\")", "String")

        for (name in listOf("WriteableBoolean1", "WriteableBoolean2")) add("Writeable", name, "false", "Boolean")
        for (name in listOf("WriteableShort1", "WriteableShort2")) add("Writeable", name, "0", "Int16")
        for (name in listOf("WriteableInteger1", "WriteableInteger2")) add("Writeable", name, "0", "Int32")
        for (name in listOf("WriteableLong1", "WriteableLong2")) add("Writeable", name, "0", "Int64")
        for (name in listOf("WriteableFloat1", "WriteableFloat2")) add("Writeable", name, "0", "Float")
        for (name in listOf("WriteableDouble1", "WriteableDouble2")) add("Writeable", name, "0", "Double")
        for (name in listOf("WriteableString1", "WriteableString2")) add("Writeable", name, "\"\"", "String")

        for (name in listOf("RandomBoolean1", "RandomBoolean2")) add("Random", name, "random(0,1,true)", "Boolean")
        for (name in listOf("RandomShort1", "RandomShort2")) add("Random", name, "random(0,100,true)", "Int16")
        for (name in listOf("RandomInteger1", "RandomInteger2")) add("Random", name, "random(0,100,true)", "Int32")
        for (name in listOf("RandomLong1", "RandomLong2")) add("Random", name, "random(0,100,true)", "Int64")
        for (name in listOf("RandomDouble1", "RandomDouble2")) add("Random", name, "random(0,100,true)", "Double")

        val sines = listOf(
            -100.0 to 100.0 to 60,
            -10.0 to 10.0 to 10,
            0.0 to 50.0 to 15,
            -40.0 to 60.0 to 20,
            -100.0 to 100.0 to 40,
            -100.0 to 100.0 to 60,
            -10.0 to 10.0 to 10,
            -20.0 to 30.0 to 15,
            -40.0 to 60.0 to 20,
            -85.0 to 115.0 to 40,
        )
        sines.forEachIndexed { index, (bounds, period) ->
            val (low, high) = bounds
            add("Sine", "Sine$index", "sine(${num(low)},${num(high)},$period,true)", "Double")
        }
        val ramps = listOf(
            0.0 to 1000.0 to 75,
            0.0 to 100.0 to 10,
            -25.0 to 175.0 to 15,
            10.0 to 310.0 to 20,
            0.0 to 400.0 to 40,
            0.0 to 500.0 to 60,
            0.0 to 600.0 to 13,
            -5.0 to 695.0 to 18,
            10.0 to 810.0 to 30,
            -10.0 to 890.0 to 50,
        )
        ramps.forEachIndexed { index, (bounds, period) ->
            val (low, high) = bounds
            add("Ramp", "Ramp$index", "ramp(${num(low)},${num(high)},$period,true)", "Double")
        }
        for (index in 0..9) add("Realistic", "Realistic$index", "realistic()", "Double")
        return rows
    }

    private fun dairy(): List<SimInstruction> = listOf(
        SimInstruction(0, "Overview/Refrigeration/HOA", "1", "Int32"),
        SimInstruction(0, "Overview/Refrigeration/Compressor/Running", "square(0,1,10,true)", "Boolean"),
        SimInstruction(0, "Overview/Refrigeration/Compressor/Amps", "realistic(40)", "Double"),
        SimInstruction(0, "Overview/Refrigeration/Compressor/Speed", "sine(0,100,20,true)", "Double"),
        SimInstruction(0, "Overview/Refrigeration/Tank/Level", "ramp(0,100,30,true)", "Double"),
        SimInstruction(0, "Overview/Refrigeration/Tank/Temp", "sine(2,8,40,true)", "Double"),
        SimInstruction(0, "Overview/Refrigeration/Tank/Agitator/Running", "true", "Boolean"),
        SimInstruction(0, "Overview/Motor/HOA", "1", "Int32"),
        SimInstruction(0, "Overview/Motor/Running", "true", "Boolean"),
        SimInstruction(0, "Overview/Motor/Amps", "sine(5,25,15,true)", "Double"),
        SimInstruction(0, "Writeable/Agitator1Mode", "0", "Int32"),
        SimInstruction(0, "Writeable/Agitator1Speed", "0", "Double"),
    )

    private fun slc(): List<SimInstruction> {
        val rows = ArrayList<SimInstruction>(300)
        fun scalars(file: String, last: Int, type: String) {
            for (index in 0..last) {
                rows += SimInstruction(0, "$file/$file:$index", "0", type)
            }
        }
        scalars("O0", 7, "Int16")
        scalars("I1", 7, "Int16")
        scalars("S2", 9, "Int16")
        scalars("B3", 9, "Int16")
        scalars("N7", 9, "Int16")
        scalars("F8", 9, "Double")
        structured("T4", listOf("ACC" to "Int16", "DN" to "Boolean", "EN" to "Boolean", "PRE" to "Int16", "TT" to "Boolean"), rows)
        structured(
            "C5",
            listOf(
                "ACC" to "Int16", "CD" to "Boolean", "CU" to "Boolean", "DN" to "Boolean",
                "OV" to "Boolean", "PRE" to "Int16", "UA" to "Boolean", "UN" to "Boolean",
            ),
            rows,
        )
        structured(
            "R6",
            listOf(
                "DN" to "Boolean", "EM" to "Boolean", "EN" to "Boolean", "ER" to "Boolean", "EU" to "Boolean",
                "FD" to "Boolean", "IN" to "Boolean", "LEN" to "Int16", "POS" to "Int16", "UL" to "Boolean",
            ),
            rows,
        )
        return rows
    }

    private fun structured(file: String, members: List<Pair<String, String>>, rows: MutableList<SimInstruction>) {
        for (index in 0..9) {
            val element = "$file:$index"
            for ((member, type) in members) {
                val source = if (type == "Boolean") "false" else "0"
                rows += SimInstruction(0, "$file/$element/$element.$member", source, type)
            }
        }
    }

    private fun num(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
