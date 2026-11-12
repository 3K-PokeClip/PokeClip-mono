// 판 NULL 조각의 ② 머리 — 창 배치 · 컷 원점이 기대는 값의 꼴(POK-473 설계 prw2 3.2 결정 3 · 4절). 판독(Range 64KB ·
// 샘플 있는 첫 traf · 빈 트랙 빼기)과 seq 별 세션 캐시 구현은 E4b-1 에서 이 파일에 더한다. 이 커밋은 형만 둔다 — 순수 모듈
// (windowPlacement · cutPosition · rangeEndpoints)이 구현 없이 형으로 먼저 서게(단독 typecheck).

/** ② 파일 머리. 모두 그 ② 파일 축의 초다(트랙마다 자기 timescale 로 먼저 초로 바꾼 값) */
export interface FileHead {
  /** Hd2 — 샘플 있는 트랙의 첫 DTS 최솟값(렌더 readTiming 과 같은 규칙) */
  hd: number;
  /** Hp2 — 샘플 있는 트랙의 PTS 최솟값의 최솟값(영상은 첫 traf 표본들의 PTS 최솟값 — open GOP 대비) */
  hp: number;
  /** D2 — ② 영상 첫 DTS */
  videoDts: number;
}

/** seq 하나의 머리 판독 결과 — missing 은 소리 주소 응답에 그 행이 없음(② 미업로드), failed 는 판독 실패 */
export type HeadEntry =
  { state: 'ready'; head: FileHead } | { state: 'missing' } | { state: 'failed' };

/** seq 별 머리 캐시. 조각 머리는 바뀌지 않으므로 ready 는 다시 읽지 않는다. get 이 undefined 면 아직 묻지 않은 seq 다 */
export interface HeadCache {
  get(seq: number): HeadEntry | undefined;
  /** 값이 바뀔 때마다 부른다. 돌려준 함수로 구독을 끊는다 */
  subscribe(listener: () => void): () => void;
}
