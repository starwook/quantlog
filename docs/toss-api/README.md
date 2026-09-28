# 토스증권 Open API 원문 문서

2026-09-25에 공식 문서(`developers.tossinvest.com/llms.txt`가 가리키는 원본)를 그대로 저장한 것. **수정하지 않는다.** 갱신은 아래 URL에서 다시 받아 덮어쓴다.

| 파일 | 원본 | 용도 |
|---|---|---|
| `llms.txt` | https://developers.tossinvest.com/llms.txt | 문서 인덱스 |
| `overview.md` | https://openapi.tossinvest.com/openapi-docs/overview.md | 개요, 인증, rate limit, 에러, WebSocket 가이드 (먼저 읽기) |
| `faq.md` | https://openapi.tossinvest.com/openapi-docs/faq.md | FAQ (조건주문, IP, 멱등성 등) |
| `api-reference.md` | https://openapi.tossinvest.com/openapi-docs/latest/api-reference/README.md | REST API 레퍼런스 (마크다운) |
| `openapi.json` | https://openapi.tossinvest.com/openapi-docs/latest/openapi.json | REST 명세 원본 (source of truth, 약 400KB) |
| `asyncapi.json` | https://openapi.tossinvest.com/openapi-docs/latest/asyncapi.json | WebSocket 명세 원본 |

`openapi.json`은 크므로 통째로 읽지 말고 필요한 경로만 grep/jq로 확인한다.
