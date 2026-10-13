package com.yzinsta.downloader.data.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [DownloadHistory] in a small SQLite table (no Room: one table doesn't need it). */
class SqliteDownloadHistory(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "history.db", null, 1), DownloadHistory {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE downloads (
                owner TEXT NOT NULL,
                item_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                files INTEGER NOT NULL,
                downloaded_at INTEGER NOT NULL,
                PRIMARY KEY (owner, item_id))"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override suspend fun record(owner: String, itemId: String, kind: String, files: Int) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("owner", owner.lowercase())
            put("item_id", itemId)
            put("kind", kind)
            put("files", files)
            put("downloaded_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("downloads", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    override suspend fun knownIds(owner: String): Set<String> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT item_id FROM downloads WHERE owner = ?", arrayOf(owner.lowercase()))
            .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }
}
