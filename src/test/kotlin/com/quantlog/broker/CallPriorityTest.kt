package com.quantlog.broker

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallPriorityTest {
    @Test
    fun `기본은 일반 등급이고 urgent 블록 안에서만 즉발이며 중첩 뒤에도 복원된다`() {
        assertFalse(CallPriority.isUrgent())
        CallPriority.urgent {
            assertTrue(CallPriority.isUrgent())
            CallPriority.urgent { assertTrue(CallPriority.isUrgent()) }
            assertTrue(CallPriority.isUrgent())
        }
        assertFalse(CallPriority.isUrgent())
    }

    @Test
    fun `블록에서 예외가 나도 등급이 복원된다`() {
        runCatching { CallPriority.urgent { error("boom") } }
        assertFalse(CallPriority.isUrgent())
    }
}
