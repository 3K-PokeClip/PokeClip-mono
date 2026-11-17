#pragma once

#include "config.hpp"
#include "constants.hpp"

#include <array>
#include <optional>
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

// 알려진 기본 api_base(두 환경 값·옛 기본값). 이 값들은 손대지 않은 기본값으로 보고 지금 환경 짝으로 맞춘다 —
// IsKnownDefaultApiBase와 브리지 GET known_api_bases(독이 같은 규칙으로 「바뀌는가」를 미리 본다)가 같이 쓴다.
std::array<const char *, 3> KnownDefaultApiBases();
bool IsKnownDefaultApiBase(const std::string &base);
bool IsKnownDefaultIngestHost(const std::string &host);

// 독 개발자 모드 「인증 서버」 칸(브리지 PUT api_base) 규칙.
// 다듬기 — 앞뒤 공백과 끝의 '/'를 뗀다.
std::string NormalizeApiBase(std::string_view text);
// 이 PC 안(루프백) 주소인가. scheme://host[:port]만 받는다(경로·물음표·#·사용자 정보 없음). http·https 모두, 호스트는
// localhost · 127.x.x.x · [::1].
bool IsLoopbackApiBase(std::string_view base);
// 독이 보낼 수 있는 값인가 — 비움(지금 환경의 기본 주소로) · 알려진 기본 주소(환경 짝을 따라간다) · 루프백 주소만.
// PC 밖의 다른 주소는 받지 않는다 — 운영·개발 사이는 서버 환경 스위치로 옮기고, 그 밖의 목적지는 설정 파일로만 바꾼다.
// 그래서 브리지 토큰만으로는 페어링 코드·마크(스트림 키 파생 값)를 컴파일된 두 짝과 이 PC 밖으로 보내게 할 수 없다.
bool IsAcceptedDockApiBase(const std::string &normalized);

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

// 브리지 PUT /api/config의 서버 환경·인증 서버 규칙. before = 요청을 얹기 전 설정, c = 독 값(TakeDockSettings)을 얹은
// 설정. 환경·api_base·키는 c에 실려 온 값으로는 바뀌지 않는다 — before 값에서 시작한다.
//  - requested가 before와 다른 환경이면 전환한다. 같은 본문의 수신 주소는 버리고(열어 둔 초안의 옛 호스트가 전환을
//    되돌리지 않게) 알려진 기본값만 새 짝으로 바꾼다.
//  - 아니면 알려진 기본 수신 호스트를 지금 짝으로 맞춘다(Load와 같은 규칙) — 다른 환경의 기본 호스트를 적거나 다른
//    환경일 때 연 초안을 저장해도 짝이 섞이지 않는다. 직접 적은 호스트는 그대로다.
//  - requestedApiBase(NormalizeApiBase를 거친 값)가 있으면 인증 서버를 그것으로 둔다. 비움·알려진 기본 주소는 (전환 뒤)
//    환경의 기본 주소, 루프백 주소는 그대로. 그 밖의 값(IsAcceptedDockApiBase가 거절)은 무시한다 — 부르는 쪽이 400으로
//    먼저 막는다.
// 환경이나 api_base가 바뀌었으면 키를 지우고 true를 돌려준다 — 키는 그 인증 서버가 발급했다.
bool ApplyPutServerEnv(PluginConfig &c, const PluginConfig &before, std::optional<ServerEnv> requested,
		       const std::optional<std::string> &requestedApiBase = std::nullopt);

// 키를 발급한 곳(서버 환경·api_base)이 같은가. 페어링 교환을 기다리는 사이 환경이 바뀌었으면 받은 키를 버린다.
bool SameKeyIssuer(const PluginConfig &a, const PluginConfig &b);

} // namespace pokeclip
