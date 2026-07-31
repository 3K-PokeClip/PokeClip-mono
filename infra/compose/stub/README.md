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

## 한계 (정적이라서)

- **라이브 long-poll·블로킹 리로드·`EXT-X-PART` 없음** — VOD형 플레이리스트다. 플레이어 뼈대·DVR 시킹 UI·트랙 구조 개발용.
- 진짜 LL-HLS 동작 확인은 `media` 컨테이너(MediaMTX)에 송출해서 한다 (`docs/dev-environment.md` 참조).
- **catch-up 끄기 등 플레이어 필수 요구사항은 계약3 §4를 따를 것** — 안 지키면 DVR이 "안 되는 것처럼" 보인다.
