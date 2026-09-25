/*
PokeClip for OBS
Copyright (C) 2026 PokeClip

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.
*/
#pragma once

namespace pokeclip {

// 독 id — OBS가 독 위치를 이 문자열로 저장한다. 바꾸면 사용자 배치가 초기화된다.
inline constexpr const char *kDockId = "pokeclip-dock";

// 페어링 교환 API 기본값. 운영 HTTPS 도메인이 정해지면 바꾼다 (ADR-036: dev 서버는 HTTP).
inline constexpr const char *kDefaultApiBase = "http://dev.pokeclip.com";
inline constexpr const char *kPairingExchangePath = "/api/stream-keys/pairing-codes/exchange";

// SRT 수신부 기본값 (ADR-020 3절 ingest.* · 로컬 compose도 8890).
inline constexpr const char *kDefaultIngestHost = "ingest.pokeclip.com";
inline constexpr int kDefaultIngestPort = 8890;
inline constexpr int kDefaultLatencyMs = 1000;

// ADR-020: 세그먼트 4s가 GOP의 정수배여야 한다 — 플러그인이 2s로 강제한다.
inline constexpr int kForcedKeyintSec = 2;

// libobs 기본 재연결과 같은 값을 명시한다 (중간 단절은 libobs가 재시도).
inline constexpr int kReconnectRetries = 20;
inline constexpr int kReconnectDelaySec = 2;

// 본방 출력이 비동기로 실패했는지 확인하는 대기 시간.
inline constexpr int kMainStreamGuardMs = 5000;

// libobs는 "stop" 신호를 먼저 보내고 인코더를 멈춘 스레드가 나중에 active를 내린다(obs-output.c
// end_data_capture). 출력 해제는 그 뒤에 해야 하므로 이 간격으로 확인한다 — 최대 간격 × 횟수.
inline constexpr int kReleasePollMs = 100;
inline constexpr int kReleasePollAttempts = 50;

// 브라우저 독 페이지가 이 시간 안에 /api/hello를 부르지 않으면 Qt 폴백으로 바꾼다.
inline constexpr int kBrowserWatchdogMs = 15000;

// A2 멀티오디오(ADR-017): 트랙 1(믹서 0)은 본방 오디오 인코더를 공유하고, 트랙 2~6(믹서 1~5)은
// 플러그인이 AAC 인코더를 만든다. 128 kbps는 2026-08-03 6트랙 실측값 — ADR-020 오디오 칸은 1번 비준 대상.
inline constexpr int kAudioTrackCount = 6;
inline constexpr int kStemAudioBitrateKbps = 128;
inline constexpr int kFallbackTrack0BitrateKbps = 160; // 본방 오디오가 AAC가 아니거나 트랙 1이 아닐 때 우리 트랙 1
inline constexpr const char *kFallbackAacEncoderId = "ffmpeg_aac";
inline constexpr const char *kAudioEncoderNamePrefix = "pokeclip-audio-";

} // namespace pokeclip
