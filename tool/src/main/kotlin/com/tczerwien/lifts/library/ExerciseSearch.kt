package com.tczerwien.lifts.library

import com.tczerwien.lifts.data.ExerciseSummary
import com.tczerwien.lifts.ui.displayName

/**
 * Exercise search for the picker. Pure, so it is tested against the real catalog.
 *
 * A query matches a name when every query word starts a word of the name, in any order ("bench dumb"
 * finds "Dumbbell Bench Press"), or when the query run together starts at a word of the name run
 * together ("pullup" and "pull up" find "Pullups" and "Band-Assisted Pull-Up"). Gym shorthand is
 * understood first ([ALIASES]: db, rdl, pec deck, single arm, ...). An exercise answers to every name
 * it has: the one Lifts shows (a short name or his rename), its stored name and its catalog name.
 */

/**
 * [exercise] as the search sees it: shown as [displayName], and answering to its stored name too and,
 * for a catalog lift he renamed ([isRenamed]), to [catalogName].
 */
fun catalogEntry(exercise: ExerciseSummary, catalogName: String? = null): CatalogEntry = CatalogEntry(
    id = exercise.id,
    shown = displayName(exercise.id, exercise.name, exercise.isCustom),
    otherNames = listOfNotNull(exercise.name, catalogName?.takeIf { isRenamed(exercise) }),
    primaryMuscles = exercise.primaryMuscles,
    equipment = exercise.equipment,
    category = exercise.category,
)

/**
 * A catalog lift whose stored name is no longer its catalog name: he renamed it. Unrenamed, a catalog
 * lift's name run together is its id run together ("Child's Pose" is childs-pose), so this needs no
 * catalog; only a renamed lift's old name does.
 */
fun isRenamed(exercise: ExerciseSummary): Boolean =
    !exercise.isCustom && words(exercise.name).joinToString("") != words(exercise.id).joinToString("")

/** One exercise as the picker sees it. Words are split once, when the catalog loads. */
class CatalogEntry(
    val id: String,
    /** The name Lifts shows ([com.tczerwien.lifts.ui.displayName]). */
    val shown: String,
    /** The other names it answers to: its stored name and, for a lift he renamed, its catalog name. */
    otherNames: Collection<String> = emptyList(),
    val primaryMuscles: List<String> = emptyList(),
    equipment: String? = null,
    val category: String = "strength",
) {
    internal val shownName = SearchName(shown)
    internal val otherNames: List<SearchName> = otherNames
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.lowercase() != shown.trim().lowercase() }
        .distinctBy { it.lowercase() }
        .map(::SearchName)

    /** Every name it answers to, for duplicate checks. */
    internal val allNames: List<String> get() = listOf(shown) + otherNames.map { it.text }

    /** Equipment as words ("cable"), so "cable pushdown" finds Triceps Pushdown; none for "other", "body only". */
    internal val equipmentWords: List<String> =
        equipment?.trim()?.lowercase()?.takeIf { it !in NOT_EQUIPMENT }?.let(::words).orEmpty()

    /** Lifts rank above stretches and jumps that share their words ("Overhead Triceps" is a stretch). */
    internal val isLift: Boolean = category.lowercase() !in NOT_LIFTS

    override fun toString() = "CatalogEntry($id, $shown)"
}

/** A name split into lowercase words, plus its words run together, for matching. */
internal class SearchName(val text: String) {
    val words: List<String> = words(text)
    val compact: String = words.joinToString("")

    /** Where each word starts inside [compact]. */
    val wordStarts: List<Int> = words.runningFold(0) { at, word -> at + word.length }.dropLast(1)
}

/** Lowercase words: letters and digits, split at anything else ("3/4 Sit-Up" → 3, 4, sit, up). */
internal fun words(text: String): List<String> =
    text.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

private val NON_WORD = Regex("[^a-z0-9]+")

private val NOT_EQUIPMENT = setOf("other", "body only", "none")

private val NOT_LIFTS = setOf("stretching", "plyometrics", "cardio")

/**
 * Gym shorthand, by the words he types, with the catalog's words for the same thing. Each side is a
 * phrase; the typed phrase still matches itself too, so "DB Shrug" (a short name) answers "db shrug"
 * as well as "Dumbbell Shrug" does. The longest phrase wins where two overlap ("reverse pec deck").
 */
internal val ALIASES: Map<String, List<String>> = mapOf(
    "db" to listOf("dumbbell"),
    "dumbbell" to listOf("db"),
    "bb" to listOf("barbell"),
    "kb" to listOf("kettlebell"),
    "rdl" to listOf("romanian deadlift"),
    "sldl" to listOf("stiff legged deadlift"),
    "ohp" to listOf("overhead press", "military press", "shoulder press"),
    "shoulder press" to listOf("military press", "overhead press"),
    "military press" to listOf("shoulder press", "overhead press"),
    "overhead press" to listOf("shoulder press", "military press"),
    "bss" to listOf("bulgarian split squat", "split squat"),
    // The catalog's pec deck is "Butterfly"; its reverse is "Reverse Machine Flyes".
    "pec deck" to listOf("butterfly"),
    "reverse pec deck" to listOf("reverse machine fly"),
    "rear delt fly" to listOf("reverse fly"),
    "chest supported" to listOf("incline", "lying"),
    "single arm" to listOf("one arm"),
    "one arm" to listOf("single arm"),
    "single leg" to listOf("one leg"),
    "one leg" to listOf("single leg"),
    "neutral grip" to listOf("v bar"),
    "lat pulldown" to listOf("pulldown"),
    "hamstring curl" to listOf("leg curl"),
    // The catalog's cable pushdowns are "Triceps Pushdown", filed under cable.
    "cable pushdown" to listOf("triceps pushdown"),
    "flies" to listOf("fly"),
    "calves" to listOf("calf"),
)

private val ALIAS_PHRASES: Map<List<String>, List<List<String>>> =
    ALIASES.entries.associate { (typed, catalog) -> words(typed) to catalog.map(::words) }

private val LONGEST_ALIAS = ALIAS_PHRASES.keys.maxOf { it.size }

/** One thing he typed (a word, or a shorthand phrase) and the spellings that satisfy it. */
internal class Term(val spellings: List<List<String>>) {
    /** Some spelling has each of its words start a word of [words], or of [extra]. */
    fun matches(words: List<String>, extra: List<String> = emptyList()): Boolean =
        spellings.any { spelling ->
            spelling.all { part -> words.any { startsWithPart(it, part) } || extra.any { startsWithPart(it, part) } }
        }
}

/** [word] starts with [part], or with [part] less a plural ending ("curls" → curl, "presses" → press). */
private fun startsWithPart(word: String, part: String): Boolean =
    word.startsWith(part) || singulars(part).any { word.startsWith(it) }

private fun singulars(word: String): List<String> = buildList {
    if (word.length >= 4 && word.endsWith("es")) add(word.dropLast(2))
    if (word.length >= 4 && word.endsWith("s") && !word.endsWith("ss")) add(word.dropLast(1))
}

/** A typed word this long that begins a shorthand ("dum" of dumbbell) takes its spellings. */
private const val MIN_BEGUN_LENGTH = 3

/** A parsed query: its terms, and what he typed run together. */
internal class Query(val terms: List<Term>, val compact: String)

internal fun parseQuery(raw: String): Query {
    val typed = words(raw)
    val terms = mutableListOf<Term>()
    var at = 0
    while (at < typed.size) {
        val phrase = (minOf(LONGEST_ALIAS, typed.size - at) downTo 1).asSequence()
            .map { typed.subList(at, at + it) }
            .firstOrNull { it in ALIAS_PHRASES }
        if (phrase != null) {
            terms += Term(listOf(phrase) + ALIAS_PHRASES.getValue(phrase))
            at += phrase.size
        } else {
            // A word he is still typing takes a one-word shorthand's spellings too ("dumb" also finds DB).
            val word = typed[at]
            val begun = if (word.length < MIN_BEGUN_LENGTH) {
                emptyList()
            } else {
                ALIAS_PHRASES.filterKeys { it.size == 1 && it[0].startsWith(word) }.values.flatten()
            }
            terms += Term(listOf(listOf(word)) + begun)
            at++
        }
    }
    return Query(terms, typed.joinToString(""))
}

/** How well a name answers a query; lower is better. */
private enum class Fit { EXACT, MATCHES }

private fun fit(name: SearchName, query: Query): Fit? {
    val runTogether = listOf(query.compact) + singulars(query.compact)
    return when {
        query.compact.isEmpty() -> null
        runTogether.any { it == name.compact } || singulars(name.compact).any { it == query.compact } -> Fit.EXACT
        query.terms.all { it.matches(name.words) } -> Fit.MATCHES
        runTogether.any { compact ->
            name.wordStarts.any { name.compact.startsWith(compact, it) }
        } -> Fit.MATCHES
        else -> null
    }
}

/**
 * The rank of [entry] for [query], lower is better, or null when it doesn't match:
 * 0 the shown name is what he typed; 1 the shown name matches; 2 another of its names is what he
 * typed; 3 another name matches; 4 it matches once its equipment counts ("cable pushdown").
 */
internal fun rank(entry: CatalogEntry, query: Query): Int? {
    fit(entry.shownName, query)?.let { return it.ordinal }
    entry.otherNames.mapNotNull { fit(it, query) }.minOrNull()?.let { return 2 + it.ordinal }
    val withEquipment = entry.equipmentWords.isNotEmpty() && (listOf(entry.shownName) + entry.otherNames).any { name ->
        query.terms.all { it.matches(name.words, entry.equipmentWords) }
    }
    return if (withEquipment) 4 else null
}

/** Near misses: a name that has all but one of his words, for a long query nothing else answers. */
private const val NEAR_MISS_RANK = 5
private const val NEAR_MISS_MIN_TERMS = 3

private fun nearMiss(entry: CatalogEntry, query: Query): Boolean =
    (listOf(entry.shownName) + entry.otherNames).any { name ->
        query.terms.count { !it.matches(name.words, entry.equipmentWords) } == 1
    }

/**
 * The entries that answer [raw], best first: lifts before stretches and jumps ("split squat" is a lift
 * before it is the "Split Squats" jump); then by [rank]; then what he has done before, most recent
 * first ([recentIds]); then shorter shown names, the plain lift before its variants ("Overhead Press"
 * before "Seated Barbell Military Press"); then by name. When nothing answers a query of three words
 * or more, the names that miss only one of them are offered instead ("seated db shoulder press" finds
 * "DB Shoulder Press").
 */
fun searchExercises(
    entries: List<CatalogEntry>,
    raw: String,
    recentIds: List<String> = emptyList(),
): List<CatalogEntry> {
    val query = parseQuery(raw)
    if (query.terms.isEmpty()) return emptyList()
    var ranked = entries.mapNotNull { entry -> rank(entry, query)?.let { entry to it } }
    if (ranked.isEmpty() && query.terms.size >= NEAR_MISS_MIN_TERMS) {
        ranked = entries.filter { nearMiss(it, query) }.map { it to NEAR_MISS_RANK }
    }
    val recency = recentIds.withIndex().associate { (index, id) -> id to index }
    return ranked
        .sortedWith(
            compareBy<Pair<CatalogEntry, Int>> { if (it.first.isLift) 0 else 1 }
                .thenBy { it.second }
                .thenBy { recency[it.first.id] ?: Int.MAX_VALUE }
                .thenBy { it.first.shownName.words.size }
                .thenBy { it.first.shown.lowercase() }
                .thenBy { it.first.id },
        )
        .map { it.first }
}
