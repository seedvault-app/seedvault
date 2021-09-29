package org.calyxos.backup.storage.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
internal class ChunksCacheTest {

    private lateinit var chunksCache: ChunksCache
    private lateinit var db: Db

    private val chunk1 = CachedChunk("id1", 1, Random.nextLong())
    private val chunk2 = CachedChunk("id2", 2, Random.nextLong())
    private val chunk3 = CachedChunk("id3", 3, Random.nextLong())

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, Db::class.java).build()
        chunksCache = db.getChunksCache()
        chunksCache.insert(chunk1)
        chunksCache.insert(chunk2)
        chunksCache.insert(chunk3)
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testInsertAndGet() {
        assertThat(chunksCache.get(chunk1.id), equalTo(chunk1))
        assertThat(chunksCache.get(chunk2.id), equalTo(chunk2))
        assertThat(chunksCache.get(chunk3.id), equalTo(chunk3))
    }

    @Test
    fun testInsertAndDelete() {
        chunksCache.deleteChunks(listOf(chunk1))
        assertThat(chunksCache.get(chunk1.id), equalTo(null))
        assertThat(chunksCache.get(chunk2.id), equalTo(chunk2))
        assertThat(chunksCache.get(chunk3.id), equalTo(chunk3))

        chunksCache.deleteChunks(listOf(chunk2, chunk3))
        assertThat(chunksCache.get(chunk1.id), equalTo(null))
        assertThat(chunksCache.get(chunk2.id), equalTo(null))
        assertThat(chunksCache.get(chunk3.id), equalTo(null))

        chunksCache.insert(chunk1)
        chunksCache.deleteChunks(listOf(chunk1.copy(refCount = 1337)))
        assertThat(chunksCache.get(chunk1.id), equalTo(null))
    }

    @Test
    fun testRefCounts() {
        assertThat(chunksCache.getUnreferencedChunks(), equalTo(emptyList()))

        chunksCache.decrementRefCount(listOf(chunk1.id, chunk2.id))
        chunksCache.decrementRefCount(listOf(chunk2.id))
        assertThat(
            chunksCache.getUnreferencedChunks(),
            equalTo(listOf(chunk1.copy(refCount = 0), chunk2.copy(refCount = 0)))
        )

        chunksCache.decrementRefCount(listOf(chunk3.id))
        chunksCache.decrementRefCount(listOf(chunk3.id))
        chunksCache.decrementRefCount(listOf(chunk3.id))
        assertThat(
            chunksCache.getUnreferencedChunks(),
            equalTo(
                listOf(
                    chunk1.copy(refCount = 0),
                    chunk2.copy(refCount = 0),
                    chunk3.copy(refCount = 0)
                )
            )
        )

        chunksCache.incrementRefCount(listOf(chunk1.id, chunk2.id))
        assertThat(chunksCache.getUnreferencedChunks(), equalTo(listOf(chunk3.copy(refCount = 0))))
    }

    @Test
    fun testAreAllAvailableChunksCached() {
        assertTrue(chunksCache.areAllAvailableChunksCached(db, listOf()))
        assertTrue(chunksCache.areAllAvailableChunksCached(db, listOf("id1")))
        assertTrue(chunksCache.areAllAvailableChunksCached(db, listOf("id1", "id2")))
        assertTrue(chunksCache.areAllAvailableChunksCached(db, listOf("id1", "id2", "id3")))
        assertTrue(chunksCache.areAllAvailableChunksCached(db, listOf("id1", "id2", "id3")))
        assertFalse(chunksCache.areAllAvailableChunksCached(db, listOf("id1", "id2", "id3", "id4")))
        assertFalse(chunksCache.areAllAvailableChunksCached(db, listOf("foo", "bar")))
    }

    @Test
    fun testClearAndRepopulate() {
        val newChunks = listOf(
            chunk1.copy(id = "newId1", refCount = 4),
            chunk2.copy(id = "newId2", refCount = 6),
            chunk3.copy(id = "newId3", refCount = 8)
        )
        chunksCache.clearAndRepopulate(db, newChunks)

        assertNull(chunksCache.get("id1"))
        assertNull(chunksCache.get("id2"))
        assertNull(chunksCache.get("id3"))

        assertThat(chunksCache.get("newId1"), equalTo(newChunks[0]))
        assertThat(chunksCache.get("newId2"), equalTo(newChunks[1]))
        assertThat(chunksCache.get("newId3"), equalTo(newChunks[2]))
    }

}
