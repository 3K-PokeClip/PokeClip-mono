# 편집기 원천 시험 픽스처(PR-W2 M0)

편집기 창 재다중화(`remuxWindow`)와 조각 판독(`fmp4Boxes`) 시험이 쓰는
③ 재포장본 꼴의 작은 조각이다. prod 데이터가 아니다 — 저장소 안의 녹화
조각을 저장소의 ③ 생산자로 다시 싸서 만들었다(2026-11-12, 로컬).

## 파일

| 파일              | 무엇                                                                    | 크기(바이트) |
| ----------------- | ----------------------------------------------------------------------- | ------------ |
| `init.mp4`        | ③ init(`ftyp` + `moov`) — 영상 1(트랙 1, 90000) + 소리 1(트랙 2, 44100) | 1,159        |
| `seg_0000300.m4s` | ③ 조각 seq 300 — 도장 pos 1234.5초                                      | 141,788      |
| `seg_0000301.m4s` | ③ 조각 seq 301 — 도장 pos 1238.5초                                      | 141,788      |
| `seg_0000302.m4s` | ③ 조각 seq 302 — 도장 pos 1242.5초                                      | 141,788      |

세 조각은 같은 녹화(`media/internal/playback/testdata/segment_4s.mp4` —
320x240, 영상 1 + 소리 1, B 프레임, 파트 4개)를 도장 위치만 4초씩 옮겨 싼
것이다. 영상 DTS 는 조각 사이에 이어진다(조각마다 360000 눈금).

② 녹화 파일(영상 1 + 소리 6, 소리가 영상보다 먼저 시작)은 여기에 복사하지
않았다. `media/internal/playback/testdata/segment_1v6a.mp4`(포크
v1.21.1-pokeclip.1 녹화 실물, sha256 `15ca0b59…2443`)를 그대로 쓴다. 그
폴더는 이미 web-ci 경로 필터에 있다.

## 판독 값(시험 기대값의 출처)

ffprobe 9.0.2 · mp4dump(Bento4 1.6.0.0) 출력이다.

| 무엇                                                     | 값                                |
| -------------------------------------------------------- | --------------------------------- |
| seq 300 영상 첫 DTS · PTS 최솟값 · PTS+길이 최댓값(눈금) | 111106099 · 111112111 · 111472137 |
| seq 301 같은 셋                                          | 111466099 · 111472111 · 111832137 |
| seq 302 같은 셋                                          | 111826099 · 111832111 · 112192137 |
| seq 300 소리 첫 DTS = PTS(눈금)                          | 54441450(= 1234.5초)              |
| 첫 영상 표본 길이 · cto(trun v1)                         | 2997 · 6012                       |
| 영상 표본 수(조각마다 · 셋 합)                           | 120 · 360                         |
| 세 조각 이은 바이트의 ffprobe start_time 영상 · 소리     | 1234.579011 · 1234.500000         |
| mediabunny 1.65.2 변환 출력 영상 · 소리 start_time       | 0.079010 · 0.000000               |
| 변환 출력 영상 · 소리 패킷 수                            | 360 · 516                         |
| `edts`(편집 목록)                                        | 없음                              |

출력 0초가 소리 첫 PTS(1234.5초)라서, 원점을 첫 영상 PTS 로 셈하면
약 0.079초 어긋난다. 시험이 이 차이로 원점 하드코딩을 잡는다.

## 다시 만들기

생산자는 `media/internal/playback` 패키지(`Remuxer`, 저장소 판 49e3d9ae ·
producer.go 마지막 변경 8c881675)다. `internal` 이라 모듈 사본 안에 명령을
두고 돌렸다. Go 1.27.2.

```bash
RUN=/path/to/run   # 실행 기록 폴더
git archive HEAD media | tar -x -C "$RUN/work"
mkdir -p "$RUN/work/media/cmd/m0produce"
cp "$RUN/scripts/m0produce.go" "$RUN/work/media/cmd/m0produce/main.go"
cd "$RUN/work/media"
go run ./cmd/m0produce internal/playback/testdata/segment_4s.mp4 \
  "$RUN/results/fixtures" 300 1234.5 4.0 3
```

`m0produce.go` 는 같은 입력을 pos 마다 `Remuxer{}.Produce` 로 싸서
`init.mp4` 와 `seg_<seq 7자리>.m4s` 를 쓴다. init 이 조각마다 같은지도
확인한다. 원문(명령 · 출력 · 판독 · sha256)은 실측 폴더
`pokeclip-empirical/runs/2026-11-12-prw2-m0/` 에 있다.

## sha256

```text
83e663c55de687079f35d58c91889928dd6a86716bfa5f1cfbf11f932cf5e0cc  init.mp4
c849a4035766afeb6e978a5d9f70d17ee889e0727031858f20151fd7e1f3d706  seg_0000300.m4s
325e9e36adb65e6cb6b347c14439dcdf75bdd1077e7e95df747580e0827106ce  seg_0000301.m4s
8040b54b6f7f5a63cec07faf003f62be5dc00bdd6198e1bcb63679e4810e73f7  seg_0000302.m4s
```
