package com.quantlog.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotifierTest {
    @Test
    fun `Discord 는 content, Slack 은 text 키로 보낸다`() {
        assertEquals(mapOf("content" to "hi"), Notifier(NotifyProperties("https://discord.com/api/webhooks/x")).payload("hi"))
        assertEquals(mapOf("text" to "hi"), Notifier(NotifyProperties("https://hooks.slack.com/services/x")).payload("hi"))
    }

    @Test
    fun `URL 이 비어 있으면 꺼져 있다`() {
        assertFalse(Notifier(NotifyProperties()).enabled)
        assertTrue(Notifier(NotifyProperties("https://x")).enabled)
    }

    @Test
    fun `너무 긴 메시지는 자른다`() {
        assertEquals(1900, Notifier(NotifyProperties("https://x")).payload("a".repeat(5000)).getValue("text").length)
    }
}
