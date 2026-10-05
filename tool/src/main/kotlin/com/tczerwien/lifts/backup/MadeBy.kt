package com.tczerwien.lifts.backup

/**
 * Which Lifts wrote a backup: two rows in its lifts_settings table beside the settings (RG-1). A backup's schema
 * version tells a newer database apart; these tell a newer Lifts on the same database apart, which would restore
 * every row but silently drop the settings this Lifts doesn't know ([com.tczerwien.lifts.settings.SettingsRows.decode]
 * reads only the keys it knows). They describe the file, not a setting: nothing reads them into DataStore. Pure,
 * covered by MadeByTest.
 */
object MadeBy {

    /** The writing Lifts' versionCode, which rises with every release ("19"). */
    const val KEY_VERSION_CODE = "lifts_made_by_version_code"

    /** Its versionName, to name it in words ("0.19.0"). */
    const val KEY_VERSION_NAME = "lifts_made_by_version_name"

    /** The rows a backup written by the Lifts of [versionCode] and [versionName] carries. */
    fun rows(versionCode: Int, versionName: String): Map<String, String> =
        mapOf(KEY_VERSION_CODE to versionCode.toString(), KEY_VERSION_NAME to versionName)
}
