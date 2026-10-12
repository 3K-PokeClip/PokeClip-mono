// Package ingestauth 는 MediaMTX 의 송출 인증에 답하는 응답기(ingest-auth)의 상태를 다룬다 — 운영자가
// 적는 거부 목록(DenyList)과, 응답기가 허용한 송출 연결의 기록(ConnLog · LoadAllowed)이다.
//
// 이 패키지의 로그 · 에러 · 기록 파일에는 비밀이 실리지 않는다(설계 6절 누설 규칙). 토큰과 연결 id 는
// 공개 식별자라 기록한다. 거부 목록의 줄 내용은 어디에도 싣지 않는다 — 운영자가 토큰 자리에 비밀을
// 잘못 붙여 넣을 수 있기 때문이다.
package ingestauth
