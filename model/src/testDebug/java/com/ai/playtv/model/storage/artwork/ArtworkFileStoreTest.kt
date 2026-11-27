package com.ai.playtv.model.storage.artwork

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.File
import java.util.Base64

class ArtworkFileStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val image = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/V1sAAAAASUVORK5CYII=")
    private fun input(bytes: ByteArray = image) = ByteArrayInputStream(bytes)

    @Test fun savesSeparatelyAndDeletesOnlyTheRequestedOwner() = runBlocking {
        val root = temporary.newFolder("artwork")
        val store = ArtworkFileStore(root, 1024, 4096)
        val first = store.ticket("source", "one")
        val second = store.ticket("source", "two")
        val other = store.ticket("other", "one")
        store.save(first, ArtworkSlot.SMALL, input())
        store.save(first, ArtworkSlot.BACKGROUND, input())
        store.save(second, ArtworkSlot.SMALL, input())
        store.save(other, ArtworkSlot.SMALL, input())
        store.deleteItem("source", "one")
        assertNull(store.find("source", "one", ArtworkSlot.SMALL))
        assertNull(store.find("source", "one", ArtworkSlot.BACKGROUND))
        assertNotNull(store.find("source", "two", ArtworkSlot.SMALL))
        try { store.save(first, ArtworkSlot.SMALL, input()); fail("stale ticket") } catch (_: IOException) { }
        store.deleteSource("source")
        assertNull(store.find("source", "two", ArtworkSlot.SMALL))
        assertNotNull(store.find("other", "one", ArtworkSlot.SMALL))
        try { store.save(second, ArtworkSlot.SMALL, input()); fail("stale source") } catch (_: IOException) { }
    }

    @Test fun invalidOrOversizedReplacementPreservesCommittedImage() = runBlocking {
        val store = ArtworkFileStore(temporary.newFolder("artwork"), 128, 256)
        val ticket = store.ticket("source", "item")
        val original = store.save(ticket, ArtworkSlot.SMALL, input())
        try { store.save(ticket, ArtworkSlot.SMALL, input("<html>bad</html>".toByteArray())); fail("invalid image") } catch (_: IOException) { }
        assertArrayEquals(image, original.readBytes())
        try { store.save(ticket, ArtworkSlot.SMALL, input(ByteArray(129))); fail("size limit") } catch (_: IOException) { }
        assertArrayEquals(image, original.readBytes())
    }

    @Test fun capacityFailureDoesNotEvictExistingImages() = runBlocking {
        val store = ArtworkFileStore(temporary.newFolder("artwork"), 100, 100)
        val first = store.ticket("source", "one")
        val second = store.ticket("source", "two")
        store.save(first, ArtworkSlot.SMALL, input())
        try { store.save(second, ArtworkSlot.SMALL, input()); fail("capacity") } catch (_: IOException) { }
        assertNotNull(store.find("source", "one", ArtworkSlot.SMALL))
        assertNull(store.find("source", "two", ArtworkSlot.SMALL))
    }

    @Test fun encodedOutputIsCommittedAndEncoderFailureKeepsPreviousFile() = runBlocking {
        val store = ArtworkFileStore(temporary.newFolder("artwork"), 1024, 4096)
        val ticket = store.ticket("source", "item")
        val encoded = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50)
        val file = store.save(ticket, ArtworkSlot.SMALL, input(), ArtworkImageEncoder { _, output, _ ->
            output.writeBytes(encoded); true
        })
        assertArrayEquals(encoded, file.readBytes())
        try {
            store.save(ticket, ArtworkSlot.SMALL, input(), ArtworkImageEncoder { _, _, _ -> throw IOException("encoder failed") })
            fail("encoding failure")
        } catch (_: IOException) { }
        assertArrayEquals(encoded, file.readBytes())
    }

    @Test fun interruptedItemDeletionRecoversBeforeAnotherWrite() = runBlocking {
        val root = temporary.newFolder("artwork")
        val store = ArtworkFileStore(root, 1024, 4096)
        val original = store.save(store.ticket("source", "one"), ArtworkSlot.SMALL, input())
        store.save(store.ticket("source", "one"), ArtworkSlot.BACKGROUND, input())
        store.save(store.ticket("source", "two"), ArtworkSlot.SMALL, input())
        val itemHash = original.name.substringBefore('.')
        val marker = File(original.parentFile, "delete-item-$itemHash")
        assertTrue(marker.createNewFile())
        val restarted = ArtworkFileStore(root, 1024, 4096)
        val newTicket = restarted.ticket("source", "one")
        assertNull(restarted.find("source", "one", ArtworkSlot.SMALL))
        assertNull(restarted.find("source", "one", ArtworkSlot.BACKGROUND))
        assertNotNull(restarted.find("source", "two", ArtworkSlot.SMALL))
        assertFalse(marker.exists())
        restarted.save(newTicket, ArtworkSlot.SMALL, input())
        assertNotNull(restarted.find("source", "one", ArtworkSlot.SMALL))
    }

    @Test fun failedSourceDeletionRetainsMarkerAndRetriesWithoutDeletingUnknownFiles() = runBlocking {
        val root = temporary.newFolder("artwork")
        val store = ArtworkFileStore(root, 1024, 4096)
        val original = store.save(store.ticket("source", "one"), ArtworkSlot.SMALL, input())
        store.save(store.ticket("other", "one"), ArtworkSlot.SMALL, input())
        val unknown = File(original.parentFile, "keep.txt").apply { writeText("keep") }
        try { store.deleteSource("source"); fail("unmanaged file expected") } catch (_: IOException) { }
        val marker = File(original.parentFile, "delete-source")
        assertTrue(marker.isFile)
        assertTrue(unknown.isFile)
        val restarted = ArtworkFileStore(root, 1024, 4096)
        try { restarted.ticket("other", "one"); fail("pending cleanup expected") } catch (_: IOException) { }
        assertTrue(unknown.delete())
        assertNotNull(restarted.find("other", "one", ArtworkSlot.SMALL))
        assertNull(restarted.find("source", "one", ArtworkSlot.SMALL))
        assertFalse(marker.exists())
    }
}
