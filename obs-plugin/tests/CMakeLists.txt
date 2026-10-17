# OBS 없이 도는 단위·통합 테스트 — 플러그인 빌드와 독립 프로젝트다.
#   cmake -S obs-plugin/tests -B obs-plugin/build_tests && cmake --build obs-plugin/build_tests && ctest --test-dir obs-plugin/build_tests --output-on-failure
cmake_minimum_required(VERSION 3.28)
project(pokeclip-obs-tests LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(PLUGIN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/..")

find_package(Threads REQUIRED)

add_executable(
  pokeclip-unit-tests
  unit-tests.cpp
  "${PLUGIN_DIR}/src/audio-assign.cpp"
  "${PLUGIN_DIR}/src/bridge-server.cpp"
  "${PLUGIN_DIR}/src/encoder-opts.cpp"
  "${PLUGIN_DIR}/src/end-signal-policy.cpp"
  "${PLUGIN_DIR}/src/mark-policy.cpp"
  "${PLUGIN_DIR}/src/pairing-code.cpp"
  "${PLUGIN_DIR}/src/private-file.cpp"
  "${PLUGIN_DIR}/src/retry-policy.cpp"
  "${PLUGIN_DIR}/src/server-env.cpp"
  "${PLUGIN_DIR}/src/srt-url.cpp"
)
target_include_directories(pokeclip-unit-tests PRIVATE "${PLUGIN_DIR}/src")
target_include_directories(pokeclip-unit-tests SYSTEM PRIVATE "${PLUGIN_DIR}/third_party")
target_link_libraries(pokeclip-unit-tests PRIVATE Threads::Threads)
if(WIN32)
  target_compile_definitions(pokeclip-unit-tests PRIVATE _WIN32_WINNT=0x0A00 NOMINMAX WIN32_LEAN_AND_MEAN)
endif()

# 개발 빌드(POKECLIP_DEV_SERVERS)의 기본 서버 환경 — 위 실행 파일은 배포 빌드처럼 매크로 없이 컴파일한다.
# constants.hpp를 읽는 소스를 한 실행 파일 안에서 매크로 값이 다르게 섞지 않는다(inline 상수의 ODR).
add_executable(pokeclip-build-env-dev build-env-dev.cpp "${PLUGIN_DIR}/src/server-env.cpp")
target_include_directories(pokeclip-build-env-dev PRIVATE "${PLUGIN_DIR}/src")
target_compile_definitions(pokeclip-build-env-dev PRIVATE POKECLIP_DEV_SERVERS)

enable_testing()
add_test(NAME pokeclip-unit-tests COMMAND pokeclip-unit-tests)
add_test(NAME pokeclip-build-env-dev COMMAND pokeclip-build-env-dev)
