package com.tczerwien.lifts.backup

/**
 * Which Lifts wrote a backup: two rows in its lifts_settings table beside the settings (RG-1), and the restore
 * guard they make possible (RG-2). A backup's schema version stops one from a newer database ([Restorer]); these
 * stop one from a newer Lifts on the same database, which would restore every row but silently drop the settings
 * this Lifts doesn't know ([com.tczerwien.lifts.settings.SettingsRows.decode] reads only the keys it knows), so
 * they would be gone from every backup after it too. They describe the file, not a setting: nothing reads them
 * into DataStore. Pure, covered by MadeByTest.
 */
object MadeBy {

    /** The writing Lifts' versionCode, which rises with every release ("19"): what the guard compares. */
    const val KEY_VERSION_CODE = "lifts_made_by_version_code"

    /** Its versionName, for the refusal's words ("0.19.0"). */
    const val KEY_VERSION_NAME = "lifts_made_by_version_name"

    /** The rows a backup written by the Lifts of [versionCode] and [versionName] carries. */
    fun rows(versionCode: Int, versionName: String): Map<String, String> =
        mapOf(KEY_VERSION_CODE to versionCode.toString(), KEY_VERSION_NAME to versionName)

    /**
     * Why a backup ([settingsRows]) can't be restored into this Lifts ([appVersionCode], [appVersionName]): a newer
     * one made it. Null when it may be: made by this Lifts or an older one, or with no version row at all, as every
     * backup from before 0.19.0 is (each older than any Lifts that reads this row), and a database pulled over adb.
     * A version that isn't a whole number counts as none: Lifts never writes one, and a hand-edited file shouldn't
     * be turned away for a row that says nothing.
     */
    fun refusal(settingsRows: Map<String, String>, appVersionCode: Int, appVersionName: String): String? {
        val code = settingsRows[KEY_VERSION_CODE]?.trim()?.toIntOrNull() ?: return null
        if (code <= appVersionCode) return null
        val name = settingsRows[KEY_VERSION_NAME]?.trim()?.takeIf { it.isNotEmpty() } ?: "version $code"
        return "a newer Lifts made it ($name; this is $appVersionName); update Lifts first"
    }
}
