package index

// G5 — init 게이팅 축의 **DB 판정**(POK-195 M3 · 설계 5.3ⓓ·5.5.5 셋째 문장 · 계획 단계 6).
//
// 재는 것: *"`init_uploaded_at IS NULL` 이면 `ready:false` + 매니페스트 PUT 금지"* 라는
// 발행 게이팅의 **술어가 장부 위에서 실제로 그렇게 갈리는가**.
//
// **왜 SQL 을 픽스처가 직접 실행하는가**: 이 술어의 소비자는 발행 층(M4)뿐이라 M3 에
// 술어 함수를 만들면 호출자가 0 이다(제0원칙 5 — 계획 9절 "G5 게이팅 술어의 소유자").
// 그래서 함수의 집은 M4 로 두고, M3 는 t3 전례대로 **픽스처가 술어를 SQL 로 직접 판정**한다.
//
// **upload_store_test.go 의 T8·T10 과 무엇이 다른가**: 저기는 CAS 의 **계약**(marked 반환·
// 세 조건·세션 독립)을 재고, 여기는 그 CAS 가 **발행 가능성을 여닫는가**를 잰다. 같은
// 문장을 두 각도에서 보는 것이라 한쪽이 통과해도 다른 쪽이 깨질 수 있다 — 예컨대 CAS 가
// init_uploaded_at 이 아닌 다른 열을 갱신하면 T8 의 marked 는 참인데 게이트는 영영 닫힌다.
//
// **M3 에서 이 경로는 픽스처로만 실행된다**: 세 열(init_s3_key·init_sha256·init_bytes)의
// 유일한 생산자가 Producer.Init 이고 그것이 M4 다(계획 D5). 그래서 "G5 통과 = 프로덕션
// init 업로드가 돈다"로 읽으면 안 된다 — 통과한 것은 장부 축 하나다.

import (
	"context"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
)

// initGateReadySQL 은 발행 층(M4)이 세션 하나에 대해 물을 술어 그대로다.
// 표현을 함수로 감싸지 않고 상수로 두는 이유는 위 머리 주석의 "집은 M4" 와 같다.
const initGateReadySQL = `SELECT init_uploaded_at IS NOT NULL FROM stream_sessions WHERE session_id = $1`

// initGateReady 는 그 세션이 지금 발행 가능한가다(= ready 플래그의 init 항).
func initGateReady(t *testing.T, pool *pgxpool.Pool, sessionID string) bool {
	t.Helper()
	var ready bool
	if err := pool.QueryRow(context.Background(), initGateReadySQL, sessionID).Scan(&ready); err != nil {
		t.Fatalf("발행 술어 판정 실패 session_id=%q: %v", sessionID, err)
	}
	return ready
}

// G5 ⑴⑵⑶ — init 바이트가 장부에 적혀 있어도, **올라간 것이 확인되기 전에는 게이트가 닫혀 있다**.
//
// 잡는 결함: 발행 술어를 "init_s3_key 가 있는가" 같은 예약 시점 값으로 잡으면 세 열이
// 채워지는 순간 게이트가 열려 **S3 에 없는 MAP 을 가리키는 매니페스트**가 나간다.
func TestInitGateStaysClosedUntilInitUploadIsConfirmed(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()

	const session = "S-gate-closed"
	sha := []byte("gate0123456789abcdef0123456789ab")
	initSession(t, pool, session, "gatestream", "dvr/gatestream/init/"+session+".mp4", sha, 704)

	// ⑴ 세 열이 다 찼는데도 init_uploaded_at 이 NULL 이면 발행 불가다.
	if initGateReady(t, pool, session) {
		t.Fatal("init_uploaded_at 이 NULL 인데 발행 술어가 참이다 — 게이트가 예약 시점에 열렸다")
	}

	// ⑵ 거부된 CAS 는 게이트를 열지 않는다(실패가 조용히 통과로 접히지 않는다).
	marked, err := st.MarkInitUploaded(ctx, session, []byte("남의 바이트열 ......................"))
	if err != nil {
		t.Fatalf("MarkInitUploaded 실패: %v", err)
	}
	if marked {
		t.Fatal("marked = true — 다른 해시로 확정됐다")
	}
	if initGateReady(t, pool, session) {
		t.Fatal("거부된 CAS 뒤에 발행 술어가 참이 됐다")
	}

	// ⑶ 제 해시로 확정하면, 그리고 그때에만 게이트가 열린다.
	marked, err = st.MarkInitUploaded(ctx, session, sha)
	if err != nil {
		t.Fatalf("MarkInitUploaded 실패: %v", err)
	}
	if !marked {
		t.Fatal("marked = false — 제 해시로는 확정돼야 한다")
	}
	if !initGateReady(t, pool, session) {
		t.Fatal("CAS 성공 뒤에도 발행 술어가 거짓이다 — CAS 가 게이트 열을 갱신하지 않았다")
	}
}

// G5 — 게이트는 **세션 축**이다. 한 세션의 확정이 같은 스트림의 다른 세션을 열지 않는다.
//
// 잡는 결함: 술어나 CAS 가 stream_id 축이면(f(stream_id) 파생 — 설계 5.3ⓐ 금지 사항)
// 앞 세션의 MAP 확정이 뒤 세션의 발행을 열어, 뒤 세션의 되감기가 남의 MAP 으로 재생된다.
//
// **두 세션의 init 바이트를 같게 둔다**: 같은 인코더 설정으로 재개시하면 MAP 이 실제로
// 동일하다(5.3ⓑ 의 바이트 동등성이 성립하는 흔한 국면). 해시가 서로 다르면 sha 가드가
// 스트림 축 결함을 대신 가려 이 테스트가 공허해진다 — T10 과 픽스처가 갈리는 지점이다.
func TestInitGateIsPerSessionNotPerStream(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()

	const stream = "gatetwosess"
	const first, second = "S-gate-1", "S-gate-2"
	sameSHA := []byte("samegate6789abcdef0123456789abcd")
	initSession(t, pool, first, stream, "dvr/"+stream+"/init/"+first+".mp4", sameSHA, 700)
	initSession(t, pool, second, stream, "dvr/"+stream+"/init/"+second+".mp4", sameSHA, 700)

	marked, err := st.MarkInitUploaded(ctx, first, sameSHA)
	if err != nil {
		t.Fatalf("MarkInitUploaded 실패: %v", err)
	}
	if !marked {
		t.Fatal("첫 세션 확정 실패 — CAS 가 세션 하나를 정확히 겨누지 않았다")
	}

	if !initGateReady(t, pool, first) {
		t.Error("확정한 세션의 발행 술어가 거짓이다")
	}
	if initGateReady(t, pool, second) {
		t.Error("확정하지 않은 세션의 발행 술어가 참이다 — 게이트가 스트림 축으로 열렸다")
	}
}

// G5 — 장부에 해시가 없는 세션은 **어떤 값으로도** 확정되지 않는다(fail-closed).
//
// 잡는 결함: CAS 의 해시 비교를 `IS NOT DISTINCT FROM` 류로 쓰면 init_sha256 이 NULL 인
// 세션이 NULL 인자로 확정된다 — 바이트를 한 번도 만들지 않은 세션의 게이트가 열린다.
// M3 에서 이것은 가상의 국면이 아니다: 세 열의 생산자가 M4 라 **모든 세션이 이 상태**다.
func TestInitGateStaysClosedWhenLedgerHasNoInitHash(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()

	const session = "S-gate-nohash"
	putSession(t, pool, fixtureSession{id: session, stream: "gatenohash", startedAt: sessionBase})

	for name, sha := range map[string][]byte{
		"NULL 인자": nil,
		"빈 바이트열":  {},
		"임의의 해시":  []byte("무엇이든 ..............................."),
	} {
		marked, err := st.MarkInitUploaded(ctx, session, sha)
		if err != nil {
			t.Fatalf("MarkInitUploaded(%s) 실패: %v", name, err)
		}
		if marked {
			t.Errorf("%s 로 확정됐다 — 장부에 해시가 없는 세션은 확정 대상이 아니다", name)
		}
	}
	if initGateReady(t, pool, session) {
		t.Error("init 바이트를 만든 적 없는 세션의 발행 술어가 참이다")
	}
}
