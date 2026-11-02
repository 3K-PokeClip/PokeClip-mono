package config

import (
	"errors"
	"regexp"
	"strings"

	"github.com/aws/aws-sdk-go-v2/aws/arn"
)

// LifecycleOptions 는 계약9 발행기(POK-196 M6-8)의 설정이다(설계 M6 15.5 「발행 켜기 게이트」 · 15.7).
type LifecycleOptions struct {
	// TopicARN 은 BROADCAST_TOPIC_ARN 이다 — 계약9 SNS FIFO 주제. 빈 값이면 발행기를 띄우지 않고 outbox 문장도 보내지
	// 않는다(롤백 스위치 — withDefault 를 쓰지 않는다).
	TopicARN string
	// TopicRegion 은 주제 ARN 의 지역이다 — SNS 클라이언트가 이 지역으로 붙는다(env 를 따로 두지 않는다).
	TopicRegion string
	// Streams 는 LIFECYCLE_PUBLISH_STREAMS 를 쉼표로 가른 허용 목록이다(앞뒤 공백 · 빈 항목은 버림). 항목은 stream_id
	// 이고 "*" 는 전부다. 비면 발행을 켜지 않는다 — 켜는 순간 옛 회차가 한꺼번에 나가지 않게 하는 드라이런 손잡이다.
	Streams []string
}

// accountIDPattern 은 AWS 계정 번호(12자리 숫자)다.
var accountIDPattern = regexp.MustCompile(`^[0-9]{12}$`)

// loadLifecycle 은 발행기 설정 둘을 읽고 주제 ARN 꼴을 본다 — SNS 주제(arn:<파티션>:sns:<지역>:<12자리 계정>:<이름>.fifo)
// 여야 한다. 꼴이 틀리면 기동 거부다(오타를 켜는 날 Publish 오류로 알지 않게). 주제가 비어도 목록은 읽는다.
func loadLifecycle(env func(string) string) (LifecycleOptions, error) {
	o := LifecycleOptions{TopicARN: strings.TrimSpace(env("BROADCAST_TOPIC_ARN"))}
	for _, s := range strings.Split(env("LIFECYCLE_PUBLISH_STREAMS"), ",") {
		if s = strings.TrimSpace(s); s != "" {
			o.Streams = append(o.Streams, s)
		}
	}
	if o.TopicARN == "" {
		return o, nil
	}
	a, err := arn.Parse(o.TopicARN)
	if err != nil || a.Service != "sns" || a.Region == "" || !accountIDPattern.MatchString(a.AccountID) ||
		len(a.Resource) <= len(".fifo") || !strings.HasSuffix(a.Resource, ".fifo") {
		return LifecycleOptions{}, errors.New("BROADCAST_TOPIC_ARN 은 SNS FIFO 주제 ARN(arn:aws:sns:<지역>:<12자리 계정>:<이름>.fifo)이어야 한다")
	}
	o.TopicRegion = a.Region
	return o, nil
}
