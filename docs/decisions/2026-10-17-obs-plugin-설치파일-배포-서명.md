# ADR-0005: OBS 플러그인은 GitHub Releases에 설치 파일로 내고, 서명은 무료 범위(자체 서명)부터 한다

날짜: 2026-10-17 / 상태: 확정 / 관련: POK-261(A7 스토리) · POK-284(배포 CI, PR #272) · POK-361(설치 파일) · POK-362(자체 서명·빌드 증명) · POK-360(공인 서명 후속),
위키 `specs/9_Pokeclip_기능명세서.md` A7(「GitHub Release 배포」), ADR-0004(CI 경로 필터 원칙)

## Context

플러그인은 CI 아티팩트(zip·tar.xz)를 손으로 풀어 넣어야 설치됐다. Windows는 스트리머가 OBS 설치 폴더를 알고 압축을
직접 풀어야 했고, macOS는 브라우저로 받으면 격리 속성 때문에 `xattr`가 필요할 수 있었다. 아티팩트는 14일 뒤 사라지고,
Chrome은 서명 없는 실행 파일이 든 압축을 「위험한 파일」로 막는다. develop에 머지된 판은 아예 빌드되지 않았다.

서명은 돈과 명의가 든다. Apple Developer Program은 연 US$99이고, Windows OV 코드서명 키는 2023-06부터 HSM 보관이
의무라 PFX를 GitHub Secrets에 넣는 방식이 막혔다(클라우드 서명 필요). Azure Artifact Signing의 Public Trust는
한국 **조직**만 받고 개인은 미국·캐나다만 받는다(Microsoft 문서, 2026-10 확인). 무료인 SignPath Foundation은
이미 공개된 판·전 구성요소 OSI 라이선스·서명 정책 페이지가 조건이다(모노레포 루트에 LICENSE가 없다).

## Decision

1. **배포 경로는 GitHub Releases.** `obs-plugin-v<X.Y.Z>[-<접미사>]` 태그를 밀면 `obs-plugin-release.yml`이
   CI와 같은 잡으로 빌드해 Release에 붙인다. `buildspec.json` `version`(X.Y.Z)이 정본이고 태그와 다르면 실패,
   태그 커밋은 origin/develop·main에 있어야 하며, 접미사가 있으면 prerelease다. 공개된 Release는 덮어쓰지 않는다.
   develop push도 빌드해 아티팩트를 30일 남긴다. (POK-284)
2. **Windows 설치 파일은 Inno Setup**(`cmake/windows/resources/installer-Windows.iss.in`). 설치 위치는 OBS 폴더가
   아니라 **`%ProgramData%\obs-studio\plugins\pokeclip-obs\{bin\64bit, data}`** — OBS 32.2.1 frontend
   `AddExtraModulePaths`가 읽는 경로이고 템플릿 `windows-x64` 프리셋의 설치 접두와 같다. 폴더를 묻지 않는다.
   - OBS가 켜져 있으면 `AppMutex=OBSStudioCore`(OBS `CheckIfAlreadyRunning`의 뮤텍스)로 막고, 자동으로 닫지 않는다.
   - OBS 폴더에 예전 zip 사본이 있으면 지운다(두 벌 로드 방지). 제거는 플러그인 폴더만 — `plugin_config`는 남긴다.
   - **AppId는 `buildspec.json` `uuids.windowsApp`에 고정하고 바꾸지 않는다** — 바꾸면 덮어쓰기 설치·제거가 끊긴다.
   - zip(OBS 루트 배치)은 포터블 OBS·수동 설치용으로 계속 낸다. 포터블 OBS는 `%ProgramData%`를 읽지 않는다.
3. **macOS는 템플릿이 이미 만들던 pkg**(`create-package.cmake`의 pkgbuild + productbuild)를 `package-macos --package`로
   산출물로 낸다. tar.xz는 더 안 낸다. component plist로 판 검사를 끈다(CFBundleVersion이 CI 실행 번호라 앞선
   실행의 pkg를 나중에 깔면 Installer가 번들을 건너뛴다).
4. **서명은 무료 범위부터.** Windows는 자체 인증서(`CN=PokeClip`)로 dll·제거 프로그램·설치 파일을 서명하고
   타임스탬프를 붙이며, 모든 Release 자산에 GitHub 빌드 증명(artifact attestation)과 SHA256SUMS를 붙인다(POK-362).
   macOS는 ad-hoc 그대로. 무서명(또는 자체 서명)이어도 정식 Release를 낼 수 있다. 공인 서명은 POK-360.

## Alternatives Considered

- **NSIS** — obs-multi-rtmp 원본의 도구. 같은 일을 스크립트로 더 직접 짜야 하고(OBS 실행 감지·제거 프로그램 서명),
  원본 설치 스크립트에는 공용 플러그인 폴더를 재귀로 지우는 코드가 있었다. 러너엔 3.10만 있다. 기각.
- **OBS 설치 폴더에 설치**(지금 zip과 같은 자리) — 레지스트리로 OBS를 찾고 못 찾으면 폴더를 고르게 해야 하며,
  OBS를 다시 깔거나 업데이트할 때 남의 프로그램 폴더를 건드린다. ProgramData는 OBS가 플러그인 전용으로 읽는 곳이다. 기각.
- **정식 Release는 서명 필수** — 서명 계정이 생길 때까지 정식판을 못 낸다. 출시 일정상 기각. 대신 시크릿이 있는데
  서명이 실패하면 무서명으로 대신 내보내지 않는다.
- **Windows 서명을 Azure Artifact Signing·상용 OV로 바로** — 명의(조직/개인)와 비용이 정해지지 않았다. POK-360으로 미룬다.
- **공통 빌드 워크플로를 따로 떼어 내기** — 기능 차이 없이 파일이 는다. CI 워크플로에 `workflow_call`을 더해 재사용한다.

## Consequences

- 스트리머는 설치 파일 하나로 설치·제거한다. 다만 **OS 경고는 남는다** — 자체 서명은 OS의 신뢰 목록에 없어
  SmartScreen·UAC 「알 수 없는 게시자」·Chrome 차단·macOS 「확인되지 않은 개발자」가 무서명과 같이 난다.
  README 「설치」에 넘기는 법을 적었다. 사용자에게 자체 인증서를 신뢰 목록에 넣으라고 안내하지 않는다.
- POK-261의 원래 완료 조건(게시자 확인·Gatekeeper 통과)은 POK-360으로 옮겼다. POK-360은 `Sign-Windows.ps1`의
  공급자와 Environment 시크릿만 바꾸고, macOS는 `setup-macos-codesigning` → `build-macos --codesign` →
  `package-macos --package --codesign --notarize`를 태그 빌드에 붙인다.
- 설치 파일은 CI에서만 만들어진다(로컬엔 Windows 툴체인·Xcode가 없다). Windows 잡이 `Test-WindowsInstaller.ps1`로
  무인 설치·재설치·제거·보존 범위를, macOS 잡이 `installer -target CurrentUserHomeDirectory`로 설치를 시험한다.
  실제 OBS 로드는 실기(Windows PC·Mac)에서 본다.
- `buildspec.json`이 바뀌어 `.deps` 캐시가 한 번 미스한다.

## 관련

- PR #272(POK-284 배포 CI), POK-361 PR(설치 파일)
- `obs-plugin/README.md` 「배포 빌드」·「설치」
