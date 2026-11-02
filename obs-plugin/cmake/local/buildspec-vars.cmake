# buildspec.json에서 이름·버전·번들 ID만 읽는다 (로컬 경로 전용 — 템플릿 bootstrap은 쓰지 않는다).
file(READ "${CMAKE_CURRENT_SOURCE_DIR}/buildspec.json" _pokeclip_buildspec)
string(JSON _name GET "${_pokeclip_buildspec}" name)
string(JSON _version GET "${_pokeclip_buildspec}" version)
string(JSON _bundleId GET "${_pokeclip_buildspec}" platformConfig macos bundleId)
string(JSON _displayName GET "${_pokeclip_buildspec}" displayName)
unset(_pokeclip_buildspec)
