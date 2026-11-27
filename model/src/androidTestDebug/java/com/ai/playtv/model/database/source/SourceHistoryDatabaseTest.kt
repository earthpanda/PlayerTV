package com.ai.playtv.model.database.source

import android.test.AndroidTestCase
import android.database.sqlite.SQLiteDatabase
import com.ai.playtv.model.bean.domain.source.ImportedSource
import com.ai.playtv.model.bean.domain.source.SourceHistoryEntry
import com.ai.playtv.model.bean.domain.source.SourceKind
import com.ai.playtv.model.bean.local.source.StoredSearchData
import java.io.File
import java.util.UUID

/** Exercises real SQLite transactions and reopen behavior on an Android device. */
class SourceHistoryDatabaseTest : AndroidTestCase() {
    private lateinit var directory: File

    override fun setUp() {
        super.setUp()
        directory = File(context.cacheDir, "history-db-test-${UUID.randomUUID()}")
    }

    override fun tearDown() {
        directory.deleteRecursively()
        super.tearDown()
    }

    fun testReopenAndIncrementalUpdatePreserveOrderAndPreparedNames() {
        val first = item("one", "First")
        val second = item("two", "Second")
        val old = SourceHistoryEntry("source", "https://example.org/list", 1, listOf(first, second), false)
        val database = SourceHistoryDatabase(directory)
        assertTrue(database.write(old, null) { finish -> finish(); true })
        database.saveSearchData(mapOf("First" to StoredSearchData("first", "f", false))) { true }
        val updated = old.copy(items = listOf(second, item("three", "Third")))
        assertTrue(database.write(updated, old) { finish -> finish(); true })
        assertFalse(database.searchData().containsKey("First"))
        assertTrue(database.searchData().containsKey("Second"))
        database.saveSearchData(mapOf("Second" to StoredSearchData("second", "s", false))) { true }
        database.close()

        val reopened = SourceHistoryDatabase(directory)
        try {
            assertEquals(listOf(updated), reopened.read())
            assertEquals(StoredSearchData("second", "s", false), reopened.searchData()["Second"])
            assertEquals(StoredSearchData(), reopened.searchData()["Third"])
        } finally { reopened.close() }
    }

    fun testRejectedTransactionAndStalePreparedDataCannotChangeHistory() {
        val old = SourceHistoryEntry("source", "https://example.org/list", 1, listOf(item("one", "Old")), false)
        val database = SourceHistoryDatabase(directory)
        try {
            assertTrue(database.write(old, null) { finish -> finish(); true })
            val replacement = old.copy(items = listOf(item("one", "New")))
            assertFalse(database.write(replacement, old) { _ -> false })
            assertEquals(listOf(old), database.read())
            assertTrue(database.write(replacement, old) { finish -> finish(); true })
            database.saveSearchData(mapOf("Old" to StoredSearchData("old", "o", false))) { true }
            assertFalse(database.searchData().containsKey("Old"))
            assertEquals(StoredSearchData(), database.searchData()["New"])
        } finally { database.close() }
    }

    fun testSharedNameSurvivesRemovalFromOneSource() {
        val first = SourceHistoryEntry("first", "https://example.org/first", 1, listOf(item("one", "Shared")), false)
        val second = SourceHistoryEntry("second", "https://example.org/second", 2, listOf(item("two", "Shared")), false)
        val database = SourceHistoryDatabase(directory)
        try {
            assertTrue(database.write(first, null) { finish -> finish(); true })
            assertTrue(database.write(second, null) { finish -> finish(); true })
            database.saveSearchData(mapOf("Shared" to StoredSearchData("shared", "s", false))) { true }
            assertTrue(database.write(first.copy(items = emptyList()), first) { finish -> finish(); true })
            assertEquals(StoredSearchData("shared", "s", false), database.searchData()["Shared"])
            assertEquals(listOf(second, first.copy(items = emptyList())), database.read())
        } finally { database.close() }
    }

    fun testUpgradeFromVersionOnePreservesHistoryAndRebuildsPinyin() {
        assertTrue(directory.mkdirs())
        val old = SQLiteDatabase.openOrCreateDatabase(File(directory, "history.db"), null)
        try {
            old.execSQL("CREATE TABLE sources (id TEXT PRIMARY KEY NOT NULL, input TEXT NOT NULL, last_used INTEGER NOT NULL, partial INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE items (source_id TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE, position INTEGER NOT NULL, item_id TEXT NOT NULL, name TEXT NOT NULL, url TEXT NOT NULL, kind TEXT NOT NULL, channel_id TEXT, logo_url TEXT, search_name TEXT NOT NULL, PRIMARY KEY(source_id, position))")
            old.execSQL("CREATE TABLE search_names (name TEXT PRIMARY KEY NOT NULL, version INTEGER NOT NULL, pinyin TEXT, initials TEXT, restricted INTEGER)")
            old.execSQL("INSERT INTO sources VALUES ('source', 'https://example.org/list', 1, 0)")
            old.execSQL("INSERT INTO items VALUES ('source', 0, 'one', 'First', 'https://example.org/one', 'UNKNOWN', NULL, NULL, 'First')")
            old.execSQL("INSERT INTO search_names VALUES ('First', 1, 'first', 'f', 0)")
            old.version = 1
        } finally { old.close() }
        val upgraded = SourceHistoryDatabase(directory)
        try {
            assertEquals(listOf(SourceHistoryEntry("source", "https://example.org/list", 1,
                listOf(item("one", "First")), false)), upgraded.read())
            assertEquals(StoredSearchData(restricted = false), upgraded.searchData()["First"])
        } finally { upgraded.close() }
    }

    private fun item(id: String, name: String) = ImportedSource(id, name, "https://example.org/$id", SourceKind.UNKNOWN)
}
