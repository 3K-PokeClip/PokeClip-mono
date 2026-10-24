package main

// 발행 루프 통합 시험의 가짜 부품(체크리스트 474 B-4 · 판단 J88) — 조건부 의미론 가짜 저장소(itStore)와 발행자 풀의 문장
// 추적기(itTracer)다. 하네스는 publish_it_fixture_pg_test.go 다.

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"errors"
	"fmt"
	"maps"
	"slices"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/jackc/pgx/v5"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// ---------------------------------------------------------------------------
// 조건부 의미론 가짜 저장소
// ---------------------------------------------------------------------------

// itStore 는 publish.Store 의 인메모리 가짜다 — S3 조건부 쓰기의 의미론(발행 패키지 fakeStore 와 같은 규칙):
//
//   - 조건은 적용하는 순간 저장된 판으로 따진다. IfAbsent 인데 객체가 있거나 IfMatch 인데 ETag 가 다르면
//     ErrPreconditionFailed(412), IfMatch 인데 객체가 없으면 ErrNotFound(404)다. 실패한 쓰기는 판을 바꾸지 않는다.
//   - ETag 는 본문만의 함수다(따옴표로 감싼 MD5 16진). 메타 키는 소문자로 저장한다.
//   - Precondition 의 칸은 비공개라 같음 비교로 가른다(IfAbsent() · IfMatch(저장된 ETag)).
//   - headFalse404 면 Head 가 객체가 있어도 없다고 답한다(탐침 C).
//   - Put 마다 차례를 적는다 — 적용된 PUT 은 본문까지 남아 그 키의 판 이력이 된다(versions).
//   - 목록 연산 · Get 에 닿으면 시험을 실패로 적는다(LIST 금지 · M4 의 GET 호출자 0).
type itStore struct {
	t testing.TB

	mu           sync.Mutex
	objects      map[string]itObject
	headFalse404 bool
	puts         []itPut
}

// itObject 는 저장된 판 하나다.
type itObject struct {
	body []byte
	etag string
	meta map[string]string
}

// itPut 은 Put 호출 하나의 기록이다 — body 는 적용된 PUT 의 본문이다(실패면 nil).
type itPut struct {
	key  string
	body []byte
	err  error
}

func newITStore(t testing.TB) *itStore { return &itStore{t: t, objects: map[string]itObject{}} }

func (s *itStore) Put(ctx context.Context, key string, cond publish.Precondition, body []byte, meta map[string]string) (string, error) {
	etag, err := s.apply(ctx, key, cond, body, meta)
	rec := itPut{key: key, err: err}
	if err == nil {
		rec.body = slices.Clone(body)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.puts = append(s.puts, rec)
	return etag, err
}

func (s *itStore) apply(ctx context.Context, key string, cond publish.Precondition, body []byte, meta map[string]string) (string, error) {
	if cond == publish.IfMatch("") {
		return "", errors.New("itStore: 빈 ETag 의 IfMatch")
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	cur, exists := s.objects[key]
	switch {
	case cond == publish.IfAbsent() && exists:
		return "", fmt.Errorf("%w: PUT key=%q 이미 있다", publish.ErrPreconditionFailed, key)
	case cond != publish.IfAbsent() && !exists:
		return "", fmt.Errorf("%w: PUT key=%q", publish.ErrNotFound, key)
	case cond != publish.IfAbsent() && cond != publish.IfMatch(cur.etag):
		return "", fmt.Errorf("%w: PUT key=%q If-Match ≠ 저장된 %s", publish.ErrPreconditionFailed, key, cur.etag)
	}
	sum := md5.Sum(body)
	obj := itObject{body: slices.Clone(body), etag: `"` + hex.EncodeToString(sum[:]) + `"`, meta: map[string]string{}}
	for k, v := range meta {
		obj.meta[strings.ToLower(k)] = v
	}
	s.objects[key] = obj
	return obj.etag, nil
}

func (s *itStore) Head(ctx context.Context, key string) (publish.Stat, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := ctx.Err(); err != nil {
		return publish.Stat{}, err
	}
	obj, ok := s.objects[key]
	if !ok || s.headFalse404 {
		return publish.Stat{}, nil
	}
	return publish.Stat{Exists: true, ETag: obj.etag, Meta: maps.Clone(obj.meta)}, nil
}

func (s *itStore) Get(context.Context, string) ([]byte, error) {
	s.t.Errorf("itStore: Get 호출 — M4 발행 경로에 GET 호출자는 없다")
	return nil, errors.New("itStore: Get 은 부르지 않는다")
}

// ListObjectsV2 · ListObjects 는 S3 LIST 금지(프로필 4절)의 동적 그물이다 — SDK 목록 인터페이스로의 형 단언이 닿으면 실패로
// 적는다(발행 워커 고루틴에서 불릴 수 있어 Errorf).
func (s *itStore) ListObjectsV2(context.Context, *s3.ListObjectsV2Input, ...func(*s3.Options)) (*s3.ListObjectsV2Output, error) {
	s.t.Errorf("S3 LIST 금지 위반: ListObjectsV2")
	return nil, errors.New("itStore: 목록 연산 금지")
}

func (s *itStore) ListObjects(context.Context, *s3.ListObjectsInput, ...func(*s3.Options)) (*s3.ListObjectsOutput, error) {
	s.t.Errorf("S3 LIST 금지 위반: ListObjects")
	return nil, errors.New("itStore: 목록 연산 금지")
}

// body 는 key 의 지금 본문이다(없으면 nil).
func (s *itStore) body(key string) []byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	return slices.Clone(s.objects[key].body)
}

// putLog 는 Put 기록의 사본이다.
func (s *itStore) putLog() []itPut {
	s.mu.Lock()
	defer s.mu.Unlock()
	return slices.Clone(s.puts)
}

// versions 는 key 에 적용된 판의 본문이다(올린 차례).
func (s *itStore) versions(key string) [][]byte {
	var out [][]byte
	for _, p := range s.putLog() {
		if p.key == key && p.err == nil {
			out = append(out, p.body)
		}
	}
	return out
}

func (s *itStore) set(f func(*itStore)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	f(s)
}

// ---------------------------------------------------------------------------
// 발행자 풀의 문장 추적기
// ---------------------------------------------------------------------------

// itStmt 는 발행자 풀이 보낸 문장 하나다 — 종류 · 시작 · 끝 · 오류.
type itStmt struct {
	class      string
	start, end time.Time
	err        error
}

type itStmtKey struct{}

// itTracer 는 발행자 풀의 pgx.QueryTracer 다 — 문장을 종류로 갈라 시작 · 끝 시각과 오류를 적는다. onStart 가 있으면 문장을
// 보내기 직전에 불러 그 ctx 로 보낸다(지연 · 결과 잃음 주입).
type itTracer struct {
	mu      sync.Mutex
	stmts   []itStmt
	onStart func(ctx context.Context, conn *pgx.Conn, class string) context.Context
}

// classifySQL 은 발행자 문장의 종류다 — 세대 규약 문장의 고유한 조각으로 가른다.
func classifySQL(sql string) string {
	switch {
	case strings.Contains(sql, "RETURNING manifest_gen, manifest_etag"):
		return "p0"
	case strings.Contains(sql, "WITH p4 AS"):
		return "p4"
	case strings.Contains(sql, "SET fence_expires_at = pg_catalog.now() + $3::interval"):
		return "renew"
	case strings.Contains(sql, "SET writer_fence = $2"):
		return "acquire"
	case strings.Contains(sql, "SET writer_fence = NULL"):
		return "release"
	case strings.Contains(sql, "GREATEST(manifest_gen"):
		return "r3"
	case strings.Contains(sql, "SET manifest_etag = NULL"):
		return "r4"
	case strings.Contains(sql, "SET inherits_session = NULL"):
		return "p2"
	case strings.Contains(sql, "INSERT INTO stream_published_gaps"):
		return "gap"
	case sql == "commit" || sql == "rollback":
		return sql
	case strings.HasPrefix(sql, "begin") && strings.Contains(sql, "read only"):
		return "begin_ro" // 적재 트랜잭션(RR 읽기 전용 — index.LoadRewindLedger)
	case strings.HasPrefix(sql, "begin"):
		return "begin"
	}
	return "other"
}

func (tr *itTracer) TraceQueryStart(ctx context.Context, conn *pgx.Conn, d pgx.TraceQueryStartData) context.Context {
	s := &itStmt{class: classifySQL(d.SQL), start: time.Now()}
	ctx = context.WithValue(ctx, itStmtKey{}, s)
	tr.mu.Lock()
	hook := tr.onStart
	tr.mu.Unlock()
	if hook != nil {
		ctx = hook(ctx, conn, s.class)
	}
	return ctx
}

func (tr *itTracer) TraceQueryEnd(ctx context.Context, _ *pgx.Conn, d pgx.TraceQueryEndData) {
	s, ok := ctx.Value(itStmtKey{}).(*itStmt)
	if !ok {
		return
	}
	s.end, s.err = time.Now(), d.Err
	tr.mu.Lock()
	defer tr.mu.Unlock()
	tr.stmts = append(tr.stmts, *s)
}

func (tr *itTracer) setOnStart(f func(ctx context.Context, conn *pgx.Conn, class string) context.Context) {
	tr.mu.Lock()
	defer tr.mu.Unlock()
	tr.onStart = f
}

// log 는 시작 순으로 정렬한 문장 기록의 사본이다.
func (tr *itTracer) log() []itStmt {
	tr.mu.Lock()
	out := slices.Clone(tr.stmts)
	tr.mu.Unlock()
	sort.Slice(out, func(i, j int) bool { return out[i].start.Before(out[j].start) })
	return out
}

// count 는 종류 class 의 문장 수다(오류 포함).
func (tr *itTracer) count(class string) int {
	n := 0
	for _, s := range tr.log() {
		if s.class == class {
			n++
		}
	}
	return n
}
