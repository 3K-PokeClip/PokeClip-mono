// Package rewind 는 되감기 목록의 문법이다 — 장부 행을 HLS 미디어 재생목록 본문으로 옮긴다.
//
// 설계 3.2 의 rewind/(「목록의 문법과 경계」)이며, 어느 행을 실을지(경계)는 하위 패키지 boundary 가
// 정한다. DB·S3·HTTP·파일 시스템·환경 변수를 모른다 — 입력은 값(Playlist)이고 출력은 바이트다.
// 같은 입력이면 같은 바이트를 내는 순수 계층이라 로그도 남기지 않는다.
//
// 지키는 불변식(프로필 4절): 조각 URI 는 영구히 고정된다 — 세션·티어 축이 없는 장부의
// playback_s3_key 그대로다(설계 5.2 D1). 본문은 순수 HLS 다 — 우리 용도의 줄(#PC-)·주석·빈 줄이
// 없고, 세대 정보는 본문 밖 메타데이터로 간다(설계 4.4.2).
package rewind

import (
	"bytes"
	"errors"
	"fmt"
	"strings"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// pdtLayout 은 EXT-X-PROGRAM-DATE-TIME 의 서식이다 — UTC·밀리초 세 자리(설계 4.8.2 실물,
// RFC 8216bis-22 4.4.4.6 "to at least millisecond accuracy"). 밀리초 아래는 Format 이 버린다.
const pdtLayout = "2006-01-02T15:04:05.000Z07:00"

// Session 은 목록이 읽는 회차(stream_sessions 한 행)다. 앞의 넷은 컬럼과 1:1 이고 NULL 은 영값이다.
type Session struct {
	// ID 는 session_id 다. MAP 의 init 키(playback.InitKey)가 이 값으로 갈린다.
	ID string
	// InheritsSession 은 inherits_session 이다. "" 면 NULL — 직전 회차를 계승하지 않은 회차다.
	InheritsSession string
	// DiscontinuityBase 는 discontinuity_base 다. 목록 소유 회차의 이 값이 곧
	// EXT-X-DISCONTINUITY-SEQUENCE 다.
	DiscontinuityBase int64
	// TargetDuration 은 target_duration(초)이다. 목록 소유 회차의 이 값이 곧 EXT-X-TARGETDURATION 이다
	// — 세션 개시 때 정해져 수명 동안 바뀌지 않으므로 목록의 TD 도 바뀌지 않는다(설계 4.9).
	TargetDuration int32
	// MinSeq 는 장부에서 이 회차에 귀속된 행의 가장 작은 seq 다 — 끊김 표시 술어의 재료다.
	// stream_sessions.first_seq 컬럼이 아니다(세션 개시 문장이 그 컬럼을 채우지 않는다). 컷오프
	// 아래 행을 알고 있으면 그 seq 여도 된다 — 술어가 컷오프로 자른다(HasDiscontinuityTag).
	MinSeq int64
}

// Playlist 는 되감기 목록 하나를 값으로 적은 것이다 — Render 의 입력이다.
//
// 목록은 되감기 URL(/dvr/{stream}/{session}/index.m3u8)의 그 회차(Owner)가 소유한다. 행은 경계가
// 정한 창([TailSeq, HeadSeq])에서 이 목록에 실을 것만 골라 seq 오름차순으로 준다 — 계승 회차면 직전
// 회차의 조각(계승 백필)이 앞에 온다. 고르는 규칙과 발행 전 검사(설계 4.5.5 S1–S7)는 이 입력 밖이다.
type Playlist struct {
	// StreamID 는 stream_id 다. MAP 의 init 키 첫 성분이다.
	StreamID string
	// BaseURL 은 조각·MAP URI 의 앞머리다 — 끝에 '/' 가 없는 절대 URL(예 https://media.pokeclip.com).
	// URI 는 BaseURL + "/" + 키다.
	BaseURL string
	// Cutoff 는 그 스트림의 활성화 컷오프(stream_cutoffs.cutoff_seq)다 — 끊김 표시 술어의 하한이다.
	Cutoff int64
	// Owner 는 목록 소유 회차의 session_id 다. 머리의 TARGETDURATION·DISCONTINUITY-SEQUENCE 가 이
	// 회차의 값이다 — 목록 첫 줄이 계승 백필이어도 그렇다.
	Owner string
	// Sessions 는 Rows 가 귀속된 회차 전부다(Owner 포함).
	Sessions []Session
	// Rows 는 목록에 실을 장부 행이다 — seq 가 1씩 이어지는 오름차순이고 전부 settled 다.
	Rows []boundary.Row
}

// Render 는 목록을 HLS 미디어 재생목록 본문으로 쓴다 — 설계 4.8.2 실물의 문법과 줄 순서 그대로다.
// VERSION 만 kty 결정으로 6 이다(계획 부기 32 — 설계의 9 는 bis-22 6.2.1 "필요보다 높게 적지
// 말라"에 걸리고, 이 목록이 쓰는 태그의 요구 최솟값은 EXT-X-MAP 의 6 이다).
//
//	#EXTM3U
//	#EXT-X-VERSION:6
//	#EXT-X-TARGETDURATION:<소유 회차 target_duration>
//	#EXT-X-MEDIA-SEQUENCE:<첫 줄 조각의 seq>
//	#EXT-X-DISCONTINUITY-SEQUENCE:<소유 회차 discontinuity_base>
//	조각마다 이 순서로:
//	  #EXT-X-DISCONTINUITY                      HasDiscontinuityTag 가 참일 때
//	  #EXT-X-MAP:URI="<BaseURL>/<init 키>"      회차가 바뀌는 첫 줄(목록 첫 줄 포함)
//	  #EXT-X-GAP                                GAP 원장에 있는 행
//	  #EXT-X-PROGRAM-DATE-TIME:<playback_pdt>
//	  #EXTINF:<duration_ms 를 초로, 소수 셋째 자리>,
//	  <BaseURL>/<playback_s3_key>
//
// MSN 이 첫 줄 조각의 seq 인 것이 계약이다(위치 카운터가 아니다 — 설계 4.8 대안 B 기각). 그래서
// 목록 안 seq 는 끊김 없이 이어져야 하고(빈자리는 GAP 줄이 메운다), 이어지지 않으면 오류다.
// DISCONTINUITY-SEQUENCE 는 base 그대로이며 렌더는 아무것도 더하지 않는다 — 증분은 축출 쪽 일이다.
// PDT 는 조각마다 장부 값을 싣는다: EXTINF 누적으로 대신하면 900조각에서 최대 0.45초 어긋나고(설계
// 4.8.2), 회차 안 끊김은 표시 없이 PDT 점프로만 드러난다(계획 4.2-R RF). 조각 키는 다시 만들지
// 않는다 — ③ PUT 이 INSERT 때 예약한 키를 그대로 쓰므로 목록도 그 키를 가리켜야 한다.
// ENDLIST 는 붙이지 않는다 — 목록을 닫는 경로는 따로다.
//
// 렌더할 수 없는 입력(빈 목록 · 쓸 수 없는 base URL · 회차 목록에 없는 회차 · 끊긴 seq · init 키
// 파생 실패)은 본문 없이 오류다. 발행된 줄은 고칠 수 없고 조각 URL 은 영구 고정이라, 틀린 본문을
// 조용히 내느니 발행을 멈추는 편이 낫다.
func Render(p Playlist) ([]byte, error) {
	if len(p.Rows) == 0 {
		return nil, errors.New("rewind: 실을 조각이 없다 — MSN(첫 줄 조각의 seq)이 정의되지 않는다")
	}
	if p.BaseURL == "" || strings.HasSuffix(p.BaseURL, "/") {
		return nil, fmt.Errorf("rewind: base URL %q 를 쓸 수 없다 — 비었거나 '/' 로 끝난다(URI = base + \"/\" + 키)", p.BaseURL)
	}
	owner, ok := p.session(p.Owner)
	if !ok {
		return nil, fmt.Errorf("rewind: 소유 회차 %q 가 회차 목록에 없다", p.Owner)
	}

	var b bytes.Buffer
	fmt.Fprintf(&b, "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:%d\n", owner.TargetDuration)
	fmt.Fprintf(&b, "#EXT-X-MEDIA-SEQUENCE:%d\n#EXT-X-DISCONTINUITY-SEQUENCE:%d\n", p.Rows[0].Seq, owner.DiscontinuityBase)
	for i, r := range p.Rows {
		if i > 0 && r.Seq != p.Rows[i-1].Seq+1 {
			return nil, fmt.Errorf("rewind: 목록 안 seq 가 %d 다음에 %d 다 — 1씩 이어져야 MSN 이 조각 번호와 맞는다",
				p.Rows[i-1].Seq, r.Seq)
		}
		newRun := i == 0 || r.SessionID != p.Rows[i-1].SessionID
		if err := p.writeSegment(&b, r, newRun); err != nil {
			return nil, err
		}
	}
	return b.Bytes(), nil
}

// writeSegment 는 조각 하나(태그 줄들과 URI 줄)를 쓴다. newRun 은 이 행에서 회차가 바뀌는가다 —
// MAP 은 회차가 바뀌는 줄에만 쓴다(회차 안 init 은 같은 바이트다 — ADR-073).
func (p Playlist) writeSegment(b *bytes.Buffer, r boundary.Row, newRun bool) error {
	s, ok := p.session(r.SessionID)
	if !ok {
		return fmt.Errorf("rewind: seq %d 의 회차 %q 가 회차 목록에 없다", r.Seq, r.SessionID)
	}
	if HasDiscontinuityTag(r.Seq, s, p.Cutoff) {
		b.WriteString("#EXT-X-DISCONTINUITY\n")
	}
	if newRun {
		key, err := playback.InitKey(p.StreamID, s.ID)
		if err != nil {
			return fmt.Errorf("rewind: seq %d 의 MAP 키 파생 실패: %w", r.Seq, err)
		}
		fmt.Fprintf(b, "#EXT-X-MAP:URI=\"%s/%s\"\n", p.BaseURL, key)
	}
	if r.IsGap {
		b.WriteString("#EXT-X-GAP\n")
	}
	fmt.Fprintf(b, "#EXT-X-PROGRAM-DATE-TIME:%s\n", r.PlaybackPDT.UTC().Format(pdtLayout))
	// duration_ms 는 양수다(쓰기 경로의 폭 검증 — indexer H7). 정수 나눗셈이라 자릿수가 정확하다.
	fmt.Fprintf(b, "#EXTINF:%d.%03d,\n", r.DurationMS/1000, r.DurationMS%1000)
	fmt.Fprintf(b, "%s/%s\n", p.BaseURL, r.PlaybackS3Key)
	return nil
}

// session 은 회차 목록에서 id 인 회차를 찾는다. 한 목록이 걸치는 회차는 한두 개라 차례로 본다.
func (p Playlist) session(id string) (Session, bool) {
	for _, s := range p.Sessions {
		if s.ID == id {
			return s, true
		}
	}
	return Session{}, false
}
