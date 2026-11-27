package com.ai.playtv.model.database.source

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.icu.util.VersionInfo
import android.os.Build
import com.ai.playtv.model.bean.domain.source.ImportedSource
import com.ai.playtv.model.bean.domain.source.MAX_IMPORTED_SOURCES
import com.ai.playtv.model.bean.domain.source.SourceHistoryEntry
import com.ai.playtv.model.bean.domain.source.SourceKind
import com.ai.playtv.model.bean.local.source.StoredSearchData
import java.io.File

/** Installation-scoped debug data. All calls are serialized by SourceHistoryStore on IO. */
internal class SourceHistoryDatabase(directory: File) : AutoCloseable {
    private val db: SQLiteDatabase

    init {
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory) { "History directory unavailable" }
        val opened = SQLiteDatabase.openOrCreateDatabase(File(directory, "history.db"), null)
        try {
            opened.setMaximumSize(64L * 1024 * 1024)
            opened.setForeignKeyConstraintsEnabled(true)
            when (opened.version) {
                0 -> {
                    opened.beginTransaction()
                    try {
                        opened.execSQL("CREATE TABLE sources (id TEXT PRIMARY KEY NOT NULL, input TEXT NOT NULL, last_used INTEGER NOT NULL, partial INTEGER NOT NULL)")
                        opened.execSQL("CREATE INDEX sources_last_used ON sources(last_used DESC)")
                        opened.execSQL("CREATE TABLE items (source_id TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE, position INTEGER NOT NULL, item_id TEXT NOT NULL, name TEXT NOT NULL, url TEXT NOT NULL, kind TEXT NOT NULL, channel_id TEXT, logo_url TEXT, search_name TEXT NOT NULL, PRIMARY KEY(source_id, position))")
                        opened.execSQL("CREATE INDEX items_search_name ON items(search_name)")
                        opened.execSQL("CREATE TABLE search_names (name TEXT PRIMARY KEY NOT NULL, version INTEGER NOT NULL, pinyin TEXT, initials TEXT, restricted INTEGER)")
                        opened.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
                        opened.version = SCHEMA_VERSION
                        opened.setTransactionSuccessful()
                    } finally { opened.endTransaction() }
                }
                1 -> {
                    // Early v1 builds shipped before the engine metadata table was added.
                    opened.beginTransaction()
                    try {
                        opened.execSQL("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
                        opened.version = SCHEMA_VERSION
                        opened.setTransactionSuccessful()
                    } finally { opened.endTransaction() }
                }
                SCHEMA_VERSION -> Unit
                else -> error("Unsupported history database version")
            }
            opened.execSQL("UPDATE search_names SET version = ?, pinyin = NULL, initials = NULL, restricted = NULL WHERE version != ?",
                arrayOf(SEARCH_VERSION, SEARCH_VERSION))
            val engine = "${Build.VERSION.SDK_INT}/${VersionInfo.ICU_VERSION}"
            val savedEngine = opened.rawQuery("SELECT value FROM metadata WHERE key = 'pinyin_engine'", null).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (savedEngine != engine) {
                opened.beginTransaction()
                try {
                    opened.execSQL("UPDATE search_names SET pinyin = NULL, initials = NULL")
                    opened.execSQL("INSERT OR REPLACE INTO metadata(key, value) VALUES('pinyin_engine', ?)", arrayOf(engine))
                    opened.setTransactionSuccessful()
                } finally { opened.endTransaction() }
            }
            db = opened
        } catch (failure: Throwable) {
            opened.close()
            throw failure
        }
    }

    fun read(): List<SourceHistoryEntry> {
        val sourcesById = linkedMapOf<String, SourceHistoryEntry>()
        val itemsBySource = mutableMapOf<String, MutableList<ImportedSource>>()
        db.beginTransactionNonExclusive()
        try {
            db.rawQuery("SELECT id, input, last_used, partial FROM sources ORDER BY last_used DESC, rowid DESC", null).use { sources ->
                while (sources.moveToNext()) {
                    val id = sources.getString(0)
                    sourcesById[id] = SourceHistoryEntry(id, sources.getString(1), sources.getLong(2), emptyList(), sources.getInt(3) != 0)
                }
            }
            db.rawQuery("SELECT source_id, item_id, name, url, kind, channel_id, logo_url FROM items ORDER BY source_id, position", null).use { rows ->
                while (rows.moveToNext()) itemsBySource.getOrPut(rows.getString(0)) { mutableListOf() }.add(
                    ImportedSource(rows.getString(1), rows.getString(2), rows.getString(3), SourceKind.valueOf(rows.getString(4)),
                        if (rows.isNull(5)) null else rows.getString(5), if (rows.isNull(6)) null else rows.getString(6)))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return sourcesById.values.map { it.copy(items = itemsBySource[it.id].orEmpty()) }
    }

    /** One transaction updates only the changed source and records pending names with its items. */
    fun write(entry: SourceHistoryEntry, previous: SourceHistoryEntry?, finish: (() -> Unit) -> Boolean): Boolean {
        require(entry.items.size <= MAX_IMPORTED_SOURCES) { "History source exceeds item capacity" }
        db.beginTransaction()
        var ended = false
        try {
            val values = ContentValues().apply {
                put("id", entry.id); put("input", entry.input); put("last_used", entry.lastUsedAtMs)
                put("partial", if (entry.partial) 1 else 0)
            }
            if (db.update("sources", values, "id = ?", arrayOf(entry.id)) == 0)
                db.insertOrThrow("sources", null, values)
            if (previous == null || previous.items != entry.items) {
                val itemStatement = db.compileStatement("INSERT OR REPLACE INTO items(source_id, position, item_id, name, url, kind, channel_id, logo_url, search_name) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)")
                val nameStatement = db.compileStatement("INSERT OR IGNORE INTO search_names(name, version) VALUES(?, ?)")
                try {
                    entry.items.forEachIndexed { position, item ->
                        if (previous?.items?.getOrNull(position) == item) return@forEachIndexed
                        val name = item.name.take(512)
                        itemStatement.clearBindings()
                        itemStatement.bindString(1, entry.id); itemStatement.bindLong(2, position.toLong())
                        itemStatement.bindString(3, item.id); itemStatement.bindString(4, item.name)
                        itemStatement.bindString(5, item.url); itemStatement.bindString(6, item.kind.name)
                        if (item.channelId == null) itemStatement.bindNull(7) else itemStatement.bindString(7, item.channelId)
                        if (item.logoUrl == null) itemStatement.bindNull(8) else itemStatement.bindString(8, item.logoUrl)
                        itemStatement.bindString(9, name); itemStatement.executeInsert()
                        nameStatement.clearBindings(); nameStatement.bindString(1, name)
                        nameStatement.bindLong(2, SEARCH_VERSION.toLong()); nameStatement.executeInsert()
                    }
                } finally { itemStatement.close(); nameStatement.close() }
                db.delete("items", "source_id = ? AND position >= ?", arrayOf(entry.id, entry.items.size.toString()))
                val currentNames = entry.items.mapTo(mutableSetOf()) { it.name.take(512) }
                val removedNames = previous?.items.orEmpty().mapTo(mutableSetOf()) { it.name.take(512) } - currentNames
                removedNames.forEach { name ->
                    db.execSQL("DELETE FROM search_names WHERE name = ? AND NOT EXISTS (SELECT 1 FROM items WHERE search_name = ?)", arrayOf(name, name))
                }
            }
            return finish {
                db.setTransactionSuccessful()
                ended = true
                db.endTransaction()
            }
        } finally { if (!ended) db.endTransaction() }
    }

    fun searchData(): Map<String, StoredSearchData> {
        val result = mutableMapOf<String, StoredSearchData>()
        db.rawQuery("SELECT name, pinyin, initials, restricted FROM search_names WHERE version = ?", arrayOf(SEARCH_VERSION.toString())).use { rows ->
            while (rows.moveToNext()) result[rows.getString(0)] = StoredSearchData(
                if (rows.isNull(1)) null else rows.getString(1), if (rows.isNull(2)) null else rows.getString(2),
                if (rows.isNull(3)) null else rows.getInt(3) != 0)
        }
        return result
    }

    fun saveSearchData(values: Map<String, StoredSearchData>, canCommit: () -> Boolean) {
        db.beginTransaction()
        try {
            values.forEach { (name, data) ->
                val update = ContentValues().apply {
                    if (data.pinyin != null && data.initials != null) { put("pinyin", data.pinyin); put("initials", data.initials) }
                    data.restricted?.let { put("restricted", if (it) 1 else 0) }
                }
                if (update.size() > 0) db.update("search_names", update, "name = ? AND version = ?",
                    arrayOf(name, SEARCH_VERSION.toString()))
            }
            if (canCommit()) db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override fun close() = db.close()

    private companion object { const val SCHEMA_VERSION = 2; const val SEARCH_VERSION = 1 }
}
