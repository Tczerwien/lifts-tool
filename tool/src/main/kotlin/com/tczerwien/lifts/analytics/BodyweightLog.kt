package com.tczerwien.lifts.analytics

import com.tczerwien.lifts.data.BodyweightEntity
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Bodyweight screen's rules for picking a day and the weight it shows: logging today, a morning
 * he forgot (backdating), and fixing or deleting an entry. Pure, covered by BodyweightLogTest.
 */

/** How far back a weigh-in can be logged or corrected. */
const val BACKDATE_MAX_DAYS = 365

/** [day] moved [delta] days, never past [today] nor more than [BACKDATE_MAX_DAYS] before it. */
fun stepLogDay(day: Long, delta: Int, today: Long): Long = (day + delta).coerceIn(today - BACKDATE_MAX_DAYS, today)

private val weekdayFormatter = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

/**
 * "Today", "Yesterday", else the weekday and date, "Sun Sep 27", so a backdated weigh-in lands where he
 * means; a day from another year says which, "Dec 20, 2025" (the weekday gives way to the year).
 */
fun logDayLabel(day: Long, today: Long): String = when {
    day == today -> "Today"
    day == today - 1 -> "Yesterday"
    LocalDate.ofEpochDay(day).year != LocalDate.ofEpochDay(today).year -> Format.dayShort(day, LocalDate.ofEpochDay(today))
    else -> weekdayFormatter.format(LocalDate.ofEpochDay(day))
}

/**
 * What the weight stepper shows for [day], in [unit]'s display value: that day's entry when it has one,
 * so he sees what he is changing; otherwise what the stepper already [held] (a morning he forgot is
 * about what he weighs now); with neither, the latest entry, else [fallback].
 */
fun bodyweightStepperValue(
    entries: List<BodyweightEntity>,
    day: Long,
    unit: WeightUnit,
    held: Double?,
    fallback: Double,
): Double {
    val shown = { entry: BodyweightEntity -> Format.roundBodyweight(Format.kgToDisplay(entry.weightKg, unit)) }
    entries.firstOrNull { it.epochDay == day }?.let { return shown(it) }
    held?.let { return it }
    return entries.maxByOrNull { it.epochDay }?.let(shown) ?: fallback
}

/** A stepper value that was in [from] units, as [to] shows it (the unit was switched while the screen was away). */
fun bodyweightInUnit(value: Double, from: WeightUnit, to: WeightUnit): Double =
    if (from == to) value else Format.roundBodyweight(Format.kgToDisplay(Format.displayToKg(value, from), to))
