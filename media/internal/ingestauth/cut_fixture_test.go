package ingestauth

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"maps"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxctl"
)

// srtConnStatFields 는 APISRTConn 의 통계 필드 이름이다(포크 v1.21.1-pokeclip.2 internal/defs/api_srt.go 38–157행
// 그대로). 가짜 MediaMTX 의 항목은 이 필드까지 모두 담는다 — 실제 항목의 61필드를 그대로 흉내 낸다.
var srtConnStatFields = []string{
	"packetsSent", "packetsReceived", "packetsSentUnique", "packetsReceivedUnique", "packetsSendLoss",
	"packetsReceivedLoss", "packetsRetrans", "packetsReceivedRetrans", "packetsSentACK", "packetsReceivedACK",
	"packetsSentNAK", "packetsReceivedNAK", "packetsSentKM", "packetsReceivedKM", "usSndDuration",
	"packetsReceivedBelated", "packetsSendDrop", "packetsReceivedDrop", "packetsReceivedUndecrypt", "bytesReceived",
	"bytesSent", "bytesSentUnique", "bytesReceivedUnique", "bytesReceivedLoss", "bytesRetrans",
	"bytesReceivedRetrans", "bytesReceivedBelated", "bytesSendDrop", "bytesReceivedDrop", "bytesReceivedUndecrypt",
	"usPacketsSendPeriod", "packetsFlowWindow", "packetsFlightSize", "msRTT", "mbpsSendRate", "mbpsReceiveRate",
	"mbpsLinkCapacity", "bytesAvailSendBuf", "bytesAvailReceiveBuf", "mbpsMaxBW", "byteMSS", "packetsSendBuf",
	"bytesSendBuf", "msSendBuf", "msSendTsbPdDelay", "packetsReceiveBuf", "bytesReceiveBuf", "msReceiveBuf",
	"msReceiveTsbPdDelay", "packetsReorderTolerance", "packetsReceivedAvgBelatedTime", "packetsSendLossRate",
	"packetsReceivedLossRate", "outboundFramesDiscarded",
}

// 가짜 MediaMTX 연결의 state 다(defs/api_srt.go 21–23행).
const (
	stateIdle    = "idle"
	stateRead    = "read"
	statePublish = "publish"
)

// mtxUUIDPattern 은 원본이 uuid.Parse 로 받는 id 가운데 이 가짜가 받는 꼴이다(소문자 정규형). 아니면 400 이다.
var mtxUUIDPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// fakeConn 은 가짜 MediaMTX 의 SRT 연결 하나다.
type fakeConn struct {
	id       string
	created  time.Time
	state    string // idle · read · publish
	path     string // 등록 전에는 빈 값
	token    string // 허용 응답을 받아 수락된 토큰 — 수락되지 않았으면 빈 값
	accepted bool
	gone     chan struct{} // kick 되거나 닫히면 닫힌다
}

// fakeMediaMTX 는 MediaMTX Control API 의 SRT 연결 부분을 흉내 내는 가짜다(httptest — Cut 은 실제 mtxctl.Client 로
// 부른다, 설계 2-9 인터페이스 0). 원본(v1.21.1-pokeclip.2)의 꼴을 따른다.
//   - 연결은 생길 때부터 목록에 idle · path 빈 값으로 있다(srt/conn.go 72–77행). 등록되면 state 와 path 가 함께
//     바뀐다(254–256행).
//   - 목록은 한 순간의 연결 집합을 created 순으로 낸다(srt/server.go 208–221행). 페이지는 api/paginate.go 그대로다 —
//     기본 100 · itemsPerPage 31비트 · 빈 목록은 pageCount 0.
//   - kick 은 목록에서 곧바로 지운다(server.go 232–241행). 없는 id 는 404, uuid 꼴이 아니면 400 이다.
//   - kick 된 연결은 그 뒤 허용 응답을 받아도 수락되지 않는다 — Accept 직후 ctx 종료로 닫힌다(conn.go 179–198행).
//   - Basic 자격은 authorize 가 판정하고, 아니면 401 · WWW-Authenticate 다(api.go 316–341행 — 원본은 응답기의 api
//     행에 묻는다).
//   - 에러는 원본의 JSON 꼴이다(api.go 284–300행). 없는 경로는 자격 판정 뒤 gin 기본 404(text/plain — gin.go 759 ·
//     764–779)다. 시험은 갈래의 경로를 없앨 수 있다(missing — 판 올림).
//
// 모형에 없는 것: SRT 소켓 · 핸드셰이크, kick 시점에 등록 직전이던 연결의 순간 등록(설계 3-4 2.7), 인증 실패 지연.
type fakeMediaMTX struct {
	srv *httptest.Server

	mu        sync.Mutex
	authorize func(user, pass string) bool
	conns     map[string]*fakeConn
	kicked    map[string]bool // kick 된 id — 그 뒤 수락을 막는다
	kicks     []string        // 200 으로 끝난 kick 의 id(차례대로)
	requests  []string        // 받은 요청 "<메서드> <경로>"(차례대로)
	lists     int             // 받은 목록 요청 수
	nextID    int
	fail      map[string]int  // 갈래(list · get · kick) → 그 상태로 답한다
	failKick  map[string]int  // id → 그 id 의 kick 만 그 상태로 답한다
	missing   map[string]bool // 없앤 갈래(list · get · kick) — 그 경로가 사라진 것처럼 gin 기본 404 로 답한다
	sticky    bool            // kick 이 200 을 내지만 연결을 지우지 않는다(확인 시한 시험)
	onList    func(call int)  // 목록을 만든 뒤 답하기 전에 부른다(잠금 밖 · 서버 고루틴)
	onGet     func(id string) // 조회 요청을 받으면 답하기 전에 부른다(잠금 밖 · 서버 고루틴)
}

// newFakeMediaMTX 는 시험 자격(testAPIUser · testAPIPass)을 받는 가짜 MediaMTX 를 띄운다.
func newFakeMediaMTX(t *testing.T) *fakeMediaMTX {
	t.Helper()
	m := &fakeMediaMTX{
		authorize: func(user, pass string) bool { return user == testAPIUser && pass == testAPIPass },
		conns:     make(map[string]*fakeConn),
		kicked:    make(map[string]bool),
		fail:      make(map[string]int),
		failKick:  make(map[string]int),
		missing:   make(map[string]bool),
		nextID:    1 << 20, // 시험이 testConnID 로 직접 만드는 id 와 겹치지 않게
	}
	m.srv = httptest.NewServer(http.HandlerFunc(m.serve))
	t.Cleanup(m.srv.Close)
	return m
}

// serve 는 Control API 요청 하나에 답한다 — 자격 → 기록 → 조회 고리 → 없앤 경로 → 주입한 실패 → 경로 차례다.
func (m *fakeMediaMTX) serve(w http.ResponseWriter, r *http.Request) {
	m.mu.Lock()
	authorize, onGet := m.authorize, m.onGet
	m.requests = append(m.requests, r.Method+" "+r.URL.Path)
	m.mu.Unlock()
	user, pass, _ := r.BasicAuth()
	if !authorize(user, pass) {
		w.Header().Set("WWW-Authenticate", `Basic realm="mediamtx"`)
		writeMTXError(w, http.StatusUnauthorized, "authentication error")
		return
	}
	route, id, _ := strings.Cut(strings.TrimPrefix(r.URL.Path, "/v3/srtconns/"), "/")
	if route == "get" && onGet != nil {
		onGet(id)
	}
	if m.routeMissing(route) {
		writeRouteNotFound(w)
		return
	}
	if status, ok := m.injected(route, id); ok {
		writeMTXError(w, status, "injected failure")
		return
	}
	switch {
	case r.Method == http.MethodGet && route == "list" && id == "":
		m.list(w, r)
	case r.Method == http.MethodGet && route == "get":
		m.get(w, id)
	case r.Method == http.MethodPost && route == "kick":
		m.kick(w, id)
	default:
		writeRouteNotFound(w)
	}
}

// routeMissing 은 시험이 그 갈래의 경로를 없앴는지 본다.
func (m *fakeMediaMTX) routeMissing(route string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.missing[route]
}

// injected 는 시험이 그 갈래(와 그 id)에 넣은 실패 상태다.
func (m *fakeMediaMTX) injected(route, id string) (status int, ok bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if route == "kick" {
		if status, ok := m.failKick[id]; ok {
			return status, true
		}
	}
	status, ok = m.fail[route]
	return status, ok
}

// writeMTXError 는 원본의 에러 응답(api.go 284–300행 — {"status":"error","error":…})을 쓴다.
func writeMTXError(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "error", "error": msg})
}

// writeRouteNotFound 는 없는 경로 · 메서드에 gin 이 내는 기본 404(gin.go 33 · 759 · 764–779 — text/plain)를 쓴다.
func writeRouteNotFound(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "text/plain")
	w.WriteHeader(http.StatusNotFound)
	_, _ = io.WriteString(w, "404 page not found")
}

// writeMTXJSON 은 200 과 v 의 JSON 을 쓴다.
func writeMTXJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(v)
}

// list 는 목록을 한 순간의 스냅숏으로 만들어 created 순으로 정렬하고 원본처럼 페이지를 나눠 낸다.
func (m *fakeMediaMTX) list(w http.ResponseWriter, r *http.Request) {
	m.mu.Lock()
	items := make([]map[string]any, 0, len(m.conns))
	for _, c := range slices.SortedFunc(maps.Values(m.conns), func(a, b *fakeConn) int { return a.created.Compare(b.created) }) {
		items = append(items, c.item())
	}
	m.lists++
	call, onList := m.lists, m.onList
	m.mu.Unlock()

	total := len(items)
	page, pageCount, err := paginate(items, r.URL.Query().Get("itemsPerPage"), r.URL.Query().Get("page"))
	if err != nil {
		writeMTXError(w, http.StatusBadRequest, err.Error())
		return
	}
	if onList != nil {
		onList(call)
	}
	writeMTXJSON(w, map[string]any{"itemCount": total, "pageCount": pageCount, "items": page})
}

// paginate 는 원본 api/paginate.go 9–57행 그대로다 — itemsPerPage 기본 100 · 31비트 · 0 거부, page 기본 0.
func paginate(items []map[string]any, itemsPerPageStr, pageStr string) (page []map[string]any, pageCount int, err error) {
	itemsPerPage := 100
	if itemsPerPageStr != "" {
		v, err := strconv.ParseUint(itemsPerPageStr, 10, 31)
		if err != nil {
			return nil, 0, err
		}
		if v == 0 {
			return nil, 0, errors.New("invalid items per page")
		}
		itemsPerPage = int(v)
	}
	pageNum := 0
	if pageStr != "" {
		v, err := strconv.ParseUint(pageStr, 10, 31)
		if err != nil {
			return nil, 0, err
		}
		pageNum = int(v)
	}
	if len(items) == 0 {
		return items, 0, nil
	}
	pageCount = len(items) / itemsPerPage
	if len(items)%itemsPerPage != 0 {
		pageCount++
	}
	lo := min(pageNum*itemsPerPage, len(items))
	hi := min((pageNum+1)*itemsPerPage, len(items))
	return items[lo:hi], pageCount, nil
}

// get 은 GET /v3/srtconns/get/{id} 다.
func (m *fakeMediaMTX) get(w http.ResponseWriter, id string) {
	if !mtxUUIDPattern.MatchString(id) {
		writeMTXError(w, http.StatusBadRequest, "invalid UUID length")
		return
	}
	m.mu.Lock()
	c, ok := m.conns[id]
	var item map[string]any
	if ok {
		item = c.item()
	}
	m.mu.Unlock()
	if !ok {
		writeMTXError(w, http.StatusNotFound, "connection not found")
		return
	}
	writeMTXJSON(w, item)
}

// kick 은 POST /v3/srtconns/kick/{id} 다 — 목록에서 곧바로 지우고, 그 연결의 수락을 막는다.
func (m *fakeMediaMTX) kick(w http.ResponseWriter, id string) {
	if !mtxUUIDPattern.MatchString(id) {
		writeMTXError(w, http.StatusBadRequest, "invalid UUID length")
		return
	}
	m.mu.Lock()
	c, ok := m.conns[id]
	if ok {
		m.kicks = append(m.kicks, id)
		if !m.sticky {
			m.removeLocked(c)
			m.kicked[id] = true
		}
	}
	m.mu.Unlock()
	if !ok {
		writeMTXError(w, http.StatusNotFound, "connection not found")
		return
	}
	writeMTXJSON(w, map[string]string{"status": "ok"})
}

// removeLocked 는 c 를 목록에서 지우고 gone 을 닫는다. m.mu 를 잡은 채로 부른다.
func (m *fakeMediaMTX) removeLocked(c *fakeConn) {
	delete(m.conns, c.id)
	close(c.gone)
}

// item 은 c 의 Control API 항목(APISRTConn 61필드)이다. created 는 원본처럼 RFC 3339(나노초)다.
func (c *fakeConn) item() map[string]any {
	item := map[string]any{
		"id":         c.id,
		"created":    c.created.Format(time.RFC3339Nano),
		"remoteAddr": testPublisherIP + ":40000",
		"state":      c.state,
		"path":       c.path,
		"query":      "",
		"user":       "",
	}
	for _, name := range srtConnStatFields {
		item[name] = 0
	}
	return item
}

// add 는 시험이 정한 연결을 목록에 둔다. state 가 비면 idle 이다.
func (m *fakeMediaMTX) add(c fakeConn) {
	if c.state == "" {
		c.state = stateIdle
	}
	c.gone = make(chan struct{})
	m.mu.Lock()
	defer m.mu.Unlock()
	m.conns[c.id] = &c
}

// open 은 새 연결을 만든다 — 생길 때부터 목록에 idle · path 빈 값으로 있다. 연결 id 와 gone 을 돌려준다.
func (m *fakeMediaMTX) open(created time.Time) (id string, gone <-chan struct{}) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.nextID++
	c := &fakeConn{id: testConnID(m.nextID), created: created, state: stateIdle, gone: make(chan struct{})}
	m.conns[c.id] = c
	return c.id, c.gone
}

// accept 는 처리기의 허용 응답을 받은 연결을 token 으로 수락한다. kick 됐거나 이미 없으면 거짓이다.
func (m *fakeMediaMTX) accept(id, token string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	c, ok := m.conns[id]
	if !ok || m.kicked[id] {
		return false
	}
	c.accepted, c.token = true, token
	return true
}

// register 는 수락된 연결을 그 토큰의 경로에 등록한다 — state 와 path 를 함께 바꾼다. 이미 없으면 거짓이다.
func (m *fakeMediaMTX) register(id string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	c, ok := m.conns[id]
	if !ok || !c.accepted {
		return false
	}
	c.state, c.path = statePublish, c.token
	return true
}

// close 는 연결을 닫아 목록에서 지운다(인증 거부 · 송출자의 끊음). 이미 없으면 아무것도 하지 않는다.
func (m *fakeMediaMTX) close(id string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if c, ok := m.conns[id]; ok {
		m.removeLocked(c)
	}
}

// acceptedLive 는 token 으로 수락돼 아직 목록에 있는 연결의 id 다(바이트 순).
func (m *fakeMediaMTX) acceptedLive(token string) []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	var ids []string
	for id, c := range m.conns {
		if c.accepted && c.token == token {
			ids = append(ids, id)
		}
	}
	slices.Sort(ids)
	return ids
}

// live 는 목록에 있는 연결의 id 다(바이트 순).
func (m *fakeMediaMTX) live() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return slices.Sorted(maps.Keys(m.conns))
}

// kicked200 은 200 으로 끝난 kick 의 id 다(차례대로).
func (m *fakeMediaMTX) kicked200() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return slices.Clone(m.kicks)
}

// requestLog 는 받은 요청 "<메서드> <경로>" 다(차례대로).
func (m *fakeMediaMTX) requestLog() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return slices.Clone(m.requests)
}

// countRequests 는 받은 요청 가운데 "<메서드> <경로>" 가 prefix 로 시작하는 것의 수다.
func (m *fakeMediaMTX) countRequests(prefix string) int {
	n := 0
	for _, r := range m.requestLog() {
		if strings.HasPrefix(r, prefix) {
			n++
		}
	}
	return n
}

// set 은 m.mu 를 잡고 edit 을 부른다 — 시험이 실패 주입 · 고리를 바꾸는 자리다.
func (m *fakeMediaMTX) set(edit func(m *fakeMediaMTX)) {
	m.mu.Lock()
	defer m.mu.Unlock()
	edit(m)
}

// newTestMTXClient 는 apiURL 의 Control API 를 시험 자격으로 부르는 실제 mtxctl.Client 다.
func newTestMTXClient(t *testing.T, apiURL string) *mtxctl.Client {
	t.Helper()
	c, err := mtxctl.NewClient(mtxctl.Options{APIURL: apiURL, User: testAPIUser, Pass: mtxctl.NewSecret(testAPIPass)})
	if err != nil {
		t.Fatalf("Setup: mtxctl.NewClient(APIURL=%q) returned error: %v", apiURL, err)
	}
	return c
}

// cutFixture 는 Cut 시험의 재료다 — 임시 거부 목록(기본으로 testTokenA 한 줄) · 시험 시계로 찍는 허용 연결 기록 ·
// 가짜 MediaMTX · 그것을 부르는 실제 mtxctl.Client. 시계는 2026-10-12 09:00:00 UTC 에서 시작하고 시험이 옮긴다.
type cutFixture struct {
	clock    *manualClock
	denyPath string
	connDir  string
	conns    *ConnLog
	mtx      *fakeMediaMTX
	client   *mtxctl.Client
	between  func() // 기록 판독의 두 세대 읽기 사이 고리(cutter.between)
}

// newCutFixture 는 cutFixture 를 만든다. 기록은 maxBytes 로 열고, 연 때(시계 시작 시각)에 시작 표시가 찍힌다.
func newCutFixture(t *testing.T, maxBytes int64) *cutFixture {
	t.Helper()
	clock := newManualClock()
	connDir := t.TempDir()
	conns, err := OpenConnLog(connDir, maxBytes, clock.now)
	if err != nil {
		t.Fatalf("Setup: OpenConnLog returned error: %v", err)
	}
	conns.log = discardLogger()
	t.Cleanup(func() { closeConnLog(conns) })
	denyPath := filepath.Join(t.TempDir(), "deny-tokens")
	writeFile(t, denyPath, testTokenA+"\n")
	m := newFakeMediaMTX(t)
	return &cutFixture{clock: clock, denyPath: denyPath, connDir: connDir, conns: conns, mtx: m, client: newTestMTXClient(t, m.srv.URL)}
}

// at 은 시계 시작에서 offset 만큼 떨어진 시각이다.
func (f *cutFixture) at(offset time.Duration) time.Time {
	return f.clock.start.Add(offset)
}

// allow 는 token · connID 의 줄을 기록에 덧붙인다(시계의 지금이 at). 고루틴 안에서도 부르므로 에러를 돌려준다.
func (f *cutFixture) allow(token, connID string) error {
	if err := f.conns.Append(token, connID); err != nil {
		return fmt.Errorf("Append(%s, %s): %v", token, connID, err)
	}
	return nil
}

// mustAllow 는 allow 가 실패하면 시험을 멈춘다(시험 고루틴에서만 부른다).
func (f *cutFixture) mustAllow(t *testing.T, token, connID string) {
	t.Helper()
	if err := f.allow(token, connID); err != nil {
		t.Fatalf("Setup: %v", err)
	}
}

// appendRawConnLine 은 Append 의 꼴 검사를 거치지 않고 현재 세대 끝에 l 을 덧붙인다 — 기록을 손으로 고친 줄이다.
func appendRawConnLine(t *testing.T, f *cutFixture, l connLine) {
	t.Helper()
	line, err := encodeConnLine(l)
	if err != nil {
		t.Fatalf("Setup: %v", err)
	}
	file, err := os.OpenFile(filepath.Join(f.connDir, connLogName), os.O_WRONLY|os.O_APPEND, 0)
	if err != nil {
		t.Fatalf("Setup: opening the current generation returned error: %v", err)
	}
	_, werr := file.Write(line)
	if err := errors.Join(werr, file.Close()); err != nil {
		t.Fatalf("Setup: appending a raw line returned error: %v", err)
	}
}

// cutVerifyWithinInTests 는 시험의 확인 시한이다 — 확인 시한 시험이 실제로 2초를 기다리지 않게 줄인다. 가짜는 kick 한
// 연결을 곧바로 지우므로 정상 갈래는 첫 조회에서 끝난다.
const cutVerifyWithinInTests = 200 * time.Millisecond

// cutter 는 시험 시계 · 짧은 확인 시한 · 판독 사이 고리를 쓰는 Cut 본문이다.
func (f *cutFixture) cutter() cutter {
	return cutter{now: f.clock.now, verifyWithin: cutVerifyWithinInTests, verifyEvery: 5 * time.Millisecond, between: f.between}
}

// cut 은 token 을 f 의 재료로 끊는다. 거부 목록은 cut 프로세스처럼 새 DenyList 로 읽는다.
func (f *cutFixture) cut(t *testing.T, token string) (CutReport, error) {
	t.Helper()
	return f.cutter().cut(t.Context(), token, NewDenyList(f.denyPath, discardLogger()), f.connDir, f.client)
}

// cutFailureKinds 는 Cut 의 에러가 묶은 까닭마다의 갈래 이름이다(바이트 순) — Control API 실패는 「<Op> status <상태>」
// · 「<Op> <Kind>」, ctx 끝은 「ctx canceled」 · 「ctx deadline」, 미완의 센티널은 그 이름, 그 밖은 「unknown」. 에러
// 문구를 싣지 않으므로 실패 문구에 그대로 써도 된다. cut 하위 명령처럼 errors.Join 의 첫 층만 펼친다.
func cutFailureKinds(err error) []string {
	if err == nil {
		return nil
	}
	parts := []error{err}
	if joined, ok := err.(interface{ Unwrap() []error }); ok {
		parts = joined.Unwrap()
	}
	var kinds []string
	for _, part := range parts {
		var apiErr *mtxctl.Error
		kind := "unknown"
		switch {
		case errors.As(part, &apiErr) && apiErr.Kind == "status":
			kind = fmt.Sprintf("%s status %d", apiErr.Op, apiErr.Status)
		case errors.As(part, &apiErr):
			kind = apiErr.Op + " " + apiErr.Kind
		case errors.Is(part, context.Canceled):
			kind = "ctx canceled"
		case errors.Is(part, context.DeadlineExceeded):
			kind = "ctx deadline"
		case errors.Is(part, ErrConnLogUnreadable):
			kind = "connlog unreadable"
		case errors.Is(part, ErrConnLogWindowShort):
			kind = "connlog window short"
		case errors.Is(part, ErrVerifyTimeout):
			kind = "verify timeout"
		}
		kinds = append(kinds, kind)
	}
	slices.Sort(kinds)
	return kinds
}

// checkReport 는 got 이 want 와 같은지 본다. Remaining 은 차례와 무관하게 견준다.
func checkReport(t *testing.T, got, want CutReport) {
	t.Helper()
	g, w := got, want
	g.Remaining, w.Remaining = slices.Sorted(slices.Values(got.Remaining)), slices.Sorted(slices.Values(want.Remaining))
	if g.FromLog != w.FromLog || g.FromList != w.FromList || g.FromUnmapped != w.FromUnmapped ||
		g.Kicked != w.Kicked || g.AlreadyGone != w.AlreadyGone || g.UnmappedYoung != w.UnmappedYoung ||
		g.Covered != w.Covered || !slices.Equal(g.Remaining, w.Remaining) {
		t.Errorf("CutReport = %+v, want %+v", got, want)
	}
}
