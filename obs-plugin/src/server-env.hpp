#pragma once

#include "config.hpp"
#include "constants.hpp"

#include <string>
#include <string_view>

namespace pokeclip {

// 서버 환경(운영·개발) 규칙 — OBS 없이 시험한다(POK-267). 짝 표와 빌드 기본 환경은 constants.hpp.
//
// 「알려진 기본값은 환경을 따라간다」: 저장된 api_base·ingest_host가 어느 환경의 기본값(또는 옛 기본값)이면 손대지
// 않은 값으로 보고 지금 환경의 값으로 바꾼다. 직접 적은 값(로컬 auth http://localhost:8082, 127.0.0.1 등)은 그대로 둔다.

const ServerDefaults &ServersFor(ServerEnv env);
const char *ServerEnvName(ServerEnv env);
// "prod"·"dev"만 받는다. 그 밖이면 false이고 out은 그대로다.
bool ParseServerEnv(std::string_view text, ServerEnv &out);

bool IsKnownDefaultApiBase(const std::string &base);
bool IsKnownDefaultIngestHost(const std::string &host);

// server_env 키가 없는 설정 파일(POK-267 이전)의 환경을 api_base로 짐작한다. dev 기본값(https·옛 http) → 개발,
// 운영 기본값 → 운영. api_base가 직접 적은 값이면 수신 호스트로 한 번 더 보고, 그래도 모르면 fallback(빌드 기본).
// 그래서 dev 키로 페어링된 기존 설치는 배포 빌드로 올려도 개발에 남는다 — 키가 끊기지 않는다.
ServerEnv InferServerEnv(const PluginConfig &c, ServerEnv fallback);

struct ServerEnvChange {
	bool env = false;    // serverEnv가 바뀌었다
	bool api = false;    // api_base를 바꿨다
	bool ingest = false; // ingest_host·ingest_port를 바꿨다
};

// c.serverEnv를 target으로 두고, 알려진 기본값인 api_base·ingest_host(와 알려진 기본 포트)만 target 짝으로 바꾼다.
// 키(streamid·passphrase)는 건드리지 않는다 — 환경을 바꾸는 쪽(브리지 PUT)이 연결 해제를 맡는다.
ServerEnvChange ApplyServerEnv(PluginConfig &c, ServerEnv target);

} // namespace pokeclip
