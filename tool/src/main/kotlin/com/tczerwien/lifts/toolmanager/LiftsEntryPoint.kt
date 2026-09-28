package com.tczerwien.lifts.toolmanager

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.toolmanager.ClientToolManifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@EntryPoint
object LiftsEntryPoint : LightEntryPoint {

    private val _inbox = MutableStateFlow(0L)

    /**
     * Bumped whenever files change through Tool Manager (an upload into Restore, say). The hook gets
     * no context, so it can't open the database; screens watch this and look for themselves.
     */
    val inbox: StateFlow<Long> = _inbox.asStateFlow()

    override fun getToolManagerManifest(): ClientToolManifest = LiftsToolManager.manifest()

    override suspend fun onToolManagerDataUpdate() {
        _inbox.value = System.currentTimeMillis()
    }
}
