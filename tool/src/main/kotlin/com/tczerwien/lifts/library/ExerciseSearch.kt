package com.tczerwien.lifts.library

import com.tczerwien.lifts.data.ExerciseSummary
import com.tczerwien.lifts.seed.BundledCatalog
import com.tczerwien.lifts.seed.CatalogSource
import com.tczerwien.lifts.ui.displayName
import com.tczerwien.lifts.ui.knownCatalogName
import com.tczerwien.lifts.ui.knownShortName

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
 * for a catalog lift he renamed ([isRenamed]), to [catalogName] and its short name: the names it shows
 * again if he resets it, which no other exercise may take meanwhile.
 */
fun catalogEntry(exercise: ExerciseSummary, catalogName: String? = null): CatalogEntry = CatalogEntry(
    id = exercise.id,
    shown = displayName(exercise.id, exercise.name, exercise.isCustom),
    otherNames = listOfNotNull(exercise.name) +
        if (isRenamed(exercise)) listOfNotNull(catalogName, knownShortName(exercise.id)) else emptyList(),
    primaryMuscles = exercise.primaryMuscles,
    equipment = exercise.equipment,
    category = exercise.category,
)

/**
 * [summaries] as the search and the name checks see them, by shown name. A lift he renamed also
 * answers to its catalog name; the bundled [catalog] is read for that ([BundledCatalog.names], once
 * per process) only when one of them has no short name. Blocking then: call it off the main thread.
 */
fun catalogEntries(summaries: List<ExerciseSummary>, catalog: CatalogSource): List<CatalogEntry> {
    val needSeed = summaries.any { isRenamed(it) && knownCatalogName(it.id) == null }
    val catalogNames = if (needSeed) BundledCatalog.names(catalog) else emptyMap()
    return summaries
        .map { catalogEntry(it, knownCatalogName(it.id) ?: catalogNames[it.id]) }
        .sortedWith(compareBy({ it.shown.lowercase() }, { it.id }))
}

/**
 * [catalogEntries] for a screen's name check ([nameClash]), which runs on Main as he submits: every
 * entry's keys are worked out here, off the main thread, so the check is only lookups. Blocking.
 */
fun nameCheckEntries(summaries: List<ExerciseSummary>, catalog: CatalogSource): List<CatalogEntry> =
    catalogEntries(summaries, catalog).onEach { it.claimedKeys }

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

    /** The [nameKeys] of all its names, worked out once: the check on Main runs over the whole catalog. */
    internal val claimedKeys: Set<String> by lazy { allNames.flatMapTo(HashSet()) { nameKeys(it) } }

    /** Equipment as words ("cable"), so "cable pushdown" finds Triceps Pushdown; none for "other", "body only". */
    internal val equipmentWords: List<String> =
        equipment?.trim()?.lowercase()?.takeIf { it !in NOT_EQUIPMENT }
            ?.let { EQUIPMENT_WORDS[it] ?: words(it) }.orEmpty()

    /** Lifts rank above stretches and jumps that share their words ("Overhead Triceps" is a stretch). */
    internal val isLift: Boolean = category.lowercase() !in NOT_LIFTS

    override fun toString() = "CatalogEntry($id, $shown)"
}

/** A name split into lowercase words, plus its words run together, for matching. */
internal class SearchName(val text: String) {
    val words: List<String> = searchWords(text)
    val compact: String = words.joinToString("")

    /** Where each word starts inside [compact]. */
    val wordStarts: List<Int> = words.runningFold(0) { at, word -> at + word.length }.dropLast(1)
}

/** Lowercase words: letters and digits, split at anything else ("3/4 Sit-Up" → 3, 4, sit, up). */
internal fun words(text: String): List<String> =
    text.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

private val NON_WORD = Regex("[^a-z0-9]+")

/** Fly, flye, flyes and flies are one word to search and to the duplicate checks. */
private val FLY_FORMS = mapOf("flye" to "fly", "flyes" to "fly", "flies" to "fly")

/** [words], with the fly forms as one ("Dumbbell Flyes" and "chest flies" both say fly). */
internal fun searchWords(text: String): List<String> = words(text).map { FLY_FORMS[it] ?: it }

/**
 * The extra spelling of a word he is still typing on its way to a fly form ("fli", "flie"): fly, since
 * names say fly; kept beside the word, which may be on its way to "flip" instead ("Tire Flip").
 */
private fun flySpelling(word: String): List<List<String>> {
    val onItsWay = word.length >= 3 && !"fly".startsWith(word) && FLY_FORMS.keys.any { it.startsWith(word) }
    return if (onItsWay) listOf(listOf("fly")) else emptyList()
}

private val NOT_EQUIPMENT = setOf("other", "body only", "none")

/** Equipment whose own words would mislead: every EZ-bar lift would answer "curl". */
private val EQUIPMENT_WORDS = mapOf("e-z curl bar" to listOf("ez", "bar"))

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
    // Chest flyes: the pec deck is "Butterfly"; cable ones include the crossover.
    "pec fly" to listOf("butterfly"),
    "machine fly" to listOf("butterfly"),
    "chest fly" to listOf("butterfly", "dumbbell fly", "cable fly", "cable crossover"),
    "cable fly" to listOf("cable crossover"),
    "calves" to listOf("calf"),
    "abs" to listOf("crunch", "ab roller", "sit up"),
    "farmer carry" to listOf("farmer walk"),
    "farmers carry" to listOf("farmer walk"),
    // The catalog's own spelling of the EZ bar ("E-Z Curl Bar"); its lifts say "EZ-Bar".
    "e z" to listOf("ez"),
)

private val ALIAS_PHRASES: Map<List<String>, List<List<String>>> =
    ALIASES.entries.associate { (typed, catalog) -> words(typed) to catalog.map(::words) }

private val LONGEST_ALIAS = ALIAS_PHRASES.keys.maxOf { it.size }

/** One thing he typed (a word, or a shorthand phrase) and the spellings that satisfy it. */
internal class Term(val spellings: List<List<String>>, private val whole: Boolean = false) {
    /**
     * Some spelling has each of its words start a word of [words], or of [extra]; or be one, for a
     * [whole] term: a one-letter word he has finished typing ("t" of "t bar") is a word, not the start
     * of "thrust".
     */
    fun matches(words: List<String>, extra: List<String> = emptyList()): Boolean =
        spellings.any { spelling ->
            spelling.all { part -> words.any { fits(it, part) } || extra.any { fits(it, part) } }
        }

    private fun fits(word: String, part: String) = if (whole) word == part else startsWithPart(word, part)
}

/** A typed one-letter word with more typed after it: finished, so it must be a whole word. */
private fun finishedLetter(typed: List<String>, at: Int): Boolean = typed[at].length == 1 && at < typed.lastIndex

/** [word] starts with [part], or with [part] less a plural ending ("curls" → curl, "presses" → press). */
private fun startsWithPart(word: String, part: String): Boolean =
    word.startsWith(part) || singulars(part).any { word.startsWith(it) }

private fun singulars(word: String): List<String> = buildList {
    if (word.length >= 4 && word.endsWith("es")) add(word.dropLast(2))
    if (word.length >= 4 && word.endsWith("s") && !word.endsWith("ss")) add(word.dropLast(1))
}

/** [word] less a plural ending, for finding shorthand: short shorthand drops a bare s too ("dbs"). */
private fun shorthandSingulars(word: String): List<String> =
    singulars(word) + listOfNotNull(word.dropLast(1).takeIf { word.length == 3 && word.endsWith("s") })

/** A typed word this long that begins a shorthand ("dum" of dumbbell) takes its spellings. */
private const val MIN_BEGUN_LENGTH = 3

/**
 * A parsed query: its terms, and every way of writing the whole query run together ([forms]): what he
 * typed, and each shorthand swapped for its spellings ("pec deck" is also "butterfly"), each also
 * without a plural ending. [forms] is what the exact and phrase fits compare against.
 */
internal class Query(val terms: List<Term>) {
    val forms: Set<String> = terms
        .fold(listOf("")) { heads, term ->
            heads.flatMap { head -> term.spellings.map { head + it.joinToString("") } }.take(MAX_FORMS)
        }
        .filter { it.isNotEmpty() }
        .flatMapTo(LinkedHashSet()) { listOf(it) + singulars(it) }
}

/** Whole-query spellings kept at most: a long query full of shorthand multiplies them. */
private const val MAX_FORMS = 64

internal fun parseQuery(raw: String): Query {
    val typed = searchWords(raw)
    val terms = mutableListOf<Term>()
    var at = 0
    while (at < typed.size) {
        // The longest shorthand here, also with its last word's plural ending off ("rdls", "pec decks").
        val found = (minOf(LONGEST_ALIAS, typed.size - at) downTo 1).asSequence()
            .map { typed.subList(at, at + it) }
            .firstNotNullOfOrNull { phrase ->
                (listOf(phrase) + shorthandSingulars(phrase.last()).map { phrase.dropLast(1) + it })
                    .firstOrNull { it in ALIAS_PHRASES }
                    ?.let { key -> phrase to key }
            }
        if (found != null) {
            val (phrase, key) = found
            terms += Term((listOf(phrase, key) + ALIAS_PHRASES.getValue(key)).distinct())
            at += phrase.size
        } else {
            // A word he is still typing takes a one-word shorthand's spellings too ("dumb" also finds DB).
            val word = typed[at]
            val begun = if (word.length < MIN_BEGUN_LENGTH) {
                emptyList()
            } else {
                ALIAS_PHRASES.filterKeys { it.size == 1 && it[0].startsWith(word) }.values.flatten()
            }
            terms += Term(listOf(listOf(word)) + begun + flySpelling(word), whole = finishedLetter(typed, at))
            at++
        }
    }
    return Query(terms)
}

/**
 * How well a name answers a query, best first: it is the query ([Query.forms]; plural endings aside);
 * the query runs on in it from the start of a word, as a phrase ("T-Bar Row with Handle" for "t-bar",
 * "Chin-Up" for "chinup"); or each query word starts one of its words, anywhere ("bench dumb").
 */
private enum class Fit { EXACT, PHRASE, WORDS }

private fun fit(name: SearchName, query: Query): Fit? = when {
    query.forms.isEmpty() -> null
    name.compact in query.forms || singulars(name.compact).any { it in query.forms } -> Fit.EXACT
    query.forms.any { form -> name.wordStarts.any { name.compact.startsWith(form, it) } } -> Fit.PHRASE
    query.terms.all { it.matches(name.words) } -> Fit.WORDS
    else -> null
}

/**
 * The rank of [entry] for [query], lower is better, or null when it doesn't match. Its best name
 * counts; at each [Fit] the name Lifts shows ranks just above its other names: 0 shown exact, 1 other
 * exact, 2 shown phrase, 3 other phrase, 4 shown words, 5 other words, then 6 when it matches only
 * once its equipment counts ("cable pushdown").
 */
internal fun rank(entry: CatalogEntry, query: Query): Int? {
    val shown = fit(entry.shownName, query)?.let { 2 * it.ordinal }
    val other = entry.otherNames.mapNotNull { fit(it, query) }.minOrNull()?.let { 2 * it.ordinal + 1 }
    listOfNotNull(shown, other).minOrNull()?.let { return it }
    val withEquipment = entry.equipmentWords.isNotEmpty() && (listOf(entry.shownName) + entry.otherNames).any { name ->
        query.terms.all { it.matches(name.words, entry.equipmentWords) }
    }
    return if (withEquipment) EQUIPMENT_RANK else null
}

private const val EQUIPMENT_RANK = 6

/** Ranks 0 and 1 are an exact name (shown or other). */
private const val LAST_EXACT_RANK = 1

/** Near misses: a name that misses exactly one of [terms] (three or more), for a query nothing else answers. */
private const val NEAR_MISS_RANK = 7
private const val NEAR_MISS_MIN_TERMS = 3

private fun nearMiss(entry: CatalogEntry, terms: List<Term>): Boolean =
    (listOf(entry.shownName) + entry.otherNames).any { name ->
        terms.count { !it.matches(name.words, entry.equipmentWords) } == 1
    }

/**
 * The words he typed, each its own term with its one-word shorthand ("db" is also dumbbell): near
 * misses count typed words, so "reverse cable fly" is three words even though "cable fly" is one
 * shorthand phrase, and a near miss must have two of them, not either of two terms.
 */
private fun wordTerms(raw: String): List<Term> {
    val typed = searchWords(raw)
    return typed.mapIndexed { at, word ->
        val keys = listOf(word) + shorthandSingulars(word)
        val spellings = listOf(listOf(word)) + keys.flatMap { ALIAS_PHRASES[listOf(it)].orEmpty() } + flySpelling(word)
        Term(spellings.distinct(), whole = finishedLetter(typed, at))
    }
}

/**
 * The entries that answer [raw], best first: lifts before stretches and jumps ("split squat" is a lift
 * before it is the "Split Squats" jump); then a name he typed exactly; then names that match, before
 * lifts that match only through their equipment ("machine press": Machine Shoulder Press before Leg
 * Press); within each, what he has done before, most recent first ([recentIds]), so "db press" finds
 * his DB Shoulder Press before closer phrases; then by [rank]; then shorter shown names, the plain lift
 * before its variants ("Overhead Press" before "Seated Barbell Military Press"); then by name. When
 * nothing answers, the names that miss exactly one of three or more terms, or one of three or more
 * typed words, are offered instead ("seated db shoulder press" finds "DB Shoulder Press").
 */
fun searchExercises(
    entries: List<CatalogEntry>,
    raw: String,
    recentIds: List<String> = emptyList(),
): List<CatalogEntry> {
    val query = parseQuery(raw)
    if (query.terms.isEmpty()) return emptyList()
    var ranked = entries.mapNotNull { entry -> rank(entry, query)?.let { entry to it } }
    // One missed term of three or more, or one missed word of three or more typed: shorthand can join two
    // typed words into one term ("reverse cable fly"), and splitting it loses what it bridges ("neutral grip").
    if (ranked.isEmpty()) {
        val typedTerms = wordTerms(raw)
        ranked = entries
            .filter { entry ->
                (query.terms.size >= NEAR_MISS_MIN_TERMS && nearMiss(entry, query.terms)) ||
                    (typedTerms.size >= NEAR_MISS_MIN_TERMS && nearMiss(entry, typedTerms))
            }
            .map { it to NEAR_MISS_RANK }
    }
    val recency = recentIds.withIndex().associate { (index, id) -> id to index }
    return ranked
        .sortedWith(
            compareBy<Pair<CatalogEntry, Int>> { if (it.first.isLift) 0 else 1 }
                // A name he typed exactly wins; below that, what he did recently beats a closer fit, among
                // names that match: a match through equipment alone ("machine" for Leg Press) comes after.
                .thenBy {
                    when {
                        it.second <= LAST_EXACT_RANK -> 0
                        it.second < EQUIPMENT_RANK -> 1
                        else -> 2
                    }
                }
                .thenBy { recency[it.first.id] ?: Int.MAX_VALUE }
                .thenBy { it.second }
                .thenBy { it.first.shownName.words.size }
                .thenBy { it.first.shown.lowercase() }
                .thenBy { it.first.id },
        )
        .map { it.first }
}

/**
 * A search turned into a new exercise's name: each word capitalised, shorthand in capitals ("db
 * hip thrust" is "DB Hip Thrust"), and a word he typed with a capital in it kept as he typed it
 * ("McGill curl-up" stays). Spaces collapse; hyphens and other marks stay where he put them.
 */
fun nameFromQuery(raw: String): String =
    raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { word ->
        when {
            word.any { it.isUpperCase() } -> word
            isShorthand(word) -> word.uppercase()
            // A plural of shorthand keeps its s small: "rdls" is RDLs.
            word.endsWith("s") && isShorthand(word.dropLast(1)) -> word.dropLast(1).uppercase() + "s"
            else -> word.replaceFirstChar { it.uppercase() }
        }
    }

/** Shorthand written in capitals in a name (DB, RDL, OHP); "abs" and "dumbbell" are words. */
private val CAPITALS = setOf("db", "bb", "kb", "rdl", "sldl", "ohp", "bss")

private fun isShorthand(word: String): Boolean = word.lowercase() in CAPITALS

/**
 * The picker's body-part chips, in gym order, over the catalog's 17 muscles. Neck has no chip: its 8
 * exercises stay under All and search.
 */
enum class BodyPart(val label: String, val muscles: Set<String>) {
    CHEST("Chest", setOf("chest")),
    BACK("Back", setOf("lats", "middle back", "lower back", "traps")),
    LEGS("Legs", setOf("quadriceps", "hamstrings", "glutes", "calves", "adductors", "abductors")),
    SHOULDERS("Shoulders", setOf("shoulders")),
    ARMS("Arms", setOf("biceps", "triceps", "forearms")),
    CORE("Core", setOf("abdominals")),
}

/** The chip a catalog muscle ("middle back") belongs to; null for neck and anything unknown. */
fun bodyPartOf(muscle: String): BodyPart? {
    val key = muscle.trim().lowercase()
    return BodyPart.entries.firstOrNull { key in it.muscles }
}

/** The entries whose primary muscle is in [part], in the order given. */
fun inBodyPart(entries: List<CatalogEntry>, part: BodyPart): List<CatalogEntry> =
    entries.filter { entry -> entry.primaryMuscles.any { bodyPartOf(it) == part } }

/**
 * The exercise, other than [exerciseId], that already answers to what renaming it to [target] would
 * store or show: a lift reset to its catalog name shows its short name ("Dumbbell Shrug" shows "DB
 * Shrug"), and that one must be free too. Null when both are.
 */
fun renameClash(exerciseId: String, isCustom: Boolean, target: String, entries: List<CatalogEntry>): CatalogEntry? =
    nameClash(target, entries, exerciseId)
        ?: nameClash(displayName(exerciseId, target, isCustom), entries, exerciseId)

/**
 * A name's keys, as duplicate checks compare names: its words run together, so case, spaces and
 * punctuation don't count, plus the same without a plural ending, as search reads them ("Crunches" is
 * also crunch, "Pull-Ups" also pullup); fly, flye, flyes and flies are all fly. Two names are one when
 * their keys meet ([sameName]): "Bench Presses" is Bench Press, "Dumbbell Fly" is Dumbbell Flyes.
 */
fun nameKeys(name: String): Set<String> {
    val compact = searchWords(name).joinToString("")
    return if (compact.isEmpty()) emptySet() else setOf(compact) + singulars(compact)
}

/** [a] and [b] are one name to the duplicate checks ([nameKeys]). */
fun sameName(a: String, b: String): Boolean = nameKeys(a).any { it in nameKeys(b) }

/**
 * The exercise, other than [selfId], that already answers to [name] (the name Lifts shows for it,
 * its stored name, or its catalog name); null when the name is free. Two exercises with one name
 * would split his history, and the Strong CSV keys exercises by name.
 */
fun nameClash(name: String, entries: List<CatalogEntry>, selfId: String? = null): CatalogEntry? {
    val keys = nameKeys(name)
    if (keys.isEmpty()) return null
    return entries.firstOrNull { entry -> entry.id != selfId && entry.claimedKeys.any { it in keys } }
}
