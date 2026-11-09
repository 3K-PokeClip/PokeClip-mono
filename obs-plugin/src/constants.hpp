/*
PokeClip for OBS
Copyright (C) 2026 PokeClip

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.
*/
#pragma once

#include <string_view>

namespace pokeclip {

// 독 id — OBS가 독 위치를 이 문자열로 저장한다. 바꾸면 사용자 배치가 초기화된다.
inline constexpr const char *kDockId = "pokeclip-dock";

// 서버 환경 — 운영(prod)·개발(dev) 두 짝. 스트림 키는 환경별로 발급되므로 Auth(페어링 교환·마크)와 SRT 수신은
// 언제나 같은 환경을 가리킨다(POK-267). 지금 환경은 설정 파일 server_env가 기억하고(server-env.hpp), 빌드는 그
// 키가 없을 때의 기본 환경만 정한다 — 배포 빌드(CI 템플릿 경로)는 운영, 개발 빌드(macos-local, POKECLIP_DEV_SERVERS)는
// 개발. 운영 이름은 엣지 8단계(POK-300)에서 열린다 — 그 전에는 독 숨은 스위치나 server_env로 개발로 바꿔 시험한다.
enum class ServerEnv { Prod, Dev };

struct ServerDefaults {
	const char *name;       // 설정 파일 server_env 값
	const char *apiBase;    // 페어링 교환·마크 API. https만 — 마크가 스트림 키 파생 값을 Bearer로 싣는다
	const char *ingestHost; // SRT 수신부
	int ingestPort;
};

// 운영 api = 1번 Jira 댓글(2026-10-13, POK-267) 확정값. 운영 SRT 수신 = ingest.pokeclip.com(NLB, ADR-020·021·
// POK-300) — media.pokeclip.com은 CloudFront라 SRT(UDP)가 닿지 않는다. 포트 8890 = 운영 수신 모듈 srt_port 기본값
// (prod 덮어쓰기 없음).
inline constexpr ServerDefaults kProdServers{"prod", "https://api.pokeclip.com", "ingest.pokeclip.com", 8890};
// 개발 = dev 서버(HTTPS — HTTP는 301로 돌려보내 POST가 닿지 않는다, 2026-09-30 확인) + dev-media(ADR-040, 팀 IP 한정).
inline constexpr ServerDefaults kDevServers{"dev", "https://dev.pokeclip.com", "media-dev.pokeclip.com", 8890};

#ifdef POKECLIP_DEV_SERVERS
inline constexpr ServerEnv kBuildServerEnv = ServerEnv::Dev; // 개발 빌드 전용(macos-local 프리셋)
#else
inline constexpr ServerEnv kBuildServerEnv = ServerEnv::Prod;
#endif

// 예전 기본값 — 저장된 설정에 이 값이 있으면 읽을 때 지금 환경의 값으로 올린다(손대지 않은 기본값으로 본다).
inline constexpr const char *kLegacyDefaultApiBase = "http://dev.pokeclip.com";
// POK-267 이전 기본 수신 — dev 키와 운영 수신이 섞인 짝이었다. 지금 운영 값과 이름이 같다(InferServerEnv).
inline constexpr const char *kLegacyDefaultIngestHost = "ingest.pokeclip.com";
inline constexpr int kLegacyDefaultIngestPort = 8890;
// 2026-10-14부터 0.2.1 전까지의 운영 짝 수신 — CloudFront라 SRT가 닿지 않으므로 직접 적은 값으로 보지 않는다.
inline constexpr const char *kLegacyProdIngestHost = "media.pokeclip.com";

static_assert(std::string_view(kProdServers.apiBase).substr(0, 8) == "https://", "운영 api는 https여야 한다");
static_assert(std::string_view(kDevServers.apiBase).substr(0, 8) == "https://", "개발 api는 https여야 한다");
static_assert(std::string_view(kProdServers.ingestHost).find(':') == std::string_view::npos,
	      "수신 호스트에 포트를 붙이지 않는다(IsSafeIngestHost)");
static_assert(std::string_view(kDevServers.ingestHost).find(':') == std::string_view::npos,
	      "수신 호스트에 포트를 붙이지 않는다(IsSafeIngestHost)");

inline constexpr const char *kPairingExchangePath = "/api/stream-keys/pairing-codes/exchange";

// SRT 지연 허용치(latency, ms) — 독·브리지가 받는 범위와 기본값. 읽을 때의 이관 규칙은 srt-latency.hpp.
inline constexpr int kMinLatencyMs = 20;
inline constexpr int kMaxLatencyMs = 8000;
// 3000 = 송출 쪽 망이 1.5초 막혀도 재전송이 제때 와 녹화가 깨지지 않는 값(2026-11-08–09 실측, POK-556) — 대가는
// 평소 라이브 지연 +2초. 수신부(gosrt)는 송출·수신 latency 중 큰 값으로 합의하므로 서버는 그대로다.
inline constexpr int kDefaultLatencyMs = 3000;
// POK-556 이전 기본값 — 1초 넘는 막힘이면 늦은 패킷을 수신부가 버렸다. 이관 표지 없는 파일의 이 값은 지금 기본값으로 올린다.
inline constexpr int kLegacyDefaultLatencyMs = 1000;
// 설정 파일의 이관 표지 키 — Save가 늘 쓰고, Load는 이 키가 있으면 저장된 1000을 고른 값으로 본다.
inline constexpr const char *kLatencyMigratedKey = "latency_migrated";

// ADR-020: 세그먼트 4s가 GOP의 정수배여야 한다 — 플러그인이 2s로 강제한다.
inline constexpr int kForcedKeyintSec = 2;

// libobs는 "stop" 신호를 먼저 보내고 인코더를 멈춘 스레드가 나중에 active를 내린다(obs-output.c
// end_data_capture). 출력 해제는 그 뒤에 해야 하므로 이 간격으로 확인한다 — 최대 간격 × 횟수.
inline constexpr int kReleasePollMs = 100;
inline constexpr int kReleasePollAttempts = 50;

// 재연결 횟수·간격은 여기 없다 — libobs 재연결을 끄고 플러그인이 정책 표(retry-policy.hpp)로 다시 시도한다.

// SRT 접속 중인 출력을 해제하기 전에 접속 결과를 기다리는 상한·간격. SRT 접속 타임아웃(OBS mpegts는 따로 안 주므로
// libsrt 기본 3초)보다 넉넉히 둔다 — 넘기면 force_stop이 접속 스레드를 기다린다.
inline constexpr int kConnectResultWaitMs = 10000;
inline constexpr int kConnectResultPollMs = 10;

// 브라우저 독 페이지가 이 시간 안에 /api/hello를 부르지 않으면 Qt 폴백으로 바꾼다.
inline constexpr int kBrowserWatchdogMs = 15000;

// A2 멀티오디오(ADR-017): 트랙 1(최종 믹스)은 본방 오디오 인코더를 공유하고(방송 트랙이 2~6이어도 그 믹서),
// 트랙 2~6(믹서 1~5)은 플러그인이 AAC 인코더를 만든다. 128 kbps는 2026-08-03 6트랙 실측값 — ADR-020 오디오 칸은
// 1번 비준 대상.
inline constexpr int kAudioTrackCount = 6;
inline constexpr int kStemAudioBitrateKbps = 128;
inline constexpr int kFallbackTrack0BitrateKbps = 160; // 본방 오디오가 AAC가 아니고 비트레이트도 모를 때 우리 트랙 1
inline constexpr const char *kFallbackAacEncoderId = "ffmpeg_aac";
inline constexpr const char *kAudioEncoderNamePrefix = "pokeclip-audio-";

} // namespace pokeclip
