# 로컬 개발 환경 (M1 1단계)

팀 전원 공통 바닥. 요구사항: **Docker만** (스텁 세그먼트 생성 시에만 ffmpeg 추가).

## 시작

```bash
cp .env.example .env        # 최초 1회
docker compose up -d
```

| 서비스 | 포트 | 용도 |
|---|---|---|
| postgres:17 | 5432 | 3번: 스키마 v0 마이그레이션은 여기에 (M1 2단계) |
| redis:7.4 | 6379 | 3번: 키·TTL·pub/sub 설계 자리 |
| media (MediaMTX 1.19.3) | UDP 8890 (SRT) · 1935 (RTMP) · 8888 (LL-HLS) | 1번: Media Origin 자리 |
| media-stub (nginx) | 8080 | 2번: 플레이어 개발용 정적 세그먼트 (`infra/compose/stub/README.md`) |

## 송출 테스트 (media)

**OBS (SRT):** 설정 → 방송 → 서버 `srt://localhost:8890?streamid=publish:test`
**ffmpeg (RTMP — homebrew ffmpeg은 SRT 미포함):**

```bash
ffmpeg -re -f lavfi -i testsrc2=size=1280x720:rate=30 -f lavfi -i sine=frequency=440 \
  -c:v libx264 -preset veryfast -g 60 -keyint_min 60 -sc_threshold 0 -pix_fmt yuv420p \
  -c:a aac -f flv rtmp://localhost:1935/test
```

⚠️ **인코더 GOP는 반드시 2초**(30fps `-g 60` / 60fps `-g 120`, ADR-020) — 세그먼트 4s가 GOP 정수배여야 드리프트가 없다.

재생 확인:

```bash
curl -s http://localhost:8888/test/index.m3u8   # EXT-X-PART · CAN-BLOCK-RELOAD 보이면 LL-HLS 정상
```

## 설계 근거 포인터

- 파라미터(4s/0.5s/900개): ADR-020 · 재생 규약: 계약3 (플레이어는 **catch-up 끄기** 필수)
- 이 환경은 로컬 전용 — AWS 배포(프라이빗+NLB, ADR-021)는 별도 IaC로 진행
