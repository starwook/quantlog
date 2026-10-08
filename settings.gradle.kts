rootProject.name = "quantlog"

// gateway: 증권사(KIS)와 닿는 모든 것(키·토큰·REST·웹소켓·체결 원장 기록·주문 실행). 시크릿은 여기에만 필요하다. 거의 안 꺼진다.
// app: 전략·화면·알림. 게이트웨이와는 코드를 공유하지 않고 계약(DB 테이블·HTTP·웹소켓 메시지 형식)만 맞춘다 — docs/서버-분리.md, docs/contracts/.
include("gateway", "app")
