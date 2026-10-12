// Package ingestauth 는 MediaMTX 의 송출 인증에 답하는 응답기(ingest-auth)다. NewHandler 가 그 HTTP 처리기를
// 만든다 — MediaMTX 의 인증 요청을 판정표(설계 2-4)대로 가르고, SRT publish 는 auth 에 4C resolve 로 물어 그 연결의
// passphrase 를 200 본문으로 돌려준다. 처리기는 auth 를 부르느라 MediaMTX 경로 관리자를 붙잡는 시간을 예산으로
// 묶고(holdBudget — 요청마다 걸린 시간을 뺀다), 판정 불가로 끝난 토큰은 기억해 차례로 다시 묻는다(failedTokens —
// 기억된 토큰 전체에서 4초에 하나, 가장 오래 기다린 토큰부터). 응답기의 상태는 둘이 더 있다 — 운영자가 적는 거부
// 목록(DenyList)과, 응답기가 허용한 송출 연결의 기록(ConnLog · LoadAllowed)이다.
//
// 이 패키지의 로그 · 에러 · 기록 파일에는 비밀이 실리지 않는다(설계 6절 누설 규칙). passphrase 원문은 200 응답
// 본문 한 자리에만 실린다. 토큰과 연결 id 는 공개 식별자라 기록한다. 거부 목록의 줄 내용은 어디에도 싣지 않는다
// — 운영자가 토큰 자리에 비밀을 잘못 붙여 넣을 수 있기 때문이다. 같은 까닭으로 토큰 꼴이 아닌 경로도 싣지 않는다.
package ingestauth
