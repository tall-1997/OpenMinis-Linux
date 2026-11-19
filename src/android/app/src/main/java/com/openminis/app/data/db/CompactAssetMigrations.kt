package com.openminis.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object CompactAssetMigrations {
    val MIGRATION_34_35 = object : Migration(34, 35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.addColumnIfMissing("compact_markers", "assets_json", "TEXT")
        }
    }
}
