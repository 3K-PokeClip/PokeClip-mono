# macOS 개발용 빌드 — 설치된 OBS.app에 직접 링크한다 (Xcode 불필요).
#
# 필요한 것:
#  - /Applications/OBS.app (32.2.x)
#  - OBS_SOURCE_DIR: 같은 버전의 obs-studio 소스 (libobs·frontend/api 헤더만 쓴다)
#  - Homebrew qtbase (헤더·moc). 실행은 OBS.app 안의 Qt를 쓰도록 빌드 후 설치 이름을 @rpath로 바꾼다.
#    OBS.app의 Qt와 부 버전(6.11 등)이 같아야 한다.
#
# CI·배포 빌드는 이 파일을 쓰지 않는다 (템플릿 경로).

if(NOT APPLE)
  message(FATAL_ERROR "POKECLIP_LOCAL_OBS_APP는 macOS 전용이다.")
endif()

set(OBS_APP "/Applications/OBS.app" CACHE PATH "링크할 OBS.app")
set(OBS_SOURCE_DIR "$ENV{OBS_SOURCE_DIR}" CACHE PATH "OBS.app과 같은 버전의 obs-studio 소스")

if(NOT EXISTS "${OBS_SOURCE_DIR}/libobs/obs.h" OR NOT EXISTS "${OBS_SOURCE_DIR}/frontend/api/obs-frontend-api.h")
  message(FATAL_ERROR "OBS_SOURCE_DIR에 obs-studio 소스를 지정하라 (예: git clone --depth 1 --branch 32.2.1 https://github.com/obsproject/obs-studio). 현재: '${OBS_SOURCE_DIR}'")
endif()

set(_obs_fw "${OBS_APP}/Contents/Frameworks")
if(NOT EXISTS "${_obs_fw}/libobs.framework/Versions/A/libobs")
  message(FATAL_ERROR "OBS.app을 찾지 못했다: ${OBS_APP}")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE RelWithDebInfo CACHE STRING "" FORCE)
endif()
if(NOT CMAKE_OSX_DEPLOYMENT_TARGET)
  set(CMAKE_OSX_DEPLOYMENT_TARGET 13.0)
endif()

# libobs가 생성 헤더로 요구하는 obsconfig.h 대체본 (macOS에서 쓰는 값만)
set(_obs_gen "${CMAKE_CURRENT_BINARY_DIR}/obs-generated")
file(WRITE "${_obs_gen}/obsconfig.h" "#pragma once\n#define OBS_RELEASE_CANDIDATE 0\n#define OBS_BETA 0\n")

# arm64에서 libobs 헤더(util/sse-intrin.h)가 SIMDE를 요구한다 — OBS는 deps로, 로컬은 Homebrew simde로.
find_path(SIMDE_INCLUDE_DIR simde/x86/sse2.h PATHS /opt/homebrew/include /usr/local/include)
if(NOT SIMDE_INCLUDE_DIR)
  message(FATAL_ERROR "SIMDE 헤더가 없다: brew install simde")
endif()

add_library(OBS::libobs SHARED IMPORTED)
set_target_properties(
  OBS::libobs
  PROPERTIES
    IMPORTED_LOCATION "${_obs_fw}/libobs.framework/Versions/A/libobs"
    INTERFACE_INCLUDE_DIRECTORIES "${OBS_SOURCE_DIR}/libobs;${_obs_gen};${SIMDE_INCLUDE_DIR}"
)

add_library(OBS::obs-frontend-api SHARED IMPORTED)
set_target_properties(
  OBS::obs-frontend-api
  PROPERTIES
    IMPORTED_LOCATION "${_obs_fw}/obs-frontend-api.dylib"
    INTERFACE_INCLUDE_DIRECTORIES "${OBS_SOURCE_DIR}/frontend/api"
)

find_package(Qt6 REQUIRED COMPONENTS Core Widgets)

# plugin-support 정적 라이브러리 (템플릿 helpers_common과 같은 구성)
configure_file(src/plugin-support.c.in plugin-support.c @ONLY)
add_library(plugin-support STATIC)
target_sources(plugin-support PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/plugin-support.c" PUBLIC src/plugin-support.h)
target_include_directories(plugin-support PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/src")
target_link_libraries(plugin-support PRIVATE OBS::libobs)

function(pokeclip_local_configure_target target)
  target_link_libraries(${target} PRIVATE plugin-support OBS::libobs OBS::obs-frontend-api Qt6::Core Qt6::Widgets)
  target_compile_options(${target} PRIVATE -Wno-quoted-include-in-framework-header)
  option(POKECLIP_DEV_LOG_DOCK_URL "개발용: 독 URL(토큰 포함)을 OBS 로그에 남긴다 — 로컬 빌드 전용" ON)
  if(POKECLIP_DEV_LOG_DOCK_URL)
    target_compile_definitions(${target} PRIVATE POKECLIP_DEV_LOG_DOCK_URL)
  endif()

  set_target_properties(
    ${target}
    PROPERTIES
      BUNDLE TRUE
      BUNDLE_EXTENSION plugin
      MACOSX_BUNDLE_GUI_IDENTIFIER "${_bundleId}"
      MACOSX_BUNDLE_BUNDLE_NAME "${target}"
      MACOSX_BUNDLE_BUNDLE_VERSION "${PROJECT_VERSION}"
      MACOSX_BUNDLE_SHORT_VERSION_STRING "${PROJECT_VERSION}"
  )

  # 바이너리를 다시 링크할 때만: Homebrew Qt → OBS.app Qt(@rpath)
  add_custom_command(
    TARGET ${target}
    POST_BUILD
    COMMAND /bin/sh "${CMAKE_CURRENT_SOURCE_DIR}/cmake/local/relink-qt-to-obs.sh" "$<TARGET_FILE:${target}>"
    COMMENT "Relink Qt to OBS.app"
    VERBATIM
  )

  # 매 빌드: data/(locale, ui) → 번들 Resources, 그 뒤 ad-hoc 서명. obs_module_file()이 Resources에서 찾는다.
  add_custom_target(
    ${target}-resources
    ALL
    COMMAND "${CMAKE_COMMAND}" -E rm -rf "$<TARGET_BUNDLE_CONTENT_DIR:${target}>/Resources"
    COMMAND "${CMAKE_COMMAND}" -E copy_directory "${CMAKE_CURRENT_SOURCE_DIR}/data" "$<TARGET_BUNDLE_CONTENT_DIR:${target}>/Resources"
    COMMAND codesign --force --sign - "$<TARGET_BUNDLE_DIR:${target}>"
    DEPENDS ${target}
    COMMENT "Copy data into bundle and ad-hoc sign"
    VERBATIM
  )

  # cmake --build --preset macos-local --target install-local
  set(_plugins_dir "$ENV{HOME}/Library/Application Support/obs-studio/plugins")
  add_custom_target(
    install-local
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${_plugins_dir}"
    COMMAND "${CMAKE_COMMAND}" -E rm -rf "${_plugins_dir}/$<TARGET_BUNDLE_DIR_NAME:${target}>"
    COMMAND "${CMAKE_COMMAND}" -E copy_directory "$<TARGET_BUNDLE_DIR:${target}>" "${_plugins_dir}/$<TARGET_BUNDLE_DIR_NAME:${target}>"
    DEPENDS ${target}-resources
    COMMENT "Install ${target}.plugin into ${_plugins_dir}"
    VERBATIM
  )
endfunction()
