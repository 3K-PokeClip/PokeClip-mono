package config

// 계약9 발행기 설정(POK-196 M6-8 ④ · 설계 M6 15.5 「발행 켜기 게이트」 · 15.7 — BROADCAST_TOPIC_ARN ·
// LIFECYCLE_PUBLISH_STREAMS)의 시험이다. 빈 주제가 발행을 끄는 롤백 스위치라 기본값이 없다.

import (
	"slices"
	"strings"
	"testing"
)

func TestLoadLifecycleDefaultsOff(t *testing.T) {
	cfg, err := Load(envOf(minimalEnv()))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if cfg.Lifecycle.TopicARN != "" || cfg.Lifecycle.TopicRegion != "" || len(cfg.Lifecycle.Streams) != 0 {
		t.Errorf("Lifecycle = %+v, want 꺼짐(빈 주제 · 빈 목록)", cfg.Lifecycle)
	}
}

// 주제의 지역이 SNS 클라이언트의 지역이고, 허용 목록은 쉼표로 가르며 항목 앞뒤 공백 · 빈 항목을 버린다.
func TestLoadLifecycleValues(t *testing.T) {
	env := minimalEnv()
	env["BROADCAST_TOPIC_ARN"] = " arn:aws:sns:ap-northeast-2:123456789012:pokeclip-prod-broadcast-lifecycle.fifo "
	env["LIFECYCLE_PUBLISH_STREAMS"] = " 01HZX3K9QW8E7RMNPA5BCDTFGV , ,* "
	cfg, err := Load(envOf(env))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if cfg.Lifecycle.TopicARN != "arn:aws:sns:ap-northeast-2:123456789012:pokeclip-prod-broadcast-lifecycle.fifo" ||
		cfg.Lifecycle.TopicRegion != "ap-northeast-2" {
		t.Errorf("주제 = (%q, %q)", cfg.Lifecycle.TopicARN, cfg.Lifecycle.TopicRegion)
	}
	if want := []string{"01HZX3K9QW8E7RMNPA5BCDTFGV", "*"}; !slices.Equal(cfg.Lifecycle.Streams, want) {
		t.Errorf("허용 목록 = %q, want %q", cfg.Lifecycle.Streams, want)
	}
}

// 주제 ARN 은 SNS FIFO 주제 꼴이어야 한다 — 오타를 켜는 날 Publish 오류로 알지 않게 기동 때 거부한다. 에러는 env 이름을
// 싣는다.
func TestLoadLifecycleRejectsMalformedTopic(t *testing.T) {
	for _, arn := range []string{
		"pokeclip-prod-broadcast-lifecycle.fifo",
		"arn:aws:sqs:ap-northeast-2:123456789012:pokeclip.fifo",
		"arn:aws:sns::123456789012:pokeclip.fifo",
		"arn:aws:sns:ap-northeast-2:1234:pokeclip.fifo",
		"arn:aws:sns:ap-northeast-2:123456789012:pokeclip",
		"arn:aws:sns:ap-northeast-2:123456789012:",
	} {
		env := minimalEnv()
		env["BROADCAST_TOPIC_ARN"] = arn
		_, err := Load(envOf(env))
		if err == nil || !strings.Contains(err.Error(), "BROADCAST_TOPIC_ARN") {
			t.Errorf("%q → 에러 %v, want BROADCAST_TOPIC_ARN 거부", arn, err)
		}
	}
}
