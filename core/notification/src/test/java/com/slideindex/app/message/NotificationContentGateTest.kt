package com.slideindex.app.message

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationContentGateTest {

    private fun data(
        key: String = "k1",
        title: String = "群 A",
        content: String = "你好",
        postTime: Long = 1000L,
        badgeCount: Int = 1,
        messageText: String? = null,
    ): NotificationData = NotificationData(
        packageName = "org.telegram.messenger",
        key = key,
        title = title,
        content = content,
        largeIcon = null,
        appIcon = null,
        contentIntent = null,
        postTime = postTime,
        messages = if (messageText == null) {
            emptyList()
        } else {
            listOf(
                NotificationMessage(
                    text = messageText,
                    sender = "张三",
                    timestamp = postTime,
                ),
            )
        },
        badgeCount = badgeCount,
    )

    @Before
    fun setUp() {
        NotificationContentGate.resetForTesting()
    }

    @Test
    fun `same content with newer post time is still duplicate`() {
        assertTrue(NotificationContentGate.accept(data(postTime = 1000L)))
        assertFalse(NotificationContentGate.accept(data(postTime = 5000L)))
    }

    @Test
    fun `changed content passes`() {
        assertTrue(NotificationContentGate.accept(data(content = "你好")))
        assertTrue(NotificationContentGate.accept(data(content = "在吗")))
    }

    @Test
    fun `changed unread count passes`() {
        assertTrue(NotificationContentGate.accept(data(badgeCount = 1)))
        assertTrue(NotificationContentGate.accept(data(badgeCount = 2)))
    }

    @Test
    fun `different conversations do not affect each other`() {
        assertTrue(NotificationContentGate.accept(data(key = "k1", title = "群 A")))
        assertTrue(NotificationContentGate.accept(data(key = "k2", title = "群 B")))
        assertFalse(NotificationContentGate.accept(data(key = "k1", title = "群 A")))
    }

    @Test
    fun `new message in conversation passes`() {
        assertTrue(NotificationContentGate.accept(data(content = "你好", messageText = "你好")))
        assertTrue(NotificationContentGate.accept(data(content = "在吗", messageText = "在吗")))
    }
}
