package com.quantlog.gateway.errorlog

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant

/** 로그 한 건을 DB 에 쌓기 좋게 정리한 값. */
data class LogRecord(
    val at: Instant,
    val level: String,
    val logger: String,
    val message: String,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null,
    val stackTrace: String? = null,
)

/** 분류(category)와 "같은 종류" 판정(fingerprint). 로그 메시지 앞의 `[태그]` 가 이 프로젝트 로그의 분류 관례다. */
object ErrorLogClassifier {
    private val TAG = Regex("^\\s*\\[([^\\]]{1,40})]")
    private val NUMBER = Regex("\\d+([.,]\\d+)*")

    fun category(record: LogRecord): String = TAG.find(record.message)?.groupValues?.get(1)?.trim() ?: record.logger.substringAfterLast('.')

    /** 숫자(대기 시간·수량·가격)를 지워서 "대기 2880ms" 와 "대기 3216ms" 가 같은 오류로 묶이게 한다. 종목 코드 같은 글자는 그대로라 종목별로는 갈린다. */
    fun fingerprint(record: LogRecord): String {
        val normalized = NUMBER.replace(record.message, "#")
        val key = "${record.level}|${record.logger}|$normalized|${record.exceptionClass.orEmpty()}"
        return MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

@Service
class ErrorLogRecorder(private val repository: ErrorLogRepository) {
    @Transactional
    fun record(record: LogRecord) {
        val fingerprint = ErrorLogClassifier.fingerprint(record)
        val existing = repository.findByFingerprint(fingerprint)
        if (existing == null) {
            repository.save(
                ErrorLog(
                    fingerprint = fingerprint,
                    level = record.level,
                    category = ErrorLogClassifier.category(record).take(100),
                    logger = record.logger.take(200),
                    message = record.message.take(MAX_MESSAGE),
                    exceptionClass = record.exceptionClass?.take(200),
                    exceptionMessage = record.exceptionMessage?.take(500),
                    stackTrace = record.stackTrace?.take(MAX_STACK),
                    firstSeen = record.at,
                    lastSeen = record.at,
                ),
            )
        } else {
            existing.count += 1
            existing.lastSeen = record.at
            existing.message = record.message.take(MAX_MESSAGE)
            record.stackTrace?.let { existing.stackTrace = it.take(MAX_STACK) }
        }
    }

    /** 마지막 발생이 [before] 보다 오래된 행을 지운다. 지운 행 수를 돌려준다. */
    @Transactional
    fun purge(before: Instant): Long = repository.deleteByLastSeenBefore(before)

    private companion object {
        const val MAX_MESSAGE = 1000
        const val MAX_STACK = 6000
    }
}
