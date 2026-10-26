import { RESUME_WAIT_MS, type PlaybackSessionState, type SessionButton } from './rewindSwitch';

// 재생 세션이 화면에 그릴 것(설계 5.3절 종결 판, POK-200 M5) — 상태기계(rewindSwitch)의 상태에서 덮개 · 버튼 · 띠 · 숨김을 뽑는
// 순수 함수다. 전이는 rewindSwitch 가 정하고, 여기는 그 상태를 읽기만 한다.

export interface SessionView {
  /** 마지막 프레임 위 덮개. checking = 재접속 대기 동안 「방송이 이어지는지 확인하고 있어요」 · preparing = 그 뒤 「다시보기 준비 중」 · vod-ready = 「다시보기 보기」 */
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
  };
  const phase = session.phase;
  if (phase.kind === 'ending-wait') {
    const waiting = session.endingSince !== null && now - session.endingSince < RESUME_WAIT_MS;
    return {
      ...base,
      cover: phase.atEnd ? (waiting ? 'checking' : 'preparing') : null,
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
