// 개발 빌드(POKECLIP_DEV_SERVERS, macos-local 프리셋)의 기본 서버 환경을 잰다 — POK-267.
// unit-tests.cpp는 배포 빌드처럼 매크로 없이 컴파일하므로, 매크로를 켠 쪽은 이 실행 파일이 따로 본다.
#include "config.hpp"
#include "constants.hpp"
#include "server-env.hpp"

#include <cstdio>
#include <string>

#ifndef POKECLIP_DEV_SERVERS
#error "이 시험은 POKECLIP_DEV_SERVERS를 켜고 컴파일한다 (tests/CMakeLists.txt)"
#endif

static_assert(pokeclip::kBuildServerEnv == pokeclip::ServerEnv::Dev);

int main()
{
	using namespace pokeclip;
	int failures = 0;
	auto check = [&](bool ok, const char *what) {
		if (!ok) {
			++failures;
			std::fprintf(stderr, "FAIL %s\n", what);
		}
	};

	check(PluginConfig{}.serverEnv == ServerEnv::Dev, "PluginConfig 기본 환경이 개발");
	const ServerDefaults &d = ServersFor(kBuildServerEnv);
	check(std::string(d.apiBase) == "https://dev.pokeclip.com", "개발 api_base");
	check(std::string(d.ingestHost) == "media-dev.pokeclip.com", "개발 수신 호스트");
	check(d.ingestPort == 8890, "개발 수신 포트");
	check(std::string(ServerEnvName(kBuildServerEnv)) == "dev", "server_env 이름");

	// server_env 없는 옛 파일 중 짐작할 근거가 없는 것은 빌드 기본(개발)으로 간다
	PluginConfig unknown;
	unknown.apiBase = "http://localhost:8082";
	unknown.ingestHost = "127.0.0.1";
	check(InferServerEnv(unknown, kBuildServerEnv) == ServerEnv::Dev, "짐작 실패 시 개발");

	if (failures == 0)
		std::printf("build-env-dev: ok (api %s, ingest %s:%d)\n", d.apiBase, d.ingestHost, d.ingestPort);
	return failures == 0 ? 0 : 1;
}
