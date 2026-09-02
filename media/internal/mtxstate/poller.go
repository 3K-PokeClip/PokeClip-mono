package mtxstate

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	// DefaultPollInterval 은 OBS_POLL 의 기본값이다(설계 5.4.1).
	// **이 값이 유일한 집이다** — config 가 리터럴로 10s 를 또 적으면 두 곳이 언젠가 어긋난다.
	DefaultPollInterval = 10 * time.Second
	// DefaultBootWait 은 OBS_BOOT_WAIT 의 기본값이다(설계 6.5.3 ⑶).
	// 기동을 3 초까지만 늦춘다 — 그 창에 들어온 파일은 초기 수집이 회수한다.
	DefaultBootWait = 3 * time.Second

	// pathsListPath 는 관측에 쓰는 유일한 엔드포인트다(F-34 실측으로 확정).
	pathsListPath = "/v3/paths/list"

	// 폴 실패 사유 라벨. **둘뿐이다** — 사유를 늘리면 대시보드가 갈라진다.
	reasonRequestFailed = "request_failed" // 요청·상태코드·파싱 실패가 모두 여기로 합류한다
	reasonMultiPage     = "multi_page"     // 응답이 여러 페이지라 원자 스냅샷이 아니다
)

// Options 는 폴러의 설정이다. 값의 출처는 config(env 6종 중 3종)다.
type Options struct {
	// APIURL 은 MediaMTX Control API 의 베이스 URL 이다(예: http://media:9997).
	//
	// **빈 값이면 폴러를 아예 만들지 않는다**(NewPoller 가 거부한다). 그것이 즉시 롤백
	// 스위치다 — 관측이 사라지면 ⓐ2 가 fail-closed 되어 주조만 멈추고 인덱싱은 그대로다.
	// HOOK_SPOOL_PATH 와 같은 형태의 스위치이며, 그래서 config 도 기본값을 두지 않는다.
	APIURL string
	// PollInterval 은 폴 주기이자 **poll 1 회의 상한**이다(OBS_POLL).
	// 두 뜻을 한 값이 지는 이유: 주기가 곧 재시도다. 헤더조차 오지 않는 응답에서
	// 고루틴이 영구 정지하면 스냅샷이 영원히 낡은 채 회복 경로가 사라진다.
	// 0 이면 DefaultPollInterval.
	PollInterval time.Duration
	// BootWait 은 WaitFirstObservation 이 첫 관측을 기다리는 상한이다(OBS_BOOT_WAIT).
	// 0 이면 DefaultBootWait.
	BootWait time.Duration
	Log      *slog.Logger
}

// Poller 는 Control API 를 주기적으로 읽어 스냅샷을 갈아 끼운다.
//
// 폴은 **직렬**이다(고루틴 하나 + 티커). 그래서 스냅샷 교체가 poll 시작 시각 기준으로
// 자연히 단조가 되고, 늦게 끝난 옛 poll 이 새 스냅샷을 덮는 국면 자체가 없다 —
// 별도 비교 가드를 두지 않는 이유가 이것이다(도달 불가 분기를 만들지 않는다).
type Poller struct {
	opt    Options
	client *http.Client

	// snap 은 마지막으로 성공한 poll 의 스냅샷이다. nil 이면 관측 이력 0 이다.
	snap atomic.Pointer[snapshot]

	// first 는 첫 성공 스냅샷에서 닫힌다. WaitFirstObservation 이 이것만 본다.
	first     chan struct{}
	firstOnce sync.Once
	done      chan struct{}
}

// NewPoller 는 폴러를 만든다. 설정이 잘못됐으면 기동 시점에 실패시킨다.
func NewPoller(opt Options) (*Poller, error) {
	if strings.TrimSpace(opt.APIURL) == "" {
		return nil, errors.New("MediaMTX Control API URL 이 비었다 — 빈 값이면 폴러를 아예 만들지 않아야 한다")
	}
	if opt.PollInterval <= 0 {
		opt.PollInterval = DefaultPollInterval
	}
	if opt.BootWait <= 0 {
		opt.BootWait = DefaultBootWait
	}
	if opt.Log == nil {
		opt.Log = slog.Default()
	}
	opt.APIURL = strings.TrimSuffix(strings.TrimSpace(opt.APIURL), "/")
	return &Poller{
		opt: opt,
		// 클라이언트 타임아웃을 두지 않는다 — 상한은 poll 마다 거는 컨텍스트가 지고,
		// 두 곳에 상한이 있으면 어느 쪽이 끊었는지 로그로 구분되지 않는다.
		client: &http.Client{},
		first:  make(chan struct{}),
		done:   make(chan struct{}),
	}, nil
}

// Start 는 폴 고루틴을 띄우고 즉시 반환한다.
//
// **첫 폴을 티커 전에 한 번 돌린다.** 기다렸다 돌리면 BootWait(3초)이 PollInterval(10초)
// 보다 짧아 첫 관측이 언제나 타임아웃한다.
func (p *Poller) Start(ctx context.Context) {
	go p.run(ctx)
}

// Done 은 폴 고루틴이 끝나면 닫힌다.
func (p *Poller) Done() <-chan struct{} { return p.done }

// WaitFirstObservation 은 첫 성공 관측을 BootWait 까지 기다린다(설계 6.5.3 ⑶ 2단계).
//
// 타임아웃이면 거짓을 돌려주고 **기동은 그대로 계속된다** — 관측 없이 뜬 상태는
// EpochKnown=false 라 ⓐ2 가 fail-closed 될 뿐이고, 그 창의 파일은 초기 수집이 회수한다.
// 상한을 인자로 받지 않는 이유: 값의 집을 Options 하나로 둔다(config 가 채운다).
func (p *Poller) WaitFirstObservation(ctx context.Context) bool {
	t := time.NewTimer(p.opt.BootWait)
	defer t.Stop()
	select {
	case <-p.first:
		return true
	case <-t.C:
		return false
	case <-ctx.Done():
		return false
	}
}

func (p *Poller) run(ctx context.Context) {
	defer close(p.done)

	p.pollOnce(ctx)

	t := time.NewTicker(p.opt.PollInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			p.pollOnce(ctx)
		}
	}
}

// pollOnce 는 목록을 한 번 읽어 성공했을 때만 스냅샷을 갈아 끼운다.
//
// 실패는 에러로 올리지 않는다 — 관측 고장은 강등이지 사망이 아니고(설계 S3),
// 다음 주기가 곧 재시도다. 대신 반드시 신호를 남긴다(무징후 금지 — 설계 S4).
func (p *Poller) pollOnce(ctx context.Context) {
	// ObservedAt 은 poll **시작** 시각이다. 완료 시각을 쓰면 왕복 시간만큼 관측이
	// 실제보다 신선해 보여 판정 창 공시(90초)와 어긋난다.
	observedAt := time.Now().UTC()

	ctx, cancel := context.WithTimeout(ctx, p.opt.PollInterval)
	defer cancel()

	list, err := p.fetch(ctx)
	if err != nil {
		p.pollFailed(reasonRequestFailed, err)
		return
	}
	// pageCount ≤ 1 인 응답만 서버 시점의 원자 스냅샷이다(0 = 빈 목록도 정상).
	// 여러 페이지는 **그 poll 을 통째로 버린다** — 이어 붙이면 페이지 사이의 삭제·추가가
	// itemCount·pageCount 를 그대로 둔 채 목록만 어긋나게 만든다(계획 3절 ⑴).
	if list.PageCount > 1 {
		p.pollFailed(reasonMultiPage, fmt.Errorf("pageCount=%d (원자 스냅샷이 아니다)", list.PageCount))
		return
	}

	p.snap.Store(newSnapshot(observedAt, list.Items))
	p.firstOnce.Do(func() { close(p.first) })
}

// pollFailed 는 스냅샷을 건드리지 않고 신호만 남긴다.
//
// **직전 스냅샷을 유지하는 것이 계약이다**(계획 3절 ⑴): 성공 이력이 있으면 그 관측이
// OBS_FRESH 로 자연 만료되게 두고, 이력이 없으면 애초에 영값이라 EpochKnown=false 다.
// 실패했다는 이유로 스냅샷을 비우면 30초짜리 유예가 사라져 순간 장애가 곧 주조 중단이 된다.
//
// 등급은 WARN 이다 — 프로세스는 살아 있고 방향은 비주조(안전)이며 다음 주기가 회복시킨다.
func (p *Poller) pollFailed(reason string, err error) {
	p.opt.Log.Warn("mtxstate_poll_failed",
		"reason", reason, "url", p.opt.APIURL, "err", err,
		"note", "직전 관측을 유지한다. 만료되면 ⓐ2 가 fail-closed 된다")
}

// newSnapshot 은 응답 항목을 조회용 형태로 접는다.
func newSnapshot(observedAt time.Time, items []apiPath) *snapshot {
	m := make(map[string]pathState, len(items))
	for _, it := range items {
		st := pathState{publishing: it.publishing()}
		if it.OnlineTime != nil {
			st.onlineTime = it.OnlineTime.UTC()
		}
		m[it.Name] = st
	}
	return &snapshot{observedAt: observedAt, items: m}
}

// maxBodyBytes 는 읽어 들일 응답 본문의 상한이다.
// 경로 100개 × 넉넉한 항목 크기를 덮으면서, 오구성으로 엉뚱한 서버를 가리켰을 때
// 메모리를 무한정 먹지 않게 한다.
const maxBodyBytes = 4 << 20

// fetch 는 목록을 한 번 읽어 파싱한다. 경계 검증은 전부 여기서 한다.
func (p *Poller) fetch(ctx context.Context) (pathList, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, p.opt.APIURL+pathsListPath, nil)
	if err != nil {
		return pathList{}, err
	}
	resp, err := p.client.Do(req)
	if err != nil {
		return pathList{}, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, maxBodyBytes))
	if err != nil {
		return pathList{}, err
	}
	if resp.StatusCode != http.StatusOK {
		// 인증 실패도 여기로 온다({"status":"error","error":"authentication error"}).
		// 본문을 진단에 싣되 길이를 자른다 — 통째로 실으면 로그가 응답으로 도배된다.
		return pathList{}, fmt.Errorf("HTTP %d: %s", resp.StatusCode, truncate(string(body), 200))
	}
	var list pathList
	if err := json.Unmarshal(body, &list); err != nil {
		return pathList{}, fmt.Errorf("응답 파싱 실패: %w", err)
	}
	return list, nil
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}

// pathList 는 GET /v3/paths/list 응답이다(F-34 실측 스키마).
type pathList struct {
	ItemCount int       `json:"itemCount"`
	PageCount int       `json:"pageCount"`
	Items     []apiPath `json:"items"`
}

// apiPath 는 항목 중 **우리가 쓰는 필드만** 담는다.
//
// ready/readyTime 은 실측상 값이 사실상 같지만 포크 소스에서 deprecated 태그라 읽지 않는다
// (폴백 갈래를 두지 않는다 — 두면 어느 쌍으로 판정했는지 로그로 구분되지 않는다).
type apiPath struct {
	Name       string     `json:"name"`
	Online     bool       `json:"online"`
	OnlineTime *time.Time `json:"onlineTime"`
	// Source 는 존재 여부만 본다. 타입까지 열면 새 소스 종류가 생길 때마다 여기가 바뀐다.
	Source json.RawMessage `json:"source"`
}

// publishing 은 설계가 정한 3항 결합이다 — 하나라도 빠지면 거짓이다.
func (a apiPath) publishing() bool {
	return a.Online && sourcePresent(a.Source)
}

// sourcePresent 는 source 가 실제 객체인지 본다.
// 필드 자체가 없으면 nil 이고, 있는데 비어 있으면 리터럴 null 이 온다 — 둘 다 부재다.
func sourcePresent(raw json.RawMessage) bool {
	trimmed := bytes.TrimSpace(raw)
	return len(trimmed) > 0 && !bytes.Equal(trimmed, []byte("null"))
}
