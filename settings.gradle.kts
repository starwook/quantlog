rootProject.name = "quantlog"

// common: 게이트웨이·앱이 같이 쓰는 계약(증권사 인터페이스·모델, 이벤트)과 공용 데이터(엔티티·저장소)
// gateway: KIS 와 닿는 모든 것(키·토큰·REST·웹소켓·주문 실행·원장 기록). 시크릿은 여기에만 필요하다.
// app: 전략·화면·알림. gateway 에 컴파일 의존을 갖지 않는다(런타임에만 같이 뜬다 — docs/서버-분리.md).
include("common", "gateway", "app")
