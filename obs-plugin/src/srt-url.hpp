#pragma once

#include "config.hpp"

#include <string>

namespace pokeclip {

// ADR-019 streamid(#!::r=<token>,m=publish)에서 r= 값만 꺼낸다. 형식이 아니면 빈 문자열.
std::string StreamTokenOf(const std::string &streamId);

// 브리지·로그에 보여줄 키 힌트 (토큰 끝 4자).
std::string KeyHintOf(const std::string &streamId);

// SRT URL 파서(av_find_info_tag)는 '&'로 값을 끊고 '+'를 공백으로 바꾼다 — 그런 문자가 없어야 한다.
bool IsSafeStreamId(const std::string &streamId);

// srt://host:port?latency=<µs>&pkt_size=1316[&pbkeylen=32]  — 비밀을 싣지 않는다.
std::string BuildSrtServerUrl(const PluginConfig &config);

} // namespace pokeclip
