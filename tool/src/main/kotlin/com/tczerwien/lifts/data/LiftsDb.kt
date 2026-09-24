package com.tczerwien.lifts.data

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
