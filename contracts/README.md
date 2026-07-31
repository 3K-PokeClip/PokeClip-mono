# contracts — 서로 주고받는 약속

**담당: 3명 공동.** 여기를 고치는 PR에는 전원이 리뷰어로 붙는다.

## 왜 이 폴더가 있나

셋이 각자 다른 언어로 짠다. 자바 클래스를 Go가 읽을 수 없고, Go 구조체를 파이썬이 읽을 수 없다.
그래서 **모두가 읽을 수 있는 형태**로 주고받을 모양을 적어 둔다.

이게 없으면 이런 일이 난다 — 한쪽은 `startAt`으로 보내고 다른 쪽은 `start_time`으로 읽으려다,
배포하고 나서야 전부 실패하는 것을 안다.

## 무엇을 담나

| 파일 | 내용 | 방향 | 상태 |
|---|---|---|---|
| [`sqs/job-command.schema.json`](sqs/job-command.schema.json) | 잡 지시서 공통 봉투 (계약1) | 3번 → 1·2번 | 초안 |
| [`api/internal.yaml`](api/internal.yaml) | 서비스끼리만 부르는 API | 3번 ↔ 1·2번 | 초안 |
| [`db/stream_segments.md`](db/stream_segments.md) | 세그먼트 인덱스 DDL | 소유 3번 · 쓰기 1번 | 초안 |
| `api/openapi.yaml` | 대시보드가 부르는 공개 API | 3번 → 2번 | 미작성 |

### 계약 지도 — 누가 누구에게

```
플러그인 ──스트림키──▶ Media ──세그먼트 인덱스──▶ Clip
                         │                         │
                    LL-HLS 재생 규약           SSE·REST
                         │                         │
                         ▼                         ▼
                        Web  ◀───────────────────  │
                                                   │
  Chat ──하이라이트 후보──▶ Clip ──SQS 잡 봉투──▶ 워커
                                     ◀─상태 콜백─┘
```

| 계약 | 이 폴더 | 정본 |
|---|---|---|
| 잡 봉투 · 상태 콜백 | `sqs/` · `api/internal.yaml` | 여기 |
| 스트림 키 검증 (계약4C) | `api/internal.yaml` | 여기 |
| 하이라이트 후보 (계약2A) | `api/internal.yaml` | 여기 |
| 세그먼트 인덱스 | `db/stream_segments.md` (DDL) | LLM-WIKI (본문) |
| LL-HLS 재생 규약 (계약3) | — | **LLM-WIKI** |
| SRT 입력 규약 | — | **LLM-WIKI** |

## 무엇을 담지 않나

- 구현 코드 — 각 서비스 폴더에 둔다
- 설계 근거·대안 검토 — 그건 ADR이고, 정본은
  [`PokeClip-LLM-WIKI`](https://github.com/3K-PokeClip/PokeClip-LLM-WIKI)에 있다

**여기엔 기계가 읽는 형식(JSON Schema·OpenAPI·DDL)을 둔다.** 산문 설명과 근거는 LLM-WIKI다.
같은 내용을 두 곳에 적으면 반드시 어긋난다.

## 고칠 때 지킬 것

**필드 추가는 안전하고, 삭제·이름 변경은 위험하다.**
배포 순서에 따라 한쪽이 못 읽는 구간이 생긴다. 지우기 전에 반드시 상대와 배포 순서를 맞춘다.

## 상태

**전부 초안이다.** 확정 전까지 필드명과 경로가 바뀔 수 있다.
각 파일 안에 상대에게 물어볼 것을 적어 뒀으니, 확인해 주면 확정으로 올린다.

`api/openapi.yaml`(공개 API)은 Clip Service의 엔드포인트가 정해진 뒤에 쓴다.
