package broadcast

// outboxWritable 판정 실 PG 시험(sgd 설계 2-1 · J7′ 관문). 켜진 게이트는 적재 직전에 이 판정을 묻고 거짓이면 문장 없이
// 건너뛴다 — 판정이 실제 문장 결과와 하나라도 어긋나면 켜진 사이드카의 닫힘이 42501 로 롤백되거나(거짓 양성 P8)
// ended 를 놓친다(거짓 음성 P9). 그래서 권한을 얻는 길 다섯마다 판정 값과 두 INSERT 의 실제 결과를 맞춘다.

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// writableRole 은 J7′ 사례 하나의 권한 꾸밈이다. grants 는 관리 풀이 돌릴 GRANT 문장이다(member · group 은 역할 이름).
type writableRole struct {
	name   string
	group  bool // 참이면 그룹 역할을 만들어 grants 의 group 자리에 넣는다
	grants func(member, group string) []string
	want   bool
}

// writableRoles 는 outbox 권한을 얻는 길 다섯이다. 다섯 모두 insertEndedSQL 이 읽는 stream_sessions · stream_segments 의
// SELECT 를 따로 받는다(시험이 먼저 깐다) — 그것이 없으면 판정과 무관한 42501 로 거짓 불일치가 난다.
var writableRoles = []writableRole{
	{name: "직접_네_권한", grants: func(m, _ string) []string {
		return []string{"GRANT SELECT, INSERT, UPDATE, DELETE ON broadcast_lifecycle_outbox TO " + m}
	}, want: true},
	{name: "열_단위_UPDATE", grants: func(m, _ string) []string {
		return []string{
			"GRANT SELECT, INSERT ON broadcast_lifecycle_outbox TO " + m,
			"GRANT UPDATE (state) ON broadcast_lifecycle_outbox TO " + m,
		}
	}, want: true},
	{name: "그룹_INHERIT_TRUE", group: true, grants: func(m, g string) []string {
		return []string{
			"GRANT SELECT, INSERT, UPDATE, DELETE ON broadcast_lifecycle_outbox TO " + g,
			"GRANT " + g + " TO " + m + " WITH INHERIT TRUE",
		}
	}, want: true},
	{name: "그룹_INHERIT_FALSE", group: true, grants: func(m, g string) []string {
		return []string{
			"GRANT SELECT, INSERT, UPDATE, DELETE ON broadcast_lifecycle_outbox TO " + g,
			"GRANT " + g + " TO " + m + " WITH INHERIT FALSE",
		}
	}, want: false},
	{name: "outbox_권한_없음", grants: func(string, string) []string { return nil }, want: false},
}

// J7′ — 판정 값 = insertStartedSQL · insertEndedSQL 의 실제 결과(성공 / 42501). 성공 쪽은 ended 가 1행을 넣어
// started 행 FOR SHARE 까지 실제로 지났음을 함께 본다.
// 잡는 결함: UPDATE 를 has_table_privilege 로 물으면 열 단위 부여에서 판정이 거짓인데 문장은 성공한다(P9 · V4).
// 판정이 역할 상속(INHERIT)을 문장과 다르게 보면 그룹 사례에서 어긋난다(P8).
func TestOutboxWritableMatchesStatementOutcome(t *testing.T) {
	for _, tt := range writableRoles {
		t.Run(tt.name, func(t *testing.T) {
			pool := newTestPool(t)
			completeRound(t, pool)
			rolePool := newWritableRolePool(t, pool, tt)

			writable := checkOutboxWritable(t, rolePool)
			startedOK := statementSucceeds(t, rolePool, "insertStartedSQL", 1, insertStartedSQL,
				StartedEventID("S-J7"), "S-J7", EventStarted, base, ingest, "pending", nil)
			endedOK := statementSucceeds(t, rolePool, "insertEndedSQL", 1, insertEndedSQL,
				StartedEventID("S-A"), EndedEventID("S-A"), EventEnded)
			t.Logf("outboxWritable = %v · insertStartedSQL 성공 = %v · insertEndedSQL 성공 = %v", writable, startedOK, endedOK)

			if writable != startedOK || writable != endedOK {
				t.Errorf("outboxWritable = %v, want 실제 결과와 같음(insertStartedSQL 성공 %v · insertEndedSQL 성공 %v)",
					writable, startedOK, endedOK)
			}
			if writable != tt.want {
				t.Errorf("outboxWritable = %v, want %v(%s)", writable, tt.want, tt.name)
			}
		})
	}
}

// newWritableRolePool 은 사례 tt 의 권한만 가진 로그인 역할의 풀이다. 정리는 t.Cleanup 이 나중 등록부터 돌리므로
// 「권한 · 멤버십 회수 → 멤버 DROP → 그룹 회수 · DROP」 순서가 되게 등록한다.
func newWritableRolePool(t *testing.T, admin *pgxpool.Pool, tt writableRole) *pgxpool.Pool {
	t.Helper()
	ctx := context.Background()
	group := ""
	if tt.group {
		group = "pc_role_outbox_group_" + randomSuffix(t)
		execAdmin(t, admin, "CREATE ROLE "+group+" NOLOGIN")
		t.Cleanup(func() {
			cleanupAdmin(t, admin,
				"REVOKE ALL ON broadcast_lifecycle_outbox FROM "+group,
				"DROP ROLE "+group)
		})
	}
	member := pgtest.NewLoginRole(t, admin)
	t.Cleanup(func() { // GRANT 보다 먼저 등록 — 중간 GRANT 가 실패해도 이미 준 권한을 거둬 멤버 DROP 이 막히지 않는다
		stmts := []string{"REVOKE ALL ON broadcast_lifecycle_outbox, stream_sessions, stream_segments FROM " + member.Name}
		if group != "" {
			stmts = append(stmts, "REVOKE "+group+" FROM "+member.Name)
		}
		cleanupAdmin(t, admin, stmts...)
	})
	execAdmin(t, admin, "GRANT SELECT ON stream_sessions, stream_segments TO "+member.Name)
	for _, s := range tt.grants(member.Name, group) {
		execAdmin(t, admin, s)
	}

	cfg := admin.Config()
	cfg.ConnConfig.User, cfg.ConnConfig.Password = member.Name, member.Password
	p, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatalf("시험 역할 풀 생성: %v", err)
	}
	t.Cleanup(p.Close) // 회수보다 늦게 등록 — 먼저 돌아 연결을 닫는다
	return p
}

// checkOutboxWritable 은 역할 풀의 트랜잭션 하나에서 판정을 묻고 되돌린다.
func checkOutboxWritable(t *testing.T, p *pgxpool.Pool) bool {
	t.Helper()
	ctx := context.Background()
	tx, err := p.Begin(ctx)
	if err != nil {
		t.Fatalf("Begin: %v", err)
	}
	defer tx.Rollback(ctx)
	ok, err := outboxWritable(ctx, tx)
	if err != nil {
		t.Fatalf("outboxWritable: %v", err)
	}
	return ok
}

// statementSucceeds 는 문장 sql 을 역할 풀의 트랜잭션 하나에서 돌리고 되돌린다. 성공이면 참, 42501 이면 거짓이다.
// 성공인데 영향 행 수가 rows 가 아니면(문장이 실제 일을 하지 않았다) 실패, 그 밖의 오류도 실패다.
func statementSucceeds(t *testing.T, p *pgxpool.Pool, name string, rows int64, sql string, args ...any) bool {
	t.Helper()
	ctx := context.Background()
	tx, err := p.Begin(ctx)
	if err != nil {
		t.Fatalf("Begin: %v", err)
	}
	defer tx.Rollback(ctx)
	tag, err := tx.Exec(ctx, sql, args...)
	switch {
	case pgtest.IsInsufficientPrivilege(err):
		return false
	case err != nil:
		t.Fatalf("%s = %v, want 성공 또는 42501", name, err)
	case tag.RowsAffected() != rows:
		t.Fatalf("%s 영향 행 = %d, want %d", name, tag.RowsAffected(), rows)
	}
	return true
}

func execAdmin(t *testing.T, admin *pgxpool.Pool, sql string) {
	t.Helper()
	if _, err := admin.Exec(context.Background(), sql); err != nil {
		t.Fatalf("관리 문장 %q: %v", sql, err)
	}
}

// cleanupAdmin 은 정리 문장을 차례로 돌린다 — 하나가 실패해도 나머지를 돌리고 실패를 알린다.
func cleanupAdmin(t *testing.T, admin *pgxpool.Pool, stmts ...string) {
	t.Helper()
	for _, s := range stmts {
		if _, err := admin.Exec(context.Background(), s); err != nil {
			t.Errorf("정리 문장 %q: %v", s, err)
		}
	}
}

// randomSuffix 는 역할 이름 접미로 쓰는 난수 16진 8자다(역할은 클러스터 전역 — 겹친 실행이 서로를 지우지 않게).
func randomSuffix(t *testing.T) string {
	t.Helper()
	buf := make([]byte, 4)
	if _, err := rand.Read(buf); err != nil {
		t.Fatalf("난수: %v", err)
	}
	return hex.EncodeToString(buf)
}
