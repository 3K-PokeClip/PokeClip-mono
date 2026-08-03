// Package recording 은 파일 시스템 쪽을 담당한다.
// 녹화 폴더에 새 파일이 생겼는지 감지하고, 다 써졌는지 판정해 완성된 세그먼트만 밖으로 내보낸다.
// DB는 전혀 모른다(설계 2.1절).
package recording

import (
	"errors"
	"fmt"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// CompletionReason 은 세그먼트가 "왜 완성됐다고 판정됐는지"를 구분한다.
// 사유마다 뒷단 처리가 달라지므로(기대 길이·학습 대상 여부) 반드시 구분해 들고 다닌다.
type CompletionReason uint8

const (
	// ReasonUnknown 은 Go 의 zero value 다. 즉 0 = "아무도 사유를 안 채웠다"는 사고 신호이며,
	// 이 값이 정상 경로에 존재하면 버그다. H0(사유 검증)이 존재하는 이유가 바로 이 zero value 다.
	ReasonUnknown CompletionReason = 0
	// ReasonNextFile 은 후속 파일 CREATE 로 완성 판정된 경우다. 이 경우만 4000ms 를 기대할 수 있어
	// 길이 승격(H6)과 학습의 대상이 된다.
	ReasonNextFile CompletionReason = 1
	// ReasonIdle 은 유휴 타임아웃으로 완성 판정된 경우다. 방송 종료일 수 있어 정당하게 짧을 수 있고,
	// 커밋 직전 mtime 재검(H4) 대상이다.
	ReasonIdle CompletionReason = 2
	// ReasonScan 은 기동 스캔에서 즉시 확정된 옛 파일이다. 기대 길이를 알 길이 없어 승격·학습에서 제외한다.
	ReasonScan CompletionReason = 3
	// ReasonRegrown 은 확정 후 파일이 더 자란 경우다. 새 행 INSERT 가 아니라 UpdateTail 경로로 간다(H1).
	ReasonRegrown CompletionReason = 4
	// ReasonHook 은 MediaMTX 의 runOnRecordSegmentComplete 훅으로 완성 판정된 경우다.
	//
	// 승격(H6)·학습 대상에서 제외된다 — 두 장치의 조건이 ReasonNextFile 한정이라 코드 변경 없이
	// 성립한다. 제외가 안전한 근거: 승격이 방어하려던 "덜 써진 파일을 읽었다"는 위험이 훅 경로에는
	// 실측상 없고(훅 시점 파일이 이미 최종 크기 29/29), 방송 마지막 partial 조각도 같은 사유로
	// 도착하므로 승격 대상에 넣으면 매 방송 재프로브가 헛돌아 reprobe_disabled 가 켜진다.
	ReasonHook CompletionReason = 5
)

// Segment 는 완성된 세그먼트 파일 하나를 표현한다.
type Segment struct {
	// StreamID 는 recordings 루트 기준 상대 디렉토리다(= MediaMTX 의 %path).
	// 아래 streamIDRe 화이트리스트를 통과한 값만 들어온다 — 슬래시는 포함될 수 없다.
	StreamID string
	// Path 는 절대 경로이며 그대로 DB 의 local_path 가 된다.
	Path string
	// StartWall 은 파일명에서 파싱한 녹화 시작 시각이며 항상 UTC 다(D6).
	// mtime 은 세그먼트의 끝 시각에 가까워 시작 시각으로 쓸 수 없다.
	StartWall time.Time
	// Reason 은 상황을 아는 호출자가 채운다. ParseSegmentPath 는 채우지 않는다.
	Reason CompletionReason
}

// ErrInvalidStreamID 는 스트림 이름이 화이트리스트를 통과하지 못했음을 뜻한다.
// 호출자는 이것을 "세그먼트 파일이 아님"(조용히 무시)과 구분해 WARN 을 남긴다.
var ErrInvalidStreamID = errors.New("허용되지 않는 stream_id 형태다")

// streamIDRe 는 stream_id 화이트리스트다.
//
// 왜 화이트리스트인가: stream_id 는 파일 시스템에서 온 이름인데, 그대로 DB 값이 되고
// S3Key 의 경로 조각이 된다. 즉 외부 입력이 경계를 넘어 저장소 키까지 흘러간다.
// 무엇을 막을지 나열하는 대신(빠뜨리기 쉽다) 무엇을 허용할지 좁게 못 박는다.
//
// 슬래시를 허용하지 않으므로 중첩 %path(예: live/kr/demo)는 거부된다.
// 근거: 현재 실사용이 로컬 단일 경로뿐이고, 중첩을 허용하면 stream_id 가 경로 구조를
// 품게 되어 키 생성과 디렉토리 구조가 얽힌다. 중첩이 실제로 필요해지는 시점은
// U9(프로덕션 stream_id 의 의미가 계약4 스트림키와 어떻게 연결되는가)가 확정될 때이며,
// 그때 이 정규식과 아래 함수 본문만 고치면 된다.
var streamIDRe = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

// segmentNameRe 는 recordPath 의 %Y-%m-%d_%H-%M-%S-%f 를 그대로 옮긴 것이다(mediamtx.yml 31행).
// 마지막 그룹(%f)의 자리수는 고정하지 않는다 — 자리수가 바뀌어도 흡수하기 위해서다(U6).
var segmentNameRe = regexp.MustCompile(`^(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})-(\d+)$`)

// ParseSegmentPath 는 녹화 파일 경로 하나를 뜯어 Segment 로 만든다.
// 세그먼트 파일이 아니면 에러를 돌려준다 — 호출자가 조용히 넘기지 않도록 명시적으로 실패시킨다.
//
// U9(프로덕션 stream_id 의 의미)를 이 함수 하나에 가둬 둔다. 계약4 스트림키와의 관계가
// 확정되면 여기 본문만 바꾸면 된다.
func ParseSegmentPath(root, path string) (Segment, error) {
	rel, err := filepath.Rel(filepath.Clean(root), filepath.Clean(path))
	if err != nil {
		return Segment{}, fmt.Errorf("루트 기준 상대 경로 계산 실패 root=%q path=%q: %w", root, path, err)
	}
	// ".." 로 시작하면 루트 밖이다. 루트 밖 파일을 스트림으로 오인하면 엉뚱한 stream_id 가 생긴다.
	if rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return Segment{}, fmt.Errorf("루트 %q 밖의 경로다: %q", root, path)
	}

	dir, base := filepath.Split(rel)
	streamID := strings.TrimSuffix(dir, string(filepath.Separator))
	// 스트림 디렉토리 없이 루트 바로 아래 놓인 파일은 어느 방송인지 알 수 없다.
	if streamID == "" {
		return Segment{}, fmt.Errorf("스트림 디렉토리가 없다: %q", path)
	}
	if !streamIDRe.MatchString(streamID) {
		return Segment{}, fmt.Errorf("%w: %q (경로 %q)", ErrInvalidStreamID, streamID, path)
	}

	startWall, err := parseStartWall(base)
	if err != nil {
		return Segment{}, fmt.Errorf("세그먼트 파일명이 아니다 %q: %w", path, err)
	}

	return Segment{
		StreamID:  streamID,
		Path:      path,
		StartWall: startWall,
	}, nil
}

// parseStartWall 은 파일명(확장자 포함 가능)에서 시작 시각을 뽑는다.
// 확장자는 U1(녹화 파일 확장자 미확인) 때문에 값을 보지 않고 잘라 버린다.
func parseStartWall(base string) (time.Time, error) {
	stem := strings.TrimSuffix(base, filepath.Ext(base))

	m := segmentNameRe.FindStringSubmatch(stem)
	if m == nil {
		return time.Time{}, fmt.Errorf("recordPath 형식 %%Y-%%m-%%d_%%H-%%M-%%S-%%f 과 다르다: %q", stem)
	}

	nums := make([]int, 6)
	for i := range nums {
		// 정규식이 숫자만 통과시키므로 Atoi 는 실패하지 않는다.
		nums[i], _ = strconv.Atoi(m[i+1])
	}
	nsec, err := fractionToNanos(m[7])
	if err != nil {
		return time.Time{}, err
	}

	return time.Date(nums[0], time.Month(nums[1]), nums[2], nums[3], nums[4], nums[5], nsec, time.UTC), nil
}

// fractionToNanos 는 %f 자리를 "초의 소수부"로 읽어 나노초로 바꾼다.
// 6자리(마이크로초)를 기대하지만 자리수를 고정하지 않는다 — 3자리든 9자리든 소수부 해석은 같다(U6).
// 나노초를 넘는 자리는 버린다. time.Date 가 나노초까지만 표현하기 때문이다.
func fractionToNanos(digits string) (int, error) {
	const nanoDigits = 9
	if len(digits) > nanoDigits {
		digits = digits[:nanoDigits]
	}
	padded := digits + strings.Repeat("0", nanoDigits-len(digits))
	n, err := strconv.Atoi(padded)
	if err != nil {
		return 0, fmt.Errorf("소수부 해석 실패 %q: %w", digits, err)
	}
	return n, nil
}
