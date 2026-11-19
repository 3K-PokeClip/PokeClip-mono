import {
  RESUME_WAIT_MS,
  loadStoppedNow,
  type PlaybackSessionState,
  type SessionButton,
  type SessionPhase,
} from './rewindSwitch';

// 재생 세션이 화면에 그릴 것(설계 5.3절 종결 판, POK-200 M5) — 상태기계(rewindSwitch)의 상태에서 덮개 · 버튼 · 띠 · 숨김을 뽑는
// 순수 함수다. 전이는 rewindSwitch 가 정하고, 여기는 그 상태를 읽기만 한다.

export interface SessionView {
  /**
   * 마지막 프레임 위 덮개. checking = 재접속 대기 동안 「방송이 이어지는지 확인하고 있어요」 · preparing = 그 뒤
   * 「다시보기 준비 중」(명부가 ended 면 재접속 대기 안이어도 곧바로 — POK-612) · vod-ready = 「다시보기 보기」
   */
  cover: 'checking' | 'preparing' | 'vod-ready' | null;
  /** 덮개(끝에 닿음) 또는 띠(아직 보는 중)에 둘 버튼 */
  actions: SessionButton[];
  /** 「새 방송이 시작됐어요 — 보러 가기」 띠(세션에 묶인 live 가 다른 방송을 봄) */
  banner: { goTo: string } | null;
  /** 받을 목록이 없어 시크바를 숨긴다(live 기원 덮개) */
  seekHidden: boolean;
  /** LIVE 를 눌러 지금 방송을 확인하는 중 */
  liveChecking: boolean;
  /** 「다시보기 준비됨」 — 아직 보는 시청자에게 띠로 */
  vodReady: boolean;
  /**
   * 새로 서는 재생기는 받지도 틀지도 않는다(isPlayerStartHeld · POK-528 U2). 재생기 수명
   * 지시이지 그릴 것이 아니다 — 재생기가 기동할 때 한 번 읽는다
   */
  startHeld: boolean;
  /** 끝남 대기의 재접속 대기(RESUME_WAIT_MS) 안이다 — 명부를 짧은 주기로 다시 읽을 때다(POK-612) */
  resumeWaiting: boolean;
}

/**
 * 화면 밖에서 오는 보기 재료(POK-612). 국면 · 판정에는 쓰지 않고 그릴 것에만 쓴다 — 판정은 회차별
 * 조회뿐이다(계약3 7-3)
 */
export interface SessionViewContext {
  /** 명부(방송 목록)가 이 방송을 ended 로 답했다 — 종료 판정이 났으니 같은 회차가 이어지지 않는다 */
  rosterEnded: boolean;
}

/** 명부가 ended 라고 답하지 않은 보기 재료 — 명부를 모르는 호출의 기본값이다 */
export const ROSTER_NOT_ENDED: SessionViewContext = { rosterEnded: false };

type EndingPhase = Extract<SessionPhase, { kind: 'ending-wait' }>;

/**
 * 새로 서는 재생기(세대가 올라 다시 서는 것 · 리마운트 포함)를 세우지 않는가 — 막힌 끝남
 * 대기 · 마지막 확인 중인 끝남 대기(POK-528 U2), 그리고 재생이 멎은 다시보기 제안(POK-547 —
 * 멈춘 화면 위 덮개 단추가 출구다)과 지금 재생기가 받기를 멈춘 다시보기 제안(새 재생기가 낡은
 * 목록을 다시 받지 않게). 되감기는 404 뒤 목록을 다시 받는 중에도 세운다(다시 받기가
 * 곧 새 재생기다 — 계획 3-6). 화면에 그릴 것이 아니라 재생기 수명 지시다. 국면 데이터에서
 * 뽑으므로 세대가 오를 때마다 같은 값을 다시 읽는다
 */
export function isPlayerStartHeld(session: PlaybackSessionState): boolean {
  const { phase } = session;
  if (phase.kind === 'vod-offered') return phase.stopped !== null || loadStoppedNow(session);
  return phase.kind === 'ending-wait' && (phase.blocked === true || phase.finalCheck === true);
}

/**
 * 받기를 멈춘 되감기 재생기가 고갈을 볼 때인가(POK-547) — 되감기 재생기로 아직 트는 다시보기
 * 제안이다. 칸이 아니라 함수라 재생기는 다시 그리기를 기다리지 않고 지금 상태로 읽는다
 */
export function watchesDrain(session: PlaybackSessionState): boolean {
  const { phase } = session;
  return phase.kind === 'vod-offered' && phase.stopped === null && session.source.kind === 'rewind';
}

/**
 * 끝남 대기의 덮개 — 막혔거나 마지막 확인 중이면 끝까지 볼 수 없음이 정해졌으니 재접속 대기
 * 안이어도 「다시보기 준비 중」(POK-528 (나)-5 · W6), 끝에 닿았으면 재접속 대기 동안 「확인 중」 ·
 * 그 뒤 「준비 중」. 명부가 ended 면 이어지지 않으니 재접속 대기 안이어도 「준비 중」(POK-612)
 */
function endingCover(
  phase: EndingPhase,
  waiting: boolean,
  rosterEnded: boolean,
): SessionView['cover'] {
  if (phase.blocked || phase.finalCheck) return 'preparing';
  if (!phase.atEnd) return null;
  return waiting && !rosterEnded ? 'checking' : 'preparing';
}

/** 끝남 대기의 단추 — 버튼 규칙이 제안한 것뿐이다. 제안이 없으면 없다(녹화 출구 없음 · POK-547) */
function offerActions(session: PlaybackSessionState): SessionButton[] {
  if (session.offer?.kind === 'go-live') return ['go-live'];
  if (session.offer?.kind === 'new-broadcast') return ['new-broadcast'];
  return [];
}

export function sessionView(
  session: PlaybackSessionState,
  now: number,
  context: SessionViewContext = ROSTER_NOT_ENDED,
): SessionView {
  const base: SessionView = {
    cover: null,
    actions: [],
    banner: session.foreignLive === null ? null : { goTo: session.foreignLive },
    seekHidden: false,
    liveChecking: session.liveChecking,
    vodReady: false,
    startHeld: isPlayerStartHeld(session),
    resumeWaiting: false,
  };
  const phase = session.phase;
  if (phase.kind === 'ending-wait') {
    const waiting = session.endingSince !== null && now - session.endingSince < RESUME_WAIT_MS;
    return {
      ...base,
      cover: endingCover(phase, waiting, context.rosterEnded),
      actions: offerActions(session),
      seekHidden: phase.atEnd && phase.origin === 'live',
      resumeWaiting: waiting,
    };
  }
  if (phase.kind === 'vod-offered') {
    // 끝에 닿았거나 재생이 멎었으면 덮개 단추, 아직 틀면 띠 단추다
    const covered = phase.atEnd || phase.stopped !== null;
    return {
      ...base,
      cover: covered ? 'vod-ready' : null,
      actions: ['vod'],
      seekHidden: phase.atEnd && session.source.kind === 'live',
      vodReady: !covered,
    };
  }
  return base;
}
