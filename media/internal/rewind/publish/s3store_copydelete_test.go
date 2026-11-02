package publish

// S3Store.Copy · Delete 의 요청 모양과 응답 분기 — s3store_test.go 와 같은 로컬 흉내 서버(newStubbedStore)에 실제 SDK
// 클라이언트가 보낸 요청을 잰다. 외부 S3 로 나가는 요청은 없다.

import (
	"context"
	"encoding/xml"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"slices"
	"strings"
	"sync"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/s3errbody"
)

const (
	copySrcKey = "dvr/demo/seg/000010.m4s"
	copyDstKey = "vod/demo/seg/000010.m4s"
)

// respondCopied 는 CopyObject 의 성공 응답(200 · CopyObjectResult 본문)으로 답한다.
func respondCopied(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/xml")
	fmt.Fprint(w, `<?xml version="1.0" encoding="UTF-8"?>`+"\n"+
		`<CopyObjectResult><ETag>"e1"</ETag><LastModified>2026-11-03T00:00:00.000Z</LastModified></CopyObjectResult>`)
}

// respond200Error 는 상태 200 에 오류 본문을 싣는다 — CopyObject 가 복사를 시작한 뒤 실패하면 S3 가 이렇게 답한다(E60).
func respond200Error(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/xml")
	fmt.Fprint(w, `<?xml version="1.0" encoding="UTF-8"?>`+"\n"+
		`<Error><Code>InternalError</Code><Message>stub</Message><RequestId>req-1</RequestId><HostId>host-1</HostId></Error>`)
}

// Copy 는 PUT 하나로 같은 버킷 안에서 서버 측 복사를 한다 — 복사 원본은 x-amz-copy-source(버킷/키), 메타는 원본 그대로
// (x-amz-metadata-directive: COPY 를 명시 — E56), 암호화 머리는 싣지 않는다(대상 버킷 기본 암호화가 정한다). 조건 · 본문은
// 없다. 잡는 결함: 메타 지시가 빠져 기본값에 기대거나 REPLACE 로 바뀌어 사본 머리가 원본과 달라짐 · 암호화 머리가 끼어
// 버킷 정책과 다른 키로 암호화됨 · 다른 버킷이나 키를 원본으로 보냄.
func TestS3StoreCopySendsServerSideCopy(t *testing.T) {
	store, stub := newStubbedStore(t, respondCopied)

	if err := store.Copy(context.Background(), copySrcKey, copyDstKey); err != nil {
		t.Fatalf("Copy 실패: %v", err)
	}

	req := stub.only(t)
	if req.method != http.MethodPut || req.path != "/"+testBucket+"/"+copyDstKey {
		t.Errorf("요청 = %s %s, want PUT /%s/%s", req.method, req.path, testBucket, copyDstKey)
	}
	if got, want := req.header.Values("X-Amz-Copy-Source"), []string{testBucket + "/" + copySrcKey}; !slices.Equal(got, want) {
		t.Errorf("x-amz-copy-source = %q, want %q", got, want)
	}
	if got := req.header.Values("X-Amz-Metadata-Directive"); !slices.Equal(got, []string{"COPY"}) {
		t.Errorf("x-amz-metadata-directive = %q, want [COPY]", got)
	}
	for name := range req.header {
		lower := strings.ToLower(name)
		switch {
		case strings.HasPrefix(lower, "x-amz-server-side-encryption"):
			t.Errorf("암호화 머리 %s = %q 가 실렸다, want 없음(대상 버킷 기본값)", name, req.header.Values(name))
		case strings.HasPrefix(lower, "x-amz-copy-source-if-"), lower == "if-match", lower == "if-none-match":
			t.Errorf("조건 머리 %s = %q 가 실렸다, want 없음", name, req.header.Values(name))
		case strings.HasPrefix(lower, "x-amz-meta-"):
			t.Errorf("메타 머리 %s 가 실렸다, want 없음(메타는 원본 그대로)", name)
		}
	}
	if len(req.body) != 0 {
		t.Errorf("요청 본문 %d바이트, want 0(서버 측 복사)", len(req.body))
	}
}

// Copy 의 응답 분기 — 원본 키 부재(404 NoSuchKey)는 ErrNotFound, 버킷 부재 · 권한 · 5xx 는 센티널이 아닌 오류다. 상태 200
// 이어도 본문이 오류면 실패다(E60 — SDK 가 오류로 바꾼다). 어느 분기든 시도는 한 번이다(재시도는 호출자 몫).
func TestS3StoreCopyMapsResponse(t *testing.T) {
	cases := []struct {
		name    string
		respond http.HandlerFunc
		// want 는 errors.Is 가 참이어야 할 센티널이다. nil 이면 어느 센티널도 아니어야 한다.
		want    error
		wantErr bool
	}{
		{name: "200_copied", respond: respondCopied},
		{name: "200_error_body", respond: respond200Error, wantErr: true},
		{name: "404_NoSuchKey", respond: respondError(http.StatusNotFound, "NoSuchKey"), want: ErrNotFound, wantErr: true},
		{name: "404_NoSuchBucket", respond: respondError(http.StatusNotFound, "NoSuchBucket"), wantErr: true},
		{name: "403_AccessDenied", respond: respondError(http.StatusForbidden, "AccessDenied"), wantErr: true},
		{name: "503_SlowDown", respond: respondError(http.StatusServiceUnavailable, "SlowDown"), wantErr: true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			store, stub := newStubbedStore(t, tc.respond)

			err := store.Copy(context.Background(), copySrcKey, copyDstKey)

			if gotErr := err != nil; gotErr != tc.wantErr {
				t.Fatalf("Copy 오류 = %v, want 오류 있음 = %t", err, tc.wantErr)
			}
			for _, s := range sentinels {
				if got, want := errors.Is(err, s), s == tc.want; got != want {
					t.Errorf("errors.Is(%v, %v) = %t, want %t", err, s, got, want)
				}
			}
			if n := stub.count(); n != 1 {
				t.Errorf("요청 %d건, want 1 — 시도는 한 번이다", n)
			}
		})
	}
}

// Copy 는 ③ 원본에서 그 사본 키(playback.VODPath)로의 복사만 보낸다 — 그 밖은 요청 없이 errBadObjectKey 다. 잡는 결함:
// 인자를 뒤바꿔 사본으로 원본(dvr/)을 덮음 · 다른 원본의 사본 자리에 복사 · 목록 문서를 조건 없이 덮어 세대 규약을 깸 ·
// 이상한 키가 다른 객체를 가리킴. 버킷 버전 관리가 없어 덮인 원본은 되돌릴 수 없다.
func TestS3StoreCopyRejectsBadKeys(t *testing.T) {
	cases := []struct {
		name     string
		src, dst string
	}{
		{name: "swapped", src: copyDstKey, dst: copySrcKey},
		{name: "other_pair", src: "dvr/demo/seg/000011.m4s", dst: copyDstKey},
		{name: "other_stream", src: copySrcKey, dst: "vod/other/seg/000010.m4s"},
		{name: "dvr_to_dvr", src: copySrcKey, dst: "dvr/demo/seg/000099.m4s"},
		{name: "same_key", src: copySrcKey, dst: copySrcKey},
		{name: "empty_src", src: "", dst: copyDstKey},
		{name: "empty_dst", src: copySrcKey, dst: ""},
		{name: "parent_ref", src: "dvr/../vod/x.m4s", dst: copyDstKey},
		{name: "space", src: copySrcKey, dst: "vod/a b.m4s"},
		{name: "query_char", src: "dvr/demo/seg/000010.m4s?versionId=1", dst: copyDstKey},
		{name: "leading_slash", src: "/" + copySrcKey, dst: copyDstKey},
		{name: "manifest_dst", src: copySrcKey, dst: "vod/demo/H1/index.m3u8"},
		{name: "manifest_src", src: "dvr/demo/sess-1/index.m3u8", dst: "vod/demo/sess-1/index.m3u8"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			store, stub := newStubbedStore(t, respondCopied)

			if err := store.Copy(context.Background(), tc.src, tc.dst); !errors.Is(err, errBadObjectKey) {
				t.Errorf("Copy(%q, %q) 오류 = %v, want errBadObjectKey", tc.src, tc.dst, err)
			}
			if n := stub.count(); n != 0 {
				t.Errorf("요청 %d건이 나갔다, want 0(보내기 전에 거절)", n)
			}
		})
	}
}

// respond200HugeError 는 상태 200 에 hugeChunk 를 128번(4MiB — 상한 s3errbody.MaxBytes 의 64배) 메시지로 채운 오류 본문을
// 싣는다. 클라이언트가 먼저 닫으면 쓰기를 멈춘다.
func respond200HugeError(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/xml")
	if _, err := fmt.Fprint(w, `<?xml version="1.0" encoding="UTF-8"?>`+"\n"+`<Error><Code>InternalError</Code><Message>`); err != nil {
		return
	}
	for range 128 {
		if _, err := w.Write(hugeChunk); err != nil {
			return
		}
	}
	_, _ = fmt.Fprint(w, `</Message></Error>`)
}

// Copy 의 「200 인데 오류」 본문도 상한까지만 읽는다 — SDK 는 그 본문을 상태 500 으로 바꿔 상한 없이 메모리에 복사하고, 조립점의
// s3errbody.Limit 은 2xx 를 감싸지 않는다(HIGH 1). Copy 는 연산별로 s3errbody.LimitAll 을 붙인다. 조립점과 같이 Limit 을 붙인
// 클라이언트로 잰다. 잡는 결함: 고장 나거나 적대적인 엔드포인트의 거대한 200 오류 본문으로 사이드카 메모리 고갈.
func TestS3StoreCopyLimitsHuge200ErrorBody(t *testing.T) {
	var raw rawBodyCounter
	store, stub := newStubbedStore(t, respond200HugeError, raw.option, s3errbody.Limit)

	err := store.Copy(context.Background(), copySrcKey, copyDstKey)

	if err == nil {
		t.Fatal("거대한 200 오류 본문의 Copy 가 오류가 아니다")
	}
	for _, s := range sentinels {
		if errors.Is(err, s) {
			t.Errorf("오류가 %v 다, want 센티널 아님(결과 모름)", s)
		}
	}
	if n := raw.read.Load(); n > s3errbody.MaxBytes+1 {
		t.Errorf("원 본문에서 %d바이트 읽음, want %d 이하", n, s3errbody.MaxBytes+1)
	}
	stub.only(t)
}

// init 짝(dvr/{s}/init/{세션}.mp4 → vod/{s}/init/{세션}.mp4)도 조각 짝과 같이 보낸다.
func TestS3StoreCopyAcceptsInitPair(t *testing.T) {
	store, stub := newStubbedStore(t, respondCopied)

	if err := store.Copy(context.Background(), "dvr/demo/init/sess-1.mp4", "vod/demo/init/sess-1.mp4"); err != nil {
		t.Fatalf("init 짝 Copy 실패: %v", err)
	}
	req := stub.only(t)
	if got, want := req.header.Values("X-Amz-Copy-Source"), []string{testBucket + "/dvr/demo/init/sess-1.mp4"}; !slices.Equal(got, want) {
		t.Errorf("x-amz-copy-source = %q, want %q", got, want)
	}
}

// deleteRequestBody 는 DeleteObjects 요청 본문(Delete 요소)이다.
type deleteRequestBody struct {
	Quiet   bool `xml:"Quiet"`
	Objects []struct {
		Key string `xml:"Key"`
	} `xml:"Object"`
}

// deleteStub 은 DeleteObjects 흉내다 — 요청마다 키 목록을 남기고, failKeys 에 든 키는 응답 Errors 칸으로 돌려준다.
type deleteStub struct {
	t        *testing.T
	failKeys map[string]bool

	mu      sync.Mutex
	batches [][]string
	quiet   []bool
}

func (d *deleteStub) respond(w http.ResponseWriter, r *http.Request) {
	var body deleteRequestBody
	if err := xml.NewDecoder(r.Body).Decode(&body); err != nil {
		d.t.Errorf("DeleteObjects 본문 해석 실패: %v", err)
	}
	keys := make([]string, len(body.Objects))
	for i, o := range body.Objects {
		keys[i] = o.Key
	}
	d.mu.Lock()
	d.batches = append(d.batches, keys)
	d.quiet = append(d.quiet, body.Quiet)
	d.mu.Unlock()

	w.Header().Set("Content-Type", "application/xml")
	fmt.Fprint(w, `<?xml version="1.0" encoding="UTF-8"?>`+"\n"+`<DeleteResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">`)
	for _, k := range keys {
		if d.failKeys[k] {
			fmt.Fprintf(w, `<Error><Key>%s</Key><Code>AccessDenied</Code><Message>Access Denied</Message></Error>`, k)
		}
	}
	fmt.Fprint(w, `</DeleteResult>`)
}

func (d *deleteStub) sent() ([][]string, []bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return slices.Clone(d.batches), slices.Clone(d.quiet)
}

// manyKeys 는 n 개의 서로 다른 사본 키다.
func manyKeys(n int) []string {
	keys := make([]string, n)
	for i := range keys {
		keys[i] = fmt.Sprintf("vod/demo/seg/%06d.m4s", i)
	}
	return keys
}

// Delete 는 DeleteObjects(POST /버킷?delete · Quiet)로 1000키씩 나눠 보낸다 — S3 한 요청의 상한이 1000키다. 키는 받은 순서
// 그대로 묶음에 실리고, 요청마다 무결성 머리(Content-MD5 또는 x-amz-checksum-*)가 붙는다(S3 가 이 연산에 요구한다). 잡는
// 결함: 1001키를 한 요청에 실어 MalformedXML 로 통째로 실패 · 경계에서 키를 빠뜨리거나 겹쳐 보냄 · Quiet 가 빠져 응답이 지운
// 키 전부로 부풂.
func TestS3StoreDeleteSendsQuietBatchesOf1000(t *testing.T) {
	cases := []struct {
		name      string
		n         int
		wantSizes []int
	}{
		{name: "1", n: 1, wantSizes: []int{1}},
		{name: "1000", n: 1000, wantSizes: []int{1000}},
		{name: "1001", n: 1001, wantSizes: []int{1000, 1}},
		{name: "2500", n: 2500, wantSizes: []int{1000, 1000, 500}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			d := &deleteStub{t: t}
			store, stub := newStubbedStore(t, d.respond)
			keys := manyKeys(tc.n)

			if err := store.Delete(context.Background(), keys); err != nil {
				t.Fatalf("Delete(%d키) 실패: %v", tc.n, err)
			}

			batches, quiet := d.sent()
			sizes := make([]int, len(batches))
			for i, b := range batches {
				sizes[i] = len(b)
			}
			if !slices.Equal(sizes, tc.wantSizes) {
				t.Errorf("묶음 크기 = %v, want %v", sizes, tc.wantSizes)
			}
			if got := slices.Concat(batches...); !slices.Equal(got, keys) {
				t.Errorf("보낸 키가 받은 키와 다르다(%d키 보냄, %d키 받음 — 순서 · 누락 · 겹침)", len(got), len(keys))
			}
			for i, q := range quiet {
				if !q {
					t.Errorf("묶음 %d 의 Quiet = false, want true", i)
				}
			}
			stub.mu.Lock()
			reqs := slices.Clone(stub.requests)
			stub.mu.Unlock()
			for i, req := range reqs {
				q, err := url.ParseQuery(req.query)
				if _, ok := q["delete"]; err != nil || !ok || req.method != http.MethodPost || req.path != "/"+testBucket {
					t.Errorf("요청 %d = %s %s?%s, want POST /%s?delete", i, req.method, req.path, req.query, testBucket)
				}
				if !hasIntegrityHeader(req.header) {
					t.Errorf("요청 %d 에 무결성 머리(Content-MD5 · x-amz-checksum-*)가 없다", i)
				}
			}
		})
	}
}

// hasIntegrityHeader 는 h 에 본문 무결성 머리가 있는가다.
func hasIntegrityHeader(h http.Header) bool {
	for name := range h {
		lower := strings.ToLower(name)
		if lower == "content-md5" || (strings.HasPrefix(lower, "x-amz-checksum-") && lower != "x-amz-checksum-algorithm") {
			return true
		}
	}
	return false
}

// 빈 목록은 요청을 보내지 않고 성공이다.
func TestS3StoreDeleteEmptySendsNothing(t *testing.T) {
	d := &deleteStub{t: t}
	store, stub := newStubbedStore(t, d.respond)

	if err := store.Delete(context.Background(), nil); err != nil {
		t.Errorf("Delete(nil) 오류 = %v, want nil", err)
	}
	if n := stub.count(); n != 0 {
		t.Errorf("요청 %d건, want 0", n)
	}
}

// 부분 실패(200 응답의 Errors 칸)는 오류다 — 못 지운 키를 오류 문장에 싣고, 그 뒤 묶음은 보내지 않는다(다시 부르면 같은 키를
// 처음부터 다시 보낸다 — 없는 키 삭제는 성공이라 무해하다). 잡는 결함: 상태 200 만 보고 성공으로 보고해 남은 사본을 놓침.
func TestS3StoreDeletePartialFailureIsError(t *testing.T) {
	keys := manyKeys(1001)
	d := &deleteStub{t: t, failKeys: map[string]bool{keys[7]: true}}
	store, _ := newStubbedStore(t, d.respond)

	err := store.Delete(context.Background(), keys)

	if err == nil {
		t.Fatal("Errors 칸이 있는 응답을 성공으로 보고했다")
	}
	if !strings.Contains(err.Error(), keys[7]) || !strings.Contains(err.Error(), "AccessDenied") {
		t.Errorf("오류 %q 에 못 지운 키 %q 와 코드 AccessDenied 가 없다", err, keys[7])
	}
	for _, s := range sentinels {
		if errors.Is(err, s) {
			t.Errorf("부분 실패 오류가 %v 다, want 센티널 아님", s)
		}
	}
	if batches, _ := d.sent(); len(batches) != 1 {
		t.Errorf("보낸 묶음 %d개, want 1(실패한 묶음에서 멈춘다)", len(batches))
	}
}

// 요청 자체의 실패(5xx · 권한)는 오류이고 시도는 묶음마다 한 번이다 — 재시도는 호출자 몫이다.
func TestS3StoreDeleteRequestFailure(t *testing.T) {
	for _, status := range []int{http.StatusServiceUnavailable, http.StatusForbidden} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			store, stub := newStubbedStore(t, respondError(status, "Stub"))

			if err := store.Delete(context.Background(), manyKeys(1001)); err == nil {
				t.Fatalf("%d 응답에 Delete 오류가 없다", status)
			}
			if n := stub.count(); n != 1 {
				t.Errorf("요청 %d건, want 1(첫 묶음 한 번에서 멈춘다)", n)
			}
		})
	}
}

// Delete 는 사본 키(playback.ParseVODPath 가 받는 vod/ 조각 · init 키)만 지운다 — ③ 원본(dvr/) · 목록 문서 · 이상한 키가
// 하나라도 있으면 아무 요청도 보내지 않고 errBadObjectKey 다(앞 묶음만 지운 채 멈추지 않게 먼저 전부 본다). 잡는 결함:
// 호출자가 원본 키를 넘겨 dvr/ 원본이 영구 삭제됨(버킷 버전 관리 없음 · 삭제 권한은 버킷 전체).
func TestS3StoreDeleteRejectsBadKeyBeforeSending(t *testing.T) {
	for _, bad := range []string{
		"dvr/demo/seg/000001.m4s",
		"dvr/demo/init/sess-1.mp4",
		"vod/demo/H1/index.m3u8",
		"vod/../dvr/demo/seg/000001.m4s",
		"vod/demo/seg/1.m4s",
		"",
	} {
		t.Run(fmt.Sprintf("%q", bad), func(t *testing.T) {
			keys := append(manyKeys(1500), bad)
			d := &deleteStub{t: t}
			store, stub := newStubbedStore(t, d.respond)

			if err := store.Delete(context.Background(), keys); !errors.Is(err, errBadObjectKey) {
				t.Errorf("키 %q 가 든 Delete 오류 = %v, want errBadObjectKey", bad, err)
			}
			if n := stub.count(); n != 0 {
				t.Errorf("요청 %d건이 나갔다, want 0", n)
			}
		})
	}
}

// 예외 하나 — 객체 탐침(①-9 · 판정 D)은 자기가 올린 원본 dvr/pcprobe-…/ 조각 · init 키를 사본과 함께 지운다. 탐침 스트림
// 이름(pcprobe- 접두)은 실제 스트림 ID 와 겹치지 않는다(E31). 다른 스트림의 dvr/ 키는 여전히 거절된다(위 시험).
func TestS3StoreDeleteAcceptsProbeSource(t *testing.T) {
	keys := []string{
		"dvr/pcprobe-pok525/seg/000000.m4s",
		"vod/pcprobe-pok525/seg/000000.m4s",
		"dvr/pcprobe-pok525/init/sess-1.mp4",
	}
	d := &deleteStub{t: t}
	store, _ := newStubbedStore(t, d.respond)

	if err := store.Delete(context.Background(), keys); err != nil {
		t.Fatalf("탐침 키 Delete 실패: %v", err)
	}
	if batches, _ := d.sent(); len(batches) != 1 || !slices.Equal(batches[0], keys) {
		t.Errorf("보낸 묶음 = %v, want [%v]", batches, keys)
	}
}
