import type { RewindStatus, RewindStatusResult } from '@/api/rewindStatus';
import type { PastVodClock } from '@/features/player/pastVod';
import type { XAnswer } from '@/features/player/rewindSwitch';
import { RESUME_WAIT_MS } from '@/features/player/sessionShared';

// 편집기 영상 원천 상태(POK-473 설계 prw2 14.2 D3 (ㄱ) · 5절 1). 판정 순서 표(위에서 처음 맞는 줄)를 두 함수로 나눈다.
//   nextEditorSourceState  회차 · 스트림 경로 답과 조각 실패를 쌓는다(1 · 2 · 3 · 6–9행)
//   editorSourceView       선 원천에서 카드 자리를 목록 범위에 대 본다(4 · 5행 — 목록이 자라면 저절로 풀리는 보기)
// 위 줄이 아래 줄을 이긴다. 순서 성질 — 갈래(만료 · 회차 경로 · 스트림 경로 · 조각 실패)마다 사건이 하나씩이면 어떤 순서로
// 와도 끝 상태가 같다. 한 갈래의 시계열(회차 경로 ending 뒤 vod 준비 · 재접속 live · 스트림 같은 방송 뒤 R58 등) 사이
// 어디에 다른 갈래 사건을 끼워도 끝 상태는 같다. 시계열 안에서는 대개 나중 답이 이기지만 R58 로 선 3행은 스트림 답으로
// 되돌아가지 않는다 — 회차 경로가 정할 때까지 둔다.
//   끝 상태   1행(만료)과 회차 경로로 정한 2행(없음)은 어떤 답으로도 풀리지 않는다(2행 위에도 1행은 선다).
//   회차 몫   R58 로 선 3행 · vod 원천 · vod 원천의 조각 실패는 스트림 경로 답(R58 · 같은 방송)이 건드리지 않는다(5절 1) —
//            회차 경로 답 · 그 원천의 조각 실패 · 1행만 바꾼다. 방송 중 원천과 그 조각 실패 위에는 R58 이 3행으로 선다.
//   덮개     회차 경로 ending · vod ∧ ¬ready 로 선 3행은 그 아래 상태를 덮어 둔다. 덮인 동안 온 스트림 답 · 조각 실패는
//            아래 상태에 쌓이고, 같은 회차 재접속으로 회차 경로가 다시 live 를 답하면 덮개를 걷어 아래 상태가 드러난다
//            (서버 media/internal/session/round_rule.go:105 합류 · media/internal/index/round_playback.go:66 live).
//   조각 실패 그 조각을 받은 목록 주소와 방송 중 여부를 사건이 싣고 온다. 원천이 서기 전 · R58 3행 · 끝 상태에서는
//            버리고(회차 덮개 아래에서는 덮인 원천에 쌓는다), 지금 원천과 다른 목록의 실패나 방송 중 여부가 그 원천의 경로와
//            어긋난 사건은 낡은 것이라 버린다. 정한 실패는 그 원천에 붙어, 같은 주소로 원천이 다시 서도(4초 폴링) 풀리지 않고
//            다른 주소로 서면(방송이 끝나 회차 vod 로 옮김) 풀린다. 같은 원천의 뒤 조각 실패는 나중 것이 이긴다.
// 카드 표지(CardSceneLabel)는 이 상태와 따로다 — 장면을 못 찾은 카드도 영상 창은 연다.

/** 조각 실패 판정(6–9행)의 근거 — 실패한 조각을 받은 목록(원천)의 경로와 주소. live = 스트림 경로 · vod = 회차 경로 */
export interface SegmentFailure {
  mode: 'live' | 'vod';
  listUrl: string;
}

export type EditorSourceState =
  /** 아직 아무 답도 없음 */
  | { kind: 'checking' }
  /** 목록을 받을 수 있다 — 방송 중이면 스트림 경로 url, 끝났으면 회차 경로 url */
  | { kind: 'ready'; mode: 'live' | 'vod'; url: string }
  /** 방송 중 · 카드 자리가 목록 끝 뒤 — 몇 초 뒤 목록이 자란다 */
  | { kind: 'uploading' }
  /** 같은 키의 다른 방송이 시작됨(R58) — 회차 경로가 정할 때까지 둔다 */
  | { kind: 'finalizing'; cause: 'stream' }
  /**
   * 회차 정리 중(ending · 미준비 vod). under = 덮인 아래 상태(스트림 경로 · 조각 실패 몫) — 회차 경로가 다시 live 면
   * 드러난다. 화면은 kind 만 본다
   */
  | { kind: 'finalizing'; cause: 'round'; under: EditorSourceState }
  /** 방송 중 편집 범위(최근 1시간) 밖. segment 가 있으면 조각 실패 판정(6 · 9행), 없으면 보기(5행) */
  | { kind: 'outsideDvr'; segment?: SegmentFailure }
  /** 출입증 만료(410) */
  | { kind: 'expired' }
  /**
   * 보관 기간이 지났거나 영상이 없음(만료 · 없음은 서버 답으로 가를 수 없다). segment 가 있으면 조각 실패 판정(7 · 9행), 없으면
   * 회차 경로 답(2행 — 끝 상태)
   */
  | { kind: 'goneOrNone'; segment?: SegmentFailure }
  /** 조각 403 이 갱신 뒤에도 남음 — 출입증 문제(8행) */
  | { kind: 'access'; segment: SegmentFailure }
  /** 아직 아무 답도 없을 때 받은 잠시 불가 */
  | { kind: 'unavailable' };

export type EditorSourceEvent =
  /** 출입증 410(isPlaybackExpired) */
  | { type: 'accessExpired' }
  /** 회차 경로 답 — 404 유예는 플레이어 다시보기(pastVod)와 같은 시계로 잰다 */
  | { type: 'round'; result: RewindStatusResult; clock: PastVodClock }
  /** 스트림 경로 답과 그때 물은 지금 방송 번호(R58) */
  | { type: 'stream'; result: RewindStatusResult; currentX: XAnswer; roundId: string }
  /**
   * 조각 받기 실패 뒤 목록을 다시 받은 결과. forbiddenAfterRefresh = 403 → 출입증 갱신 → 또 403, gone = 404.
   * listed = 다시 받은 목록에 그 seq 가 아직 있다 · live = 방송 중 · roundGone = 그때 회차 경로가 404 ·
   * listUrl = 그 조각을 받은 목록 주소(지금 원천과 다르면 낡은 창의 실패라 버린다)
   */
  | {
      type: 'segment';
      failure: 'forbiddenAfterRefresh' | 'gone';
      listed: boolean;
      live: boolean;
      roundGone: boolean;
      listUrl: string;
    };

/** 카드 장면 표지 — located = playerAtMs 값 · sceneMissing = null(clip 이 장면을 못 찾음) · estimated = 칸 없음 */
export type CardSceneLabel = 'located' | 'sceneMissing' | 'estimated';

/** 조각 실패로 정한 상태(6–9행)면 그 근거, 아니면 null */
function segmentFailureOf(state: EditorSourceState): SegmentFailure | null {
  if (state.kind !== 'outsideDvr' && state.kind !== 'goneOrNone' && state.kind !== 'access') {
    return null;
  }
  return state.segment ?? null;
}

/** 지금 원천 — 섰으면 그 목록, 조각 실패로 정했으면 실패를 낸 목록. 원천이 서기 전 · 3행 · 끝 상태면 null */
function sourceOf(state: EditorSourceState): SegmentFailure | null {
  if (state.kind === 'ready') return { mode: state.mode, listUrl: state.url };
  return segmentFailureOf(state);
}

type RoundFinalizing = Extract<EditorSourceState, { cause: 'round' }>;

/** 회차 경로(ending · vod ∧ ¬ready)가 덮어 선 3행인가 */
function isRoundFinalizing(state: EditorSourceState): state is RoundFinalizing {
  return state.kind === 'finalizing' && state.cause === 'round';
}

/** 회차 경로만 바꾸는 상태 — R58 3행 · vod 원천 · vod 원천의 조각 실패(회차 덮개는 그 아래 상태가 받는다) */
function isRoundOwned(state: EditorSourceState): boolean {
  return state.kind === 'finalizing' || sourceOf(state)?.mode === 'vod';
}

/** 뒤 답으로 풀리지 않는 상태 — 1행 · 회차 경로로 정한 2행 */
function isFinal(state: EditorSourceState): boolean {
  if (state.kind === 'expired') return true;
  return state.kind === 'goneOrNone' && segmentFailureOf(state) === null;
}

/** 새 원천(ready). 조각 실패 판정이 같은 목록 주소를 붙들고 있으면 그 판정을 둔다 */
function readyOver(
  prev: EditorSourceState,
  next: Extract<EditorSourceState, { kind: 'ready' }>,
): EditorSourceState {
  return segmentFailureOf(prev)?.listUrl === next.url ? prev : next;
}

/**
 * 회차 경로 200 답 → 상태. 덮개 아래 상태(under)를 받아 ending 은 그 위를 덮고, live 는 그것을 그대로 돌려준다(덮개를
 * 걷음 — 덮개가 없었으면 아무것도 바뀌지 않는다). mode null 은 정하지 않아 null
 */
function fromRound(
  prev: EditorSourceState,
  under: EditorSourceState,
  status: RewindStatus,
): EditorSourceState | null {
  const { mode, ready, url } = status;
  if (mode === 'ended') return { kind: 'goneOrNone' };
  if (mode === 'ending' || (mode === 'vod' && !ready)) {
    return isRoundFinalizing(prev) ? prev : { kind: 'finalizing', cause: 'round', under };
  }
  if (mode === 'vod' && url !== null) return readyOver(under, { kind: 'ready', mode: 'vod', url });
  if (mode === 'live') return under;
  return null;
}

function onRound(
  prev: EditorSourceState,
  result: RewindStatusResult,
  clock: PastVodClock,
): EditorSourceState {
  switch (result.kind) {
    case 'ok':
      return fromRound(prev, isRoundFinalizing(prev) ? prev.under : prev, result.status) ?? prev;
    case 'none':
      return clock.nowMs - clock.unknownSinceMs >= RESUME_WAIT_MS ? { kind: 'goneOrNone' } : prev;
    case 'unavailable':
      return prev.kind === 'checking' ? { kind: 'unavailable' } : prev;
  }
}

/**
 * 스트림 경로 답 — 플레이어 onLiveStreamPoll(features/player/rewindSwitch.ts:403-415)의 같은 방송 규칙을 다시 적었다(그
 * 함수는 내보내지 않는다). 지금 방송 번호를 모르면 버리고, ''(방송 없음)은 통과하고, 이 방송과 다르면 스트림 경로를 접는다.
 * 회차 몫 상태(isRoundOwned)는 건드리지 않는다. R58 은 3행이라 방송 중 원천의 조각 실패 판정(6–9행) 위에 선다
 */
function onStream(
  prev: EditorSourceState,
  result: RewindStatusResult,
  currentX: XAnswer,
  roundId: string,
): EditorSourceState {
  if (isRoundOwned(prev) || currentX.kind !== 'known') return prev;
  if (currentX.id !== '' && currentX.id !== roundId) return { kind: 'finalizing', cause: 'stream' };
  if (result.kind !== 'ok') return prev;
  const { mode, ready, url } = result.status;
  if (mode !== 'live' || !ready || url === null) return prev;
  return readyOver(prev, { kind: 'ready', mode: 'live', url });
}

/**
 * 조각 실패(6–9행). 403 은 출입증 갈래, 404 는 목록에서 빠졌는지로 가른다. 그 조각을 받은 목록이 지금 원천이 아니거나(원천
 * 전 · R58 3행 · 낡은 창) 사건의 방송 중 여부가 그 원천의 경로와 어긋나면 버린다. 판정은 그 원천(경로 · 주소)에 붙는다
 */
function onSegment(
  prev: EditorSourceState,
  event: Extract<EditorSourceEvent, { type: 'segment' }>,
): EditorSourceState {
  const segment = sourceOf(prev);
  if (segment === null || segment.listUrl !== event.listUrl) return prev;
  if (event.live !== (segment.mode === 'live')) return prev;
  if (!event.listed && event.live) return { kind: 'outsideDvr', segment };
  if (event.failure === 'forbiddenAfterRefresh' && !event.roundGone) {
    return { kind: 'access', segment };
  }
  return { kind: 'goneOrNone', segment };
}

/** 원천 상태에 사건 하나를 더한다 */
export function nextEditorSourceState(
  prev: EditorSourceState,
  event: EditorSourceEvent,
): EditorSourceState {
  if (event.type === 'accessExpired' || prev.kind === 'expired') return { kind: 'expired' };
  if (isFinal(prev)) return prev;
  if (event.type === 'round') return onRound(prev, event.result, event.clock);
  // 회차 덮개 아래로 — 스트림 답 · 조각 실패는 덮인 상태에 쌓는다(덮개가 걷히면 드러남)
  if (isRoundFinalizing(prev)) {
    const under = nextEditorSourceState(prev.under, event);
    return under === prev.under ? prev : { ...prev, under };
  }
  switch (event.type) {
    case 'stream':
      return onStream(prev, event.result, event.currentX, event.roundId);
    case 'segment':
      return onSegment(prev, event);
  }
}

/**
 * 화면에 보일 원천 상태(4 · 5행). 방송 중 원천이 섰고 카드 자리가 있으면 목록 범위에 대 본다 — 끝 뒤면 uploading, 첫 PDT
 * 앞이면 outsideDvr. 목록 전 · 카드 없음 · 끝난 방송이면 상태 그대로다
 * @param list 받은 목록의 첫 PDT · 마지막 조각 끝(epoch ms)
 * @param wantMs 열려는 카드 자리(목록 PDT 축 ms)
 */
export function editorSourceView(
  state: EditorSourceState,
  list: { firstPdtMs: number; endMs: number } | null,
  wantMs: number | null,
): EditorSourceState {
  if (state.kind !== 'ready' || state.mode !== 'live' || list === null || wantMs === null) {
    return state;
  }
  if (wantMs > list.endMs) return { kind: 'uploading' };
  if (wantMs < list.firstPdtMs) return { kind: 'outsideDvr' };
  return state;
}

/** 카드 응답 playerAtMs(readPlayerAtMs 결과) → 카드 장면 표지 */
export function cardSceneLabelOf(playerAtMs: number | null | undefined): CardSceneLabel {
  if (playerAtMs === undefined) return 'estimated';
  return playerAtMs === null ? 'sceneMissing' : 'located';
}
