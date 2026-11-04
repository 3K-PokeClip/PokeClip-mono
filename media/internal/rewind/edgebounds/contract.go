// Package edgebounds 는 열린 회차의 /dvr 하한(floor)을 원장(PG)에서 CloudFront KeyValueStore(KVS)로 올리고, 회차가
// 닫히면 KVS 키를 지워 푼다(POK-525 설계 2-3 · 3-3). 엣지 함수 dvr-floor.js 가 그 키로 하한보다 앞선 /dvr 조각을 막는다.
//
// 원장이 정본이고 KVS 는 사본이다. 이 파일은 계약(원장 · KVS 의 인터페이스와 값 타입)이고, key.go · value.go 가 엣지
// 함수와 공유 골든으로 맞추는 KVS 키 · 값 원문이다.
//
// **표준 라이브러리만 import 한다.** 원장 운영 구현(index.EdgeBoundsLedger)이 이 패키지의 Ledger 를 구현하므로 이
// 패키지가 장부(internal/index)를 끌어오면 순환이다. KVS 운영 구현은 SDK 를 쓰므로 하위 패키지 kvsstore 에 둔다(K9) —
// 이 패키지의 의존 목록에 SDK 가 들지 않는다(TestPackageDependsOnNeitherIndexNorAWSSDK 가 단언).
package edgebounds

import (
	"context"
	"time"
)

// Floor 는 원장 한 행이다 — 열린 회차의 /dvr 하한.
type Floor struct {
	StreamID, RoundID string
	Value             int64
}

// Ledger 는 원장이다. 운영 = index.EdgeBoundsLedger(PG), 시험 = 가짜.
type Ledger interface {
	// Raise 는 회차가 열려 있고 해제되지 않은 행만 GREATEST 로 올리고(없으면 만든다) 올린 뒤의 행을 돌려준다.
	// Value 가 1 보다 작은 행은 부르기 전에 거른다(CHECK dvr_floor >= 1).
	Raise(ctx context.Context, rows []Floor) ([]Floor, error)
	// ClosedUnreleased 는 해제 안 된 행 가운데 회차가 닫혔거나 세션 행이 없는(만료 · 탈퇴) 것을 limit 개 돌려준다.
	ClosedUnreleased(ctx context.Context, limit int) ([]Floor, error)
	// MarkReleased 는 KVS 키를 지운 행에 released_at 을 적는다(이미 적힌 행은 건드리지 않음).
	MarkReleased(ctx context.Context, rows []Floor) error
	// DeleteReleased 는 olderThan 보다 먼저 해제된 행을 지우고 지운 수를 돌려준다.
	DeleteReleased(ctx context.Context, olderThan time.Duration) (int64, error)
}

// Entry 는 KVS 키 하나의 쓰기다.
type Entry struct{ Key, Value string }

// Stat 은 KVS 의 지금 판(ETag)과 크기다.
type Stat struct {
	ETag         string
	Items, Bytes int64
}

// Store 는 KVS 다. 운영 = kvsstore 의 KVS 구현(cloudfrontkeyvaluestore), 시험 = 판 의미를 흉내 낸 가짜
// (무변경 지우기는 판 불변 — 실측 H3-1 · H3-4).
type Store interface {
	Describe(ctx context.Context) (Stat, error)
	// Update 는 ifMatch 판 위에서만 puts 를 쓰고 deletes 를 지운다(전부 아니면 전무 · 합쳐 50개 이하 · 키 중복 0).
	// 오류 종류로 경합을 가르지 않는다 — 부른 쪽이 Describe 로 판을 대조한다(계약 4).
	Update(ctx context.Context, ifMatch string, puts []Entry, deletes []string) (etag string, err error)
	// Fetch 는 탐침 · 실측의 되읽기 전용이다 — 쓰기 판정에는 쓰지 않는다.
	Fetch(ctx context.Context, key string) (value string, found bool, err error)
}

// ReleaseMarkerKey 는 해제 요청마다 새 값으로 함께 put 하는 표지 키다 — 무변경 지우기도 판을 바꾸게 한다(계약 3).
// `/` 가 없어 하한 키(FloorKey)와 겹치지 않고, 엣지 함수는 이 키를 읽지 않는다.
const ReleaseMarkerKey = "_release"
