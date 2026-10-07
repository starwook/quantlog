package com.quantlog.position

import java.util.Collections

/** 오래된 항목부터 버리는 크기 제한 맵(스레드 안전). 주문번호처럼 하루 단위로 쌓이는 키를 무한히 들고 있지 않으려고 쓴다. */
fun <V> boundedMap(maxSize: Int = 2000): MutableMap<String, V> =
    Collections.synchronizedMap(
        object : LinkedHashMap<String, V>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>) = size > maxSize
        },
    )
