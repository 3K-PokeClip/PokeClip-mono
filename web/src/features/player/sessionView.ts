import {
  RESUME_WAIT_MS,
  type PlaybackSessionState,
  type SessionButton,
  type SessionPhase,
} from './rewindSwitch';

// 재생 세션이 화면에 그릴 것(설계 5.3절 종결 판, POK-200 M5) — 상태기계(rewindSwitch)의 상태에서 덮개 · 버튼 · 띠 · 숨김을 뽑는
// 순수 함수다. 전이는 rewindSwitch 가 정하고, 여기는 그 상태를 읽기만 한다.

export interface SessionView {
  /**
   * 마지막 프레임 위 덮개. checking = 재접속 대기 동안 「방송이 이어지는지 확인하고 있어요」 · preparing = 그 뒤
   * 「다시보기 준비 중」 · vod-ready = 「다시보기 보기」
   */
  cover: 'checking' | 'preparing' | 'vod-ready' | null;
  /** 덮개(끝에 닿음) 또는 띠(아직 보는 중)에 둘 버튼 */
  actions: SessionButton[];
  /** 「새 방송이 시작됐어요 — 보러 가기」 띠(세션에 묶인 live 가 다른 방송을 봄) */
  banner: { goTo: string } | null;
  /** 받을 목록이 없어 시크바를 숨긴다(live 기원 덮개) */
  seekHidden: boolean;
  /** LIVE 버튼을 숨긴다(끝난 방송의 VOD) */
  liveHidden: boolean;
  /** LIVE 를 눌러 지금 방송을 확인하는 중 */
  liveChecking: boolean;
  /** 「녹화 보기」를 기본 강조로 둔다(명부 ended) */
  recordEmphasis: boolean;
  /** 「다시보기 준비됨」 — 아직 보는 시청자에게 띠로 */
  vodReady: boolean;
  /**
   * 새로 서는 재생기는 받지도 틀지도 않는다(isPlayerStartHeld · POK-528 U2). 재생기 수명
   * 지시이지 그릴 것이 아니다 — 재생기가 기동할 때 한 번 읽는다
   */
  startHeld: boolean;
}

type EndingPhase = Extract<SessionPhase, { kind: 'ending-wait' }>;

/**
 * 새로 서는 재생기(세대가 올라 다시 서는 것 · 리마운트 포함)를 세우지 않는가 — 막힌 끝남
 * 대기 · 마지막 확인 중인 끝남 대기(POK-528 U2). 되감기는 404 뒤 목록을 다시 받는 중에도
 * 세운다(다시 받기가 곧 새 재생기다 — 계획 3-6). 화면에 그릴 것이 아니라 재생기 수명
 * 지시다. 국면 데이터에서 뽑으므로 세대가 오를 때마다 같은 값을 다시 읽는다
 */
export function isPlayerStartHeld(session: PlaybackSessionState): boolean {
  const { phase } = session;
  return phase.kind === 'ending-wait' && (phase.blocked === true || phase.finalCheck === true);
}

/**
 * 끝남 대기의 덮개 — 막혔거나 마지막 확인 중이면 끝까지 볼 수 없음이 정해졌으니 재접속 대기
 * 안이어도 「다시보기 준비 중」(POK-528 (나)-5 · W6), 끝에 닿았으면 재접속 대기 동안 「확인 중」 ·
 * 그 뒤 「준비 중」
 */
function endingCover(phase: EndingPhase, waiting: boolean): SessionView['cover'] {
  if (phase.blocked || phase.finalCheck) return 'preparing';
  if (!phase.atEnd) return null;
  return waiting ? 'checking' : 'preparing';
}

function offerActions(session: PlaybackSessionState): SessionButton[] {
  if (session.offer?.kind === 'go-live') return ['record', 'go-live'];
  if (session.offer?.kind === 'new-broadcast') return ['record', 'new-broadcast'];
  return ['record'];
}

export function sessionView(session: PlaybackSessionState, now: number): SessionView {
  const base: SessionView = {
    cover: null,
    actions: [],
    banner: session.foreignLive === null ? null : { goTo: session.foreignLive },
    seekHidden: false,
    liveHidden: false,
    liveChecking: session.liveChecking,
    recordEmphasis: false,
    vodReady: false,
    startHeld: isPlayerStartHeld(session),
  };
  const phase = session.phase;
  if (phase.kind === 'ending-wait') {
    const waiting = session.endingSince !== null && now - session.endingSince < RESUME_WAIT_MS;
    return {
      ...base,
      cover: endingCover(phase, waiting),
      actions: offerActions(session),
      seekHidden: phase.atEnd && phase.origin === 'live',
      recordEmphasis: session.rosterEnded,
    };
  }
  if (phase.kind === 'vod-offered') {
    return {
      ...base,
      cover: phase.atEnd ? 'vod-ready' : null,
      actions: ['record', 'vod'],
      seekHidden: phase.atEnd && session.source.kind === 'live',
      vodReady: !phase.atEnd,
    };
  }
  if (phase.kind === 'vod') return { ...base, liveHidden: true };
  return base;
}
