package com.quantlog.trading

import java.util.concurrent.ConcurrentHashMap

/** 같은 키(종목)의 작업이 겹쳐 돌지 않게 한다. 이미 돌고 있으면 기다리지 않고 건너뛴다. */
class KeyedLock {
    private val running = ConcurrentHashMap.newKeySet<String>()

    /** [key] 가 비어 있으면 [action] 을 실행하고 true, 이미 실행 중이면 건너뛰고 false. */
    fun runIfFree(
        key: String,
        action: () -> Unit,
    ): Boolean {
        if (!running.add(key)) return false
        try {
            action()
        } finally {
            running.remove(key)
        }
        return true
    }
}
