// Package fsop 은 취소를 받지 않는 개별 파일시스템 호출(os.Stat, 미디어 프로브 등)을
// 짧은 워커로 감싸 시간 상한을 주는 층이다. 멈춘 파일시스템이 이벤트 루프를
// 무징후로 세우는 경로를 여기서 끊는다(POK-168 r15a · ADR-063).
//
// 계약 4항(설계 3.2 — API 반환 형상은 M1 이 확정한다, F-41):
//  1. 취소를 받지 않는 개별 FS 호출을 짧은 워커로 감싸 상한을 준다.
//     상한 = FS_OP_TIMEOUT(권고 5초).
//  2. 자원 핸들을 시간 경계 너머로 돌려주지 않는다 — 파일을 여는 작업은
//     열고·읽고·닫기를 워커 안에서 통째로 끝낸다(늦은 성공의 FD 회수 소유자 부재 문제 소멸).
//  3. 타임아웃은 site 라벨과 함께 fsLatch.Trip 과 fs_op_stalled 로 연결된다.
//     정상 실패와 타임아웃이 호출자에게 구분돼 도달해야 이 연결이 성립한다(수용 기준 f6m ⓓ).
//  4. Latch 는 loop 단일 goroutine 이 소유한다. 상한은 비율 상한이지 총량 상한이 아니다.
package fsop

import "time"

// DefaultOpTimeout 은 개별 FS 호출 상한의 기본값이다(계약 1항 권고).
// 환경변수(FS_OP_TIMEOUT) 배선과 StatT·ProbeT·Latch 의 형상은 M1 이 확정한다.
const DefaultOpTimeout = 5 * time.Second
