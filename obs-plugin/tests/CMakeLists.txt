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
  "${PLUGIN_DIR}/src/bridge-server.cpp"
  "${PLUGIN_DIR}/src/encoder-opts.cpp"
  "${PLUGIN_DIR}/src/pairing-code.cpp"
  "${PLUGIN_DIR}/src/srt-url.cpp"
)
target_include_directories(pokeclip-unit-tests PRIVATE "${PLUGIN_DIR}/src")
target_include_directories(pokeclip-unit-tests SYSTEM PRIVATE "${PLUGIN_DIR}/third_party")
target_link_libraries(pokeclip-unit-tests PRIVATE Threads::Threads)
if(WIN32)
  target_compile_definitions(pokeclip-unit-tests PRIVATE _WIN32_WINNT=0x0A00 NOMINMAX WIN32_LEAN_AND_MEAN)
endif()

enable_testing()
add_test(NAME pokeclip-unit-tests COMMAND pokeclip-unit-tests)
