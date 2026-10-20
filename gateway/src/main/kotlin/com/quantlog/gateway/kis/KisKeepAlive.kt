package com.quantlog.gateway.kis

/**
 * 웹소켓이 살아 있는지 ping/pong 으로 확인한다. 한투 공식 예제(파이썬 `websockets`)가 기본으로 하는 것과 같다:
 * 주기마다 ping 을 보내고, 다음 주기까지 pong 도 받은 메시지도 없으면 연결이 죽은 것으로 본다.
 * 한투가 끊는다는 신호 없이 연결을 버리면(2026-10-19 16:33, 모의서버 장애 때 26시간 수신 0바이트) 이것 말고는 알 방법이 없다.
 */
class KisKeepAlive {
    @Volatile
    private var waitingPong = false

    /** 주기마다 부른다. true 면 직전 ping 뒤로 받은 게 없다 — 연결을 버리고 다시 붙어야 한다. false 면 ping 을 보낸다. */
    fun tick(): Boolean {
        if (waitingPong) return true
        waitingPong = true
        return false
    }

    /** pong 이든 시세·체결통보 메시지든 받으면 부른다. */
    fun onAlive() {
        waitingPong = false
    }

    /** 새 연결이 붙으면 이전 연결의 대기 상태를 버린다. */
    fun reset() {
        waitingPong = false
    }
}
