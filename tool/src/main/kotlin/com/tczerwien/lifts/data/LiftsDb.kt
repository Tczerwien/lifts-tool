package com.tczerwien.lifts.data

import com.tczerwien.lifts.seed.BundledCatalog
import com.tczerwien.lifts.seed.CatalogSource
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase

/**
 * The app's one database owner. Every screen gets its repository here, so exactly one Room
 * instance is ever opened on lifts.db, whichever screen a process happens to start on. This is
 * the only SDK-aware file in data/; [LiftsRepository] itself stays SDK-free.
 */
fun SealedLightContext.liftsRepository(): LiftsRepository = LiftsRepository.getInstance {
    buildDatabase(LiftsDatabase::class.java, LiftsRepository.DATABASE_NAME)
}

/**
 * The bundled exercise catalog as the APK carries it (assets/[BundledCatalog.ASSET]), read when asked.
 * Blocking: call [CatalogSource.read] off the main thread. It holds this context only as long as its
 * holder (a view model), never process-wide.
 */
fun SealedLightContext.bundledCatalog(): CatalogSource = CatalogSource { readAsset(BundledCatalog.ASSET).decodeToString() }
