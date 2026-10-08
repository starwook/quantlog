package com.quantlog.gateway.api

import com.quantlog.gateway.GatewayProperties
import com.quantlog.gateway.kis.KisApiException
import com.quantlog.gateway.record.OrderFailedException
import com.quantlog.gateway.record.OrderResultUnknownException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import mu.KotlinLogging
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

private val log = KotlinLogging.logger {}

/** 오류 응답 형식: `{"error": 메시지, "code": 증권사 msg_cd 또는 null}`. 앱은 상태 코드와 이 본문으로 실패를 구분한다. */
data class ApiError(val error: String, val code: String? = null)

@RestControllerAdvice
class ApiExceptionHandler {
    /** 증권사가 거절·실패를 돌려줬다. */
    @ExceptionHandler(KisApiException::class)
    fun kis(e: KisApiException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiError(e.message.orEmpty(), e.code))

    @ExceptionHandler(OrderResultUnknownException::class)
    fun unknown(e: OrderResultUnknownException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError(e.message.orEmpty(), "RESULT_UNKNOWN"))

    @ExceptionHandler(OrderFailedException::class)
    fun failed(e: OrderFailedException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiError(e.message.orEmpty(), "PREVIOUS_FAILED"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(e: IllegalArgumentException): ResponseEntity<ApiError> =
        ResponseEntity.unprocessableEntity().body(ApiError(e.message.orEmpty(), "INVALID"))

    @ExceptionHandler(UnsupportedOperationException::class)
    fun unsupported(e: UnsupportedOperationException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(ApiError(e.message.orEmpty(), "UNSUPPORTED"))

    @ExceptionHandler(Exception::class)
    fun other(e: Exception): ResponseEntity<ApiError> {
        log.warn(e) { "[게이트웨이 API] 처리 실패" }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError(e.message ?: e.javaClass.simpleName))
    }
}

/** 공유 토큰(`X-Gateway-Token`) 검사. 토큰을 설정하지 않았으면 통과시킨다(사설망·로컬 개발). */
@Component
class TokenInterceptor(private val properties: GatewayProperties) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (properties.token.isBlank() || request.getHeader(TOKEN_HEADER) == properties.token) return true
        response.status = HttpStatus.UNAUTHORIZED.value()
        return false
    }

    companion object {
        const val TOKEN_HEADER = "X-Gateway-Token"
    }
}

@Configuration
class ApiWebConfig(private val tokenInterceptor: TokenInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(tokenInterceptor).addPathPatterns("/api/**")
    }
}
