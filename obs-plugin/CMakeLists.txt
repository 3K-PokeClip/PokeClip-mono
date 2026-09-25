cmake_minimum_required(VERSION 3.28...3.30)

# PokeClip for OBS (POK-169)
#
# 빌드 경로가 둘이다.
#  - 기본: obs-plugintemplate 경로. buildspec.json의 OBS 소스·obs-deps·Qt6를 받아 빌드한다 (CI·배포).
#  - POKECLIP_LOCAL_OBS_APP=ON: macOS 개발용. Xcode 없이 설치된 OBS.app의 libobs·Qt에 링크한다.
#    템플릿 macOS 모듈은 Xcode 생성기를 강제하므로 이 경로는 템플릿 모듈을 쓰지 않는다.
option(POKECLIP_LOCAL_OBS_APP "macOS dev build linked against an installed OBS.app (no Xcode)" OFF)

if(POKECLIP_LOCAL_OBS_APP)
  include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/local/buildspec-vars.cmake")
  project(${_name} VERSION ${_version} LANGUAGES C CXX)
  include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/local/obs-app.cmake")
else()
  include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/common/bootstrap.cmake" NO_POLICY_SCOPE)
  project(${_name} VERSION ${_version})
  option(ENABLE_FRONTEND_API "Use obs-frontend-api for UI functionality" ON)
  option(ENABLE_QT "Use Qt functionality" ON)
  include(compilerconfig)
  include(defaults)
  include(helpers)
endif()

add_library(${CMAKE_PROJECT_NAME} MODULE)

target_sources(
  ${CMAKE_PROJECT_NAME}
  PRIVATE
    src/plugin-main.cpp
    src/app-state.cpp
    src/app-state.hpp
    src/audio-assign.cpp
    src/audio-assign.hpp
    src/audio-router.cpp
    src/audio-router.hpp
    src/bridge-server.cpp
    src/bridge-server.hpp
    src/config.cpp
    src/config.hpp
    src/constants.hpp
    src/encoder-opts.cpp
    src/encoder-opts.hpp
    src/dock-host.cpp
    src/dock-host.hpp
    src/fallback-panel.cpp
    src/fallback-panel.hpp
    src/gop-guard.cpp
    src/gop-guard.hpp
    src/pairing-code.cpp
    src/pairing-code.hpp
    src/pairing.cpp
    src/pairing.hpp
    src/srt-target.cpp
    src/srt-target.hpp
    src/srt-url.cpp
    src/srt-url.hpp
    src/stream-target.cpp
    src/stream-target.hpp
    src/ui-thread.cpp
    src/ui-thread.hpp
)

# 서드파티 단일 헤더(cpp-httplib, obs-browser panel)는 경고 대상에서 뺀다.
target_include_directories(${CMAKE_PROJECT_NAME} SYSTEM PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/third_party")
target_compile_features(${CMAKE_PROJECT_NAME} PRIVATE cxx_std_20)
set_target_properties(${CMAKE_PROJECT_NAME} PROPERTIES AUTOMOC ON AUTOUIC OFF AUTORCC OFF)

if(WIN32)
  # cpp-httplib는 Windows 10 이상 API를 요구한다. min/max 매크로는 std와 충돌한다.
  target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE _WIN32_WINNT=0x0A00 NOMINMAX WIN32_LEAN_AND_MEAN)
endif()

find_package(CURL REQUIRED)
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE CURL::libcurl)

if(POKECLIP_LOCAL_OBS_APP)
  pokeclip_local_configure_target(${CMAKE_PROJECT_NAME})
else()
  find_package(libobs REQUIRED)
  find_package(obs-frontend-api REQUIRED)
  find_package(Qt6 REQUIRED COMPONENTS Core Widgets)
  target_link_libraries(
    ${CMAKE_PROJECT_NAME}
    PRIVATE OBS::libobs OBS::obs-frontend-api Qt6::Core Qt6::Widgets
  )
  target_compile_options(
    ${CMAKE_PROJECT_NAME}
    PRIVATE $<$<C_COMPILER_ID:Clang,AppleClang>:-Wno-quoted-include-in-framework-header -Wno-comma>
  )
  set_target_properties_plugin(${CMAKE_PROJECT_NAME} PROPERTIES OUTPUT_NAME ${_name})
endif()
