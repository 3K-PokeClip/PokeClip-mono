package main

import (
	"fmt"
	"strconv"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/ingestauth"
)

// IP 한도의 환경 변수 이름이다(트랙 B 설계 2-1 의 13). 셋 다 선택이다 — 비면 기본값(defaultIPLimit · 켜짐)이다. 값은
// 에러 · 로그에 싣지 않는다.
const (
	envIPLimit         = "INGEST_AUTH_IP_LIMIT"          // on · off
	envIPLimitBurst    = "INGEST_AUTH_IP_LIMIT_BURST"    // 정수
	envIPLimitInterval = "INGEST_AUTH_IP_LIMIT_INTERVAL" // Go duration
)

// defaultIPLimit 은 IP 한도의 기본값이다 — B = 30 은 동시 송출자 최대 30(한 NAT 뒤 재시작 물결)을 통과시키고, r = 3초는
// 플러그인 재시도 간격(5초)보다 짧다(설계 2-1 의 4 · 결정 119차 Q1). 라이브러리 영값은 꺼짐이라 켜는 것은 여기가 맡는다.
var defaultIPLimit = ingestauth.IPLimit{Burst: 30, Interval: 3 * time.Second}

// ipLimit 의 기동 로그 값이다.
const (
	ipLimitOn  = "on"
	ipLimitOff = "off"
)

// loadIPLimit 은 IP 한도 환경 변수를 읽는다. INGEST_AUTH_IP_LIMIT 은 on · off 의 닫힌 집합이고(비면 on), off 면 영값
// (꺼짐)이다. BURST · INTERVAL 은 비면 기본값이고, 꺼짐이어도 검사한다 — 오타가 다시 켤 때 처음 드러나지 않게. 틀린
// 값은 조용히 꺼지거나 기본값이 되지 않고 기동 실패다(LOG_LEVEL 과 같은 규칙).
func loadIPLimit(getenv func(string) string) (ingestauth.IPLimit, error) {
	limit := defaultIPLimit
	if raw := getenv(envIPLimitBurst); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < ingestauth.IPLimitBurstMin || n > ingestauth.IPLimitBurstMax {
			return ingestauth.IPLimit{}, fmt.Errorf("%s 는 %d–%d 의 정수여야 한다", envIPLimitBurst, ingestauth.IPLimitBurstMin, ingestauth.IPLimitBurstMax)
		}
		limit.Burst = n
	}
	if raw := getenv(envIPLimitInterval); raw != "" {
		d, err := time.ParseDuration(raw)
		if err != nil || d < ingestauth.IPLimitIntervalMin || d > ingestauth.IPLimitIntervalMax {
			return ingestauth.IPLimit{}, fmt.Errorf("%s 는 %v–%v 의 Go duration(예: 3s)이어야 한다", envIPLimitInterval, ingestauth.IPLimitIntervalMin, ingestauth.IPLimitIntervalMax)
		}
		limit.Interval = d
	}
	switch getenv(envIPLimit) {
	case "", ipLimitOn:
		return limit, nil
	case ipLimitOff:
		return ingestauth.IPLimit{}, nil
	}
	return ingestauth.IPLimit{}, fmt.Errorf("%s 는 %s · %s 가운데 하나여야 한다", envIPLimit, ipLimitOn, ipLimitOff)
}

// ipLimitState 는 기동 로그의 ip_limit 값이다.
func ipLimitState(l ingestauth.IPLimit) string {
	if l == (ingestauth.IPLimit{}) {
		return ipLimitOff
	}
	return ipLimitOn
}
