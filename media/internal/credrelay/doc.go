// Package credrelay 는 Media 상자의 IMDS 대리 창구(cred-relay)의 핵심이다(POK-574). 켬 상자에서는 컨테이너가 IMDS
// 토큰을 받지 못하고(홉 1), 역할 자격은 호스트 망의 중계가 토큰을 가진 소비자(사이드카 · janitor)에게만 준다.
//
//   - LoadTokens 는 호출자마다 토큰 파일 하나를 os.Root 안에서 읽고(secretfile.ReadIn — 루트 밖 · 링크 이름 거부),
//     Tokens.Match 는 요청의 Authorization 을 상수 시간으로 견준다.
//   - NewIMDSRoleSource 는 IMDS 의 인스턴스 역할 자격을 직접 읽어 실제 만료 그대로 돌려준다.
//   - NewHandler 는 HTTP 계약(인증 → 요청이 몰고 가는 자격 캐시 → 응답마다 min(실제 만료, 응답 시각 + 60분))이고,
//     NewServer 는 서버 상한을 건다.
//
// 이 패키지의 로그 · 에러 · 오류 응답 본문에는 비밀이 실리지 않는다 — 역할 자격 세 필드 · IMDS 토큰 · 호출자
// 토큰 · 받은 Authorization 원문. 자격 값은 200 응답 본문 한 자리에만 실린다. 원천 실패는 종류(err_type)만
// 로그에 싣는다.
package credrelay
