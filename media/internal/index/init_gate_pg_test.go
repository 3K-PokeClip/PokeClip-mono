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
// **M4 가 이 경로의 전제를 뒤집었다**: M3 에서는 세 열(init_s3_key·init_sha256·init_bytes)을
// 쓰는 쪽이 없어 픽스처로만 돌았지만, 지금은 개시 시점의 세 열이 NULL 이고 첫 init CAS 가 그
// 값을 쓴다(아래 TestInitCASRefusesEmptyHashArgument 주석과 같은 사실). 그래도 "G5 통과 =
// 프로덕션 init 업로드가 돈다"로 읽으면 안 된다 — 통과한 것은 장부 축 하나이고, 워커가 CAS 를
// 부르는 경로는 여기서 재지 않는다.

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
	mark, err := st.MarkInitUploaded(ctx, session, []byte("남의 바이트열 ......................"),
		"dvr/gatestream/init/"+session+".mp4", 704, false)
	if err != nil {
		t.Fatalf("MarkInitUploaded 실패: %v", err)
	}
	if mark != InitMarkMismatch {
		t.Fatalf("mark = %v, want %v — 다른 해시로 확정됐다", mark, InitMarkMismatch)
	}
	if initGateReady(t, pool, session) {
		t.Fatal("거부된 CAS 뒤에 발행 술어가 참이 됐다")
	}

	// ⑶ 제 해시로 확정하면, 그리고 그때에만 게이트가 열린다.
	mark, err = st.MarkInitUploaded(ctx, session, sha,
		"dvr/gatestream/init/"+session+".mp4", 704, false)
	if err != nil {
		t.Fatalf("MarkInitUploaded 실패: %v", err)
	}
	if mark != InitMarkSuccess {
		t.Fatalf("mark = %v, want %v — 제 해시로는 확정돼야 한다", mark, InitMarkSuccess)
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

	mark, err := st.MarkInitUploaded(ctx, first, sameSHA,
		"dvr/"+stream+"/init/"+first+".mp4", 700, false)
	if err != nil {
		t.Fatalf("MarkInitUploaded 실패: %v", err)
	}
	if mark != InitMarkSuccess {
		t.Fatalf("mark = %v — CAS 가 세션 하나를 정확히 겨누지 않았다", mark)
	}

	if !initGateReady(t, pool, first) {
		t.Error("확정한 세션의 발행 술어가 거짓이다")
	}
	if initGateReady(t, pool, second) {
		t.Error("확정하지 않은 세션의 발행 술어가 참이다 — 게이트가 스트림 축으로 열렸다")
	}
}

// G5 — **빈 해시로는 어떤 세션도 확정되지 않는다**(fail-closed).
//
// M3 의 이 테스트는 "장부에 해시가 없는 세션은 확정 대상이 아니다" 였다. M4 가 그 전제를
// 뒤집는다 — 개시 시점 init_sha256 은 NULL 이고 첫 업로드 CAS 가 그 값을 만드는 생산자다.
// 그래서 판정 축을 옮긴다: 장부 쪽 NULL 은 정상 개시 상태이고, **인자 쪽 빈 값**이
// 거부돼야 할 것이다. NULL 을 그대로 써 넣으면 이후 모든 대조의 앵커가 사라져
// 바이트를 한 번도 만들지 않은 세션의 발행 게이트가 열린다.
func TestInitCASRefusesEmptyHashArgument(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()

	const session = "S-gate-nohash"
	putSession(t, pool, fixtureSession{id: session, stream: "gatenohash", startedAt: sessionBase})

	for name, sha := range map[string][]byte{
		"NULL 인자": nil,
		"빈 바이트열":  {},
	} {
		mark, err := st.MarkInitUploaded(ctx, session, sha, "k", 10, false)
		if err == nil {
			t.Errorf("%s 가 통과했다 — 앵커 없는 확정은 5.3ⓑ 보증을 통째로 없앤다", name)
		}
		// 오류와 함께 판정이 나가면 err 를 늦게 보는 호출자가 그 판정대로 움직인다.
		switch mark {
		case InitMarkSuccess, InitMarkAlreadySame, InitMarkMismatch, InitMarkMissing:
			t.Errorf("%s: MarkInitUploaded 판정 = %v, want 판정 없음(영값) — 오류와 판정이 함께 나갔다", name, mark)
		}
	}
	if initGateReady(t, pool, session) {
		t.Error("init 바이트를 만든 적 없는 세션의 발행 술어가 참이다")
	}
}
