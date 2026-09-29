package com.tczerwien.lifts.ui

import androidx.compose.foundation.lazy.LazyListState

/**
 * A list's scroll position, held by the screen object. The SDK composes only the screen on top, and a
 * screen's composition (with anything it remembers) is dropped when an editor or another screen covers
 * it; the screen object stays on the back stack, so a list scrolled here is where he left it when he
 * comes back. A new [key] (another chip, a new search) starts the list over at the top.
 */
class KeptListState {
    private var key: Any? = null
    private var state = LazyListState()

    /** The state for [key]: the same one while the key stays, a fresh one at the top when it changes. */
    fun forKey(key: Any?): LazyListState {
        if (key != this.key) {
            this.key = key
            state = LazyListState()
        }
        return state
    }
}
