# 정적 세그먼트 스텁 (2번 플레이어 개발용)

진짜 Media Origin(MediaMTX+합성 레이어) 완성을 기다리지 않고 플레이어 개발을 시작하기 위한 정적 서버.

## 사용법

```bash
./gen-segments.sh          # ffmpeg로 5분짜리 테스트 세그먼트 생성 (1회)
docker compose up -d media-stub
curl http://localhost:8080/live/stub/index.m3u8
```

- 재생 URL: `http://localhost:8080/live/stub/index.m3u8` — 계약3의 `/live/{streamId}/index.m3u8` 형태, master→media 2단 구조
- 규격: fMP4(CMAF) · 세그먼트 4초 · GOP 2초 (계약3/ADR-020 값과 동일)
- CORS: 요청 오리진 반사 + credentials 허용 (계약3 §1의 쿠키 전제 대비)

## Windows에서 생성하기

`gen-segments.sh`는 bash+ffmpeg 전제라 Windows 네이티브에선 안 돈다. 둘 중 하나로:

**방법 A — WSL2 (권장, Docker Desktop 깔면 이미 있음):**

```bash
sudo apt install -y ffmpeg   # WSL 최초 1회
./gen-segments.sh
```

**방법 B — ffmpeg 컨테이너 (아무것도 설치 안 함):** 이 디렉토리(`infra/compose/stub`)에서 PowerShell로:

```powershell
mkdir -Force data/live/stub
docker run --rm -v "${PWD}/data/live/stub:/out" linuxserver/ffmpeg `
  -y -f lavfi -i testsrc2=size=1280x720:rate=30 -f lavfi -i sine=frequency=440:sample_rate=48000 `
  -t 300 -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 60 -keyint_min 60 -sc_threshold 0 `
  -c:a aac -b:a 128k -f hls -hls_time 4 -hls_playlist_type vod -hls_segment_type fmp4 `
  -hls_flags independent_segments -hls_fmp4_init_filename init.mp4 `
  -hls_segment_filename /out/seg_%05d.m4s -master_pl_name index.m3u8 /out/chunks.m3u8
```

(옵션은 `gen-segments.sh`와 동일 — 스크립트 수정 시 여기도 맞출 것)

## 편집기용 로컬 소스 (클립 편집기 개발용)

클립 편집기(시안 `1d-a`)의 미리보기를 실제 영상으로 돌리기 위한 소스다. 서버가 영상 바이트를
내려주기 전(POK-122)까지 재생 배선을 먼저 끝내려고 둔다.

```bash
./gen-editor-source.sh ~/Downloads/녹화.mov --start 00:20:00 --duration 00:10:00
docker compose restart media-stub   # nginx.conf 의 MIME 목록이 바뀌었을 때
curl -s http://localhost:8080/live/editor-sample/source.json
```

- 재생 URL: `http://localhost:8080/live/editor-sample/index.m3u8`
- 사이드카: 같은 폴더의 `source.json` — 재생목록·필름스트립·파형의 자리를 알려 준다.
  `web/.env.local` 의 `NEXT_PUBLIC_EDITOR_SOURCE_URL` 에 이 주소를 넣는다.
- 구조: **영상 렌디션 + 오디오 렌디션 + 마스터**(POK-168 확정 서빙 형태). 오디오 트랙을 골라
  클립을 만들려면 합본이 아니라 분리여야 한다.
- 파일명이 `chunks.m3u8` 이 아니라 `video.m3u8`·`audio0.m3u8` 인 이유: ffmpeg 의 `%v` 는
  파일명이나 마지막 디렉터리명 중 한 곳에만 올 수 있고, 디렉터리에 두면 `-master_pl_name` 이
  리터럴 `%v` 폴더로 떨어질 수 있다.
- 실측(10분 1080p60): 약 3초 · 443MB · 파일 311개. 영상은 **무손실 복사**다 —
  OBS 녹화의 키프레임 간격이 2초라 다시 인코딩할 이유가 없다. 안 맞으면 스크립트가 멈추고
  `--reencode` 를 권한다.
- **원본 영상을 리포 안으로 복사하지 마라.** gitignore 가 덮는 것은 `data/` 뿐이다.

Windows 는 **WSL2** 에서 실행한다(`sudo apt install -y ffmpeg nodejs`). 이 스크립트는 임의 경로의
로컬 파일을 입력으로 받고 ffmpeg 와 node 를 파이프로 잇기 때문에 컨테이너 1줄 명령으로 못 옮긴다.

## 한계 (정적이라서)

- **라이브 long-poll·블로킹 리로드·`EXT-X-PART` 없음** — VOD형 플레이리스트다. 플레이어 뼈대·DVR 시킹 UI·트랙 구조 개발용.
- 진짜 LL-HLS 동작 확인은 `media` 컨테이너(MediaMTX)에 송출해서 한다 (`docs/dev-environment.md` 참조).
- **catch-up 끄기 등 플레이어 필수 요구사항은 계약3 §4를 따를 것** — 안 지키면 DVR이 "안 되는 것처럼" 보인다.
