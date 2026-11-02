package publish

// VOD 동결의 옛 판 채택 PG 시험이다(POK-525 설계 r7 4-9 「교체 순간의 경합」 · 계획 ①-10 · E85 · M87).

import (
	"bytes"
	"log/slog"
	"strings"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
)

// renderedVOD 는 FreezeVOD 가 지금 장부로 렌더할 새 판 본문이다(쓰지 않는다).
func renderedVOD(t *testing.T, p *Publisher, v *vodJob) []byte {
	t.Helper()
	l, plan, out, ok := v.load(t.Context(), false)
	if !ok {
		t.Fatalf("장부 읽기 실패: %+v", out)
	}
	body, err := rewind.Render(v.playlist(l, plan.rows))
	if err != nil {
		t.Fatalf("렌더 실패: %v", err)
	}
	return body
}

// editVOD 는 본문 doc 에 edits(옛 문자열 · 새 문자열 쌍)를 차례로 한 번씩 적용한다.
func editVOD(t *testing.T, doc string, edits ...string) []byte {
	t.Helper()
	for i := 0; i+1 < len(edits); i += 2 {
		if !strings.Contains(doc, edits[i]) {
			t.Fatalf("고칠 문자열 %q 가 본문에 없다", edits[i])
		}
		doc = strings.Replace(doc, edits[i], edits[i+1], 1)
	}
	return []byte(doc)
}

// plantVOD 는 다른 writer 가 먼저 올린 VOD 문서 body 를 사슬 키에 둔다 — 메타의 pc-body-sha256 은 그 본문의 sha 다(옛
// 이미지도 같은 메타를 쓴다).
func plantVOD(store *fakeStore, body []byte) {
	plant(store, putCall{key: vodChainKey, body: body, meta: map[string]string{metaSession: "P", metaBodySHA256: encodeSHA(sha256Of(body))}})
}

// plantLegacyVOD 는 옛 이미지(사본 없음 · /dvr URI)가 먼저 올린 VOD 문서를 사슬 키에 둔다 — 새 판 본문의 앞머리를
// /dvr 로 바꾸고 edits 를 적용한 본문이다. 둔 본문을 돌려준다.
func plantLegacyVOD(t *testing.T, p *Publisher, store *fakeStore, v *vodJob, edits ...string) []byte {
	t.Helper()
	doc := strings.ReplaceAll(string(renderedVOD(t, p, v)), fxBaseURL+"/vod/", fxBaseURL+"/dvr/")
	body := editVOD(t, doc, edits...)
	plantVOD(store, body)
	return body
}

// legacyAdoptions 는 p 가 남긴 vod_adopted_legacy 로그다.
func legacyAdoptions(p *Publisher) []logRec {
	var out []logRec
	for _, r := range p.log.Handler().(*logRecorder).recs {
		if r.msg == vodAdoptedLegacyLog {
			out = append(out, r)
		}
	}
	return out
}

// vod_adopted_legacy(E85) — 교체 겹침에서 옛 이미지가 옛 판(/dvr URI) 문서를 먼저 올려 새 이미지의 IfAbsent 가 412 면,
// 조각 단위로 같을 때 그 판을 채택해 옛 본문의 sha · 그 판의 ETag 로 사슬을 확정하고 WARN 을 낸다(이관 대상). 변이 M87
// = 채택 갈래 제거 → vod_conflict 로 FAIL.
func TestFreezeVODAdoptsLegacyBodyAfter412(t *testing.T) {
	p, store, chain := newVODChain(t, chainRows())
	v := &vodJob{p: p, chain: chain, key: vodChainKey}
	legacy := plantLegacyVOD(t, p, store, v,
		"#EXT-X-VERSION:6\n", "#EXT-X-VERSION:6\n#EXT-X-INDEPENDENT-SEGMENTS\n",
		"#EXT-X-PROGRAM-DATE-TIME:20", "#EXT-X-PROGRAM-DATE-TIME:19")

	out := p.FreezeVOD(t.Context(), chain, false)

	wantOutcome(t, out, AxisVOD, ResultFrozen, false)
	if body, _ := stored(store, vodChainKey); !bytes.Equal(body, legacy) {
		t.Fatalf("VOD 키 본문이 바뀌었다 — 옛 판을 덮으면 안 된다\n%s", body)
	}
	wantVODFrozen(t, p, store, ResultFrozen, 0, 9)
	logs := legacyAdoptions(p)
	if len(logs) != 1 || logs[0].level != slog.LevelWarn || logs[0].attrs["stream"] != fxStream ||
		logs[0].attrs["key"] != vodChainKey || logs[0].attrs["session"] != "P" {
		t.Errorf("vod_adopted_legacy 로그 = %+v, want WARN 한 줄(stream %s · key %s · session P)", logs, fxStream, vodChainKey)
	}
}

// 옛 판이라도 조각 단위로 다르면 채택하지 않는다 — 지금처럼 vod_conflict 실패이고 장부는 freezing 그대로다(덮어쓰지
// 않는다). 앞머리가 새 판(/vod)인 다른 본문은 조각이 같아도 옛 판 갈래로 가지 않는다. 변이(동일성 · 앞머리 검사 제거)
// → 채택돼 FAIL.
func TestFreezeVODRefusesLegacyBodyWithDifferentSegments(t *testing.T) {
	cases := []struct {
		name  string
		ns    string
		edits []string
	}{
		{"조각 하나 다름", "/dvr/", []string{"/dvr/str/seg/000003.m4s", "/dvr/str/seg/000033.m4s"}},
		{"조각 수 다름", "/dvr/", []string{"/dvr/str/seg/000009.m4s\n", "/dvr/str/seg/000009.m4s\n#EXTINF:4.000,\n" + fxBaseURL + "/dvr/str/seg/000010.m4s\n"}},
		{"조각 순서 다름", "/dvr/", []string{"seg/000001.m4s", "seg/X.m4s", "seg/000002.m4s", "seg/000001.m4s", "seg/X.m4s", "seg/000002.m4s"}},
		{"조각이 같은 새 판 앞머리의 다른 본문", "/vod/", []string{"#EXT-X-VERSION:6\n", "#EXT-X-VERSION:6\n#EXT-X-INDEPENDENT-SEGMENTS\n"}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			p, store, chain := newVODChain(t, chainRows())
			v := &vodJob{p: p, chain: chain, key: vodChainKey}
			doc := strings.ReplaceAll(string(renderedVOD(t, p, v)), fxBaseURL+"/vod/", fxBaseURL+c.ns)
			planted := editVOD(t, doc, c.edits...)
			plantVOD(store, planted)

			out := p.FreezeVOD(t.Context(), chain, false)

			wantOutcome(t, out, AxisVOD, ResultFailed, false)
			if st := readVOD(t, p, "S"); out.Reason != vodConflict || st.state != "freezing" {
				t.Errorf("사유 %q · 상태 %q, want %s · freezing", out.Reason, st.state, vodConflict)
			}
			if body, _ := stored(store, vodChainKey); !bytes.Equal(body, planted) {
				t.Errorf("VOD 키 본문이 바뀌었다\n%s", body)
			}
			if logs := legacyAdoptions(p); len(logs) != 0 {
				t.Errorf("vod_adopted_legacy 로그 = %+v, want 없음", logs)
			}
		})
	}
}

// 새 판 문서가 이미 있으면(같은 sha — 앞 시도가 쓰고 확정 전에 멈춤 · D7) 기존 채택 경로 그대로다 — 본문을 읽지 않고
// Head 의 판으로 확정하며 옛 판 로그가 없다.
func TestFreezeVODAdoptsCurrentBodyWithoutGet(t *testing.T) {
	p, store, chain := newVODChain(t, chainRows())
	v := &vodJob{p: p, chain: chain, key: vodChainKey}
	plantVOD(store, renderedVOD(t, p, v))

	wantOutcome(t, p.FreezeVOD(t.Context(), chain, false), AxisVOD, ResultFrozen, false)

	wantVODFrozen(t, p, store, ResultFrozen, 0, 9)
	if gets := store.getKeys(); len(gets) != 0 {
		t.Errorf("Get 호출 %v, want 없음", gets)
	}
	if logs := legacyAdoptions(p); len(logs) != 0 {
		t.Errorf("vod_adopted_legacy 로그 = %+v, want 없음", logs)
	}
}

// 옛 판을 보려고 본문을 읽다가 실패하면(그 사이 객체가 사라짐 · 일시 오류) 결과를 모르니 재시도(get_failed)이고 장부는
// freezing 그대로다. 변이(Get 실패를 head_failed 로) → FAIL.
func TestFreezeVODRetriesWhenLegacyGetFails(t *testing.T) {
	p, store, chain := newVODChain(t, chainRows())
	plantLegacyVOD(t, p, store, &vodJob{p: p, chain: chain, key: vodChainKey})
	store.setOnGet(func() { remove(store, vodChainKey) })

	out := p.FreezeVOD(t.Context(), chain, false)

	wantOutcome(t, out, AxisVOD, ResultRetry, true)
	if st := readVOD(t, p, "S"); out.Reason != freezeGetFailed || st.state != "freezing" {
		t.Errorf("사유 %q · 상태 %q, want %s · freezing", out.Reason, st.state, freezeGetFailed)
	}
}
