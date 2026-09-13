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

// 브라우저 독 페이지가 이 시간 안에 /api/hello를 부르지 않으면 Qt 폴백으로 바꾼다.
inline constexpr int kBrowserWatchdogMs = 15000;

} // namespace pokeclip
