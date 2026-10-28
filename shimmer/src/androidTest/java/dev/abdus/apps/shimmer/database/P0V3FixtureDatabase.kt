package dev.abdus.apps.shimmer.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/** Frozen v3 Room fixture used only by device migration tests. */
@Database(entities = [FolderEntity::class, ImageEntity::class], version = 3, exportSchema = true)
@TypeConverters(UriConverters::class)
abstract class P0V3FixtureDatabase : RoomDatabase()
