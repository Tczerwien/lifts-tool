package com.tczerwien.lifts.export

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CsvExportTest {

    private val rendered = CsvExport.render(ExportFixtures.snapshot())
    private val rows = parseCsv(rendered)

    @Test
    fun headerIsExactStrongIosFormat() {
        assertEquals(
            "Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes,RPE",
            rendered.substringBefore('\n'),
        )
    }

    @Test
    fun oneRowPerCompletedSet() {
        // Header + 6 completed sets.
        assertEquals(7, rendered.trimEnd('\n').split('\n').size)
        assertEquals(7, rows.size)
        assertEquals(12, rows[0].size)
    }

    @Test
    fun rowsAreChronologicalWithFormattedDatesAndDurations() {
        // Workout 1 (44 min) rows come first.
        assertEquals("2026-07-06 10:00:00", rows[1][0])
        assertEquals("44m", rows[1][2])
        assertEquals("Push Day", rows[1][1])
        assertEquals("Bench Press (Barbell)", rows[1][3])
        // Workout 2 lasted 98 min -> "1h 38m".
        assertEquals("2026-07-13 10:00:00", rows[4][0])
        assertEquals("1h 38m", rows[4][2])
    }

    @Test
    fun setFieldsAreExported() {
        // rows[5] = workout 2, set order 2 (the 102.5x5 @8 PR set).
        val row = rows[5]
        assertEquals("2", row[4])
        assertEquals("102.5", row[5])
        assertEquals("5", row[6])
        assertEquals("0", row[7]) // Distance
        assertEquals("0", row[8]) // Seconds
        assertEquals("Felt strong", row[10])
        assertEquals("8", row[11])
        // Warmup row: weight 60, no RPE.
        assertEquals("60", rows[4][5])
        assertEquals("", rows[4][11])
    }

    @Test
    fun commaAndQuoteFieldRoundTripsPerRfc4180() {
        // Raw encoding: quoted, embedded quotes doubled.
        assertContains(rendered, "\"Paused last rep, focus on \"\"leg drive\"\"\"")
        // Parsing it back yields the original text, and the row still has 12 fields.
        assertEquals(ExportFixtures.INSTANCE_NOTES, rows[5][9])
        assertEquals(12, rows[5].size)
    }

    @Test
    fun bodyweightCsvHasHeaderAndOneRowPerEntry() {
        val bodyweight = CsvExport.renderBodyweight(ExportFixtures.snapshot())
        val bodyweightRows = parseCsv(bodyweight)

        assertEquals("Date,Weight", bodyweight.substringBefore('\n'))
        // Header + 6 entries, oldest first.
        assertEquals(7, bodyweightRows.size)
        assertEquals(listOf("2026-06-15", "82"), bodyweightRows[1])
        assertEquals(listOf("2026-07-09", "81.2"), bodyweightRows[5])
        assertEquals(listOf("2026-07-13", "80.5"), bodyweightRows[6])
    }

    /** Minimal RFC-4180 parser (quoted fields, doubled quotes, LF or CRLF rows). */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"')
                        i++
                    }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        row.add(field.toString())
                        field.clear()
                    }
                    '\n' -> {
                        row.add(field.toString())
                        field.clear()
                        rows.add(row.toList())
                        row.clear()
                    }
                    '\r' -> {}
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row.toList())
        }
        return rows
    }
}
