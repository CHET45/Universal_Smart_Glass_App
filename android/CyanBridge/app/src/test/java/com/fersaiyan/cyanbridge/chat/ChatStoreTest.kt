package com.fersaiyan.cyanbridge.chat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStoreTest {

    @After
    fun tearDown() {
        ChatStore.clearAll()
    }

    @Test
    fun createThread_isListed() {
        val t = ChatStore.createThread(title = "Test")
        val list = ChatStore.listThreads()
        assertEquals(1, list.size)
        assertEquals(t.id, list[0].id)
        assertEquals("Test", list[0].title)
    }

    @Test
    fun addMessage_updatesTitleFromFirstUserMessage() {
        val t = ChatStore.createThread()
        ChatStore.addMessage(t.id, ChatRole.USER, "Hello there")

        val thread = ChatStore.getThread(t.id)
        assertNotNull(thread)
        assertTrue(thread!!.title.startsWith("Hello"))
    }

    @Test
    fun listMessages_returnsInOrder() {
        val t = ChatStore.createThread(title = "X")
        ChatStore.addMessage(t.id, ChatRole.USER, "a")
        ChatStore.addMessage(t.id, ChatRole.ASSISTANT, "b")

        val msgs = ChatStore.listMessages(t.id)
        assertEquals(2, msgs.size)
        assertEquals("a", msgs[0].content)
        assertEquals("b", msgs[1].content)
    }
}
