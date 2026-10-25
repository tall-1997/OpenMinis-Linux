package com.openminis.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChatDaoAppendConcurrencyTest {
    @Test fun twoRoomInstancesAllocateDistinctSortOrders() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "chat-append-concurrency-${UUID.randomUUID()}.db")
        val db1 = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath).build()
        val db2 = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath).build()
        try {
            val dao1 = db1.chatDao()
            val dao2 = db2.chatDao()
            dao1.insertSession(
                ChatSessionEntity(
                    id = "session",
                    modelId = "test",
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )

            val writes = (0 until 64).map { index ->
                async(Dispatchers.IO) {
                    val dao = if (index % 2 == 0) dao1 else dao2
                    dao.appendMessage(
                        MessageEntity(
                            id = "message-$index",
                            sessionId = "session",
                            role = "user",
                            partsJson = "[]",
                            createdAt = index.toLong(),
                            sortOrder = 0,
                        ),
                    )
                }
            }.awaitAll()

            assertEquals(64, writes.map { it.sortOrder }.toSet().size)
            assertEquals((0 until 64).toList(), dao1.sortOrders("session").sorted())
        } finally {
            db1.close()
            db2.close()
            file.delete()
        }
    }
}
