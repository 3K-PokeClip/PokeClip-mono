import type { RewindStatusResult } from '@/api/rewindStatus';
import { movedDoor, requestProbe, withNextDoor } from './liveHandoff';
import type {
  Offer,
  PlaybackSessionState,
  SeekView,
  SessionButton,
  SessionEvent,
  SessionPhase,
  StreamPollEvent,
  Transition,
} from './rewindSwitch';
import {
  ENDING_WAIT_MS,
  RESUME_WAIT_MS,
  close,
  liveFromAway,
  readyLive,
  replace,
  seekInList,
  seekLive,
  stateOnly,
  within,
  withUrl,
} from './sessionShared';

// 재생 세션 상태기계의 끝남 대기 표(rewindSwitch.ts 에서 옮김 · POK-200 c13m — 줄 수
// 800 처분). 동작은 옮기기 전과 같다.

type EndingPhase = Extract<SessionPhase, { kind: 'ending-wait' }>;

export function onEnding(
  session: PlaybackSessionState,
  phase: EndingPhase,
  event: SessionEvent,
  now: number,
): Transition {
  switch (event.type) {
    case 'stream-poll': {
      const fromRewind = phase.origin === 'rewind';
      const doored = fromRewind ? withNextDoor(session, event) : session;
      if (phase.recordOnEnd) return stateOnly(doored);
      return onEndingStreamPoll(doored, phase, event, now);
    }
    case 'round-poll':
      return phase.recordOnEnd
        ? stateOnly(session)
        : onEndingRoundPoll(session, phase, event.result, now);
    case 'end-reached':
    case 'live-closed':
      if (phase.atEnd) return stateOnly(session);
      if (phase.recordOnEnd) return close(session, 'recorded');
      return stateOnly({ ...session, phase: { ...phase, atEnd: true } });
    case 'tick':
      if (session.endingSince === null || now - session.endingSince < ENDING_WAIT_MS) {
        return stateOnly(session);
      }
      return roundFinal(session, phase);
    case 'roster-ended':
      return stateOnly({ ...session, rosterEnded: true });
    case 'button':
      return onEndingButton(session, event.which);
    case 'seek':
      return onEndingSeek(session, phase, event.targetMs, event.view);
    case 'live-button':
      return phase.atEnd ? stateOnly(session) : liveFromAway(session, event.x, event.attempt);
    default:
      return stateOnly(session);
  }
}

/**
 * 이음 지점 — rewind 기원이면 atEnd ? E : P, live 기원이면 lastAtMs. url 이 바뀌어
 * list 가 비었으면 E 대신 lastAtMs(T12)
 */
function joinPoint(session: PlaybackSessionState, phase: EndingPhase): number | null {
  if (phase.origin !== 'rewind' || !phase.atEnd) return session.lastAtMs;
  return session.list?.endMs ?? session.lastAtMs;
}

function onEndingStreamPoll(
  session: PlaybackSessionState,
  phase: EndingPhase,
  event: StreamPollEvent,
  now: number,
): Transition {
  // E1 — 이미 본 url 을 거르기 전에 본다. 문이 열렸고 넘김 조건이면 탐침을 청한다(넘김은 탐침 결과가 한다)
  const probing = requestProbe(session, now);
  if (probing !== null) return probing;
  const currentX = event.x;
  const live = readyLive(event.result);
  if (live === null || session.roundRewindUrls.has(live.url)) return stateOnly(session);
  const sameRound = currentX?.kind === 'known' && currentX.id === session.roundId;
  // 되감기의 자동 이음은 같은 방송(X = 이 회차)이 끊겼다 돌아온 경우뿐이다 — 다른 방송 · 모름은 버튼 규칙으로
  const joinable = phase.origin === 'live' || sameRound;
  const join = joinPoint(session, phase);
  if (joinable && within(join, live.firstPdtMs, live.headPdtMs)) {
    const resumed = {
      ...session,
      roundRewindUrls: withUrl(session.roundRewindUrls, live.url),
      endingSince: null,
      offer: null,
      rosterEnded: false,
      entry: { url: live.url, firstPdtMs: live.firstPdtMs, headPdtMs: live.headPdtMs },
    };
    if (phase.origin === 'rewind') {
      return stateOnly({
        ...resumed,
        phase: { kind: 'rewind', pendingNext: null },
        source: replace(session, 'rewind', live.url, join),
        handoffDoor: movedDoor(session, live.url, currentX, event.ask),
      });
    }
    return stateOnly({
      ...resumed,
      phase: { kind: 'live' },
      source: replace(session, 'live', session.liveUrl, null),
    });
  }
  let offer: Offer | null = null;
  if (sameRound) offer = { kind: 'go-live' };
  else if (currentX?.kind === 'known' && currentX.id !== '') {
    offer = { kind: 'new-broadcast', goTo: currentX.id };
  }
  return stateOnly({ ...session, offer });
}

function onEndingRoundPoll(
  session: PlaybackSessionState,
  phase: EndingPhase,
  result: RewindStatusResult,
  now: number,
): Transition {
  if (result.kind === 'none') {
    const waited = session.endingSince !== null && now - session.endingSince >= RESUME_WAIT_MS;
    return waited ? roundFinal(session, phase) : stateOnly(session);
  }
  if (result.kind !== 'ok') return stateOnly(session);
  const { status } = result;
  if (status.mode === 'ended') return roundFinal(session, phase);
  if (status.mode !== 'vod' || status.url === null) return stateOnly(session);
  if (status.firstPdtMs === null || status.headPdtMs === null) return stateOnly(session);
  // 세션이 본 구간 [watchedFromMs, observedEndMs] 과 겹쳐야 그 회차의 문서다(다른 회차 문서 방어 · R46)
  const seenFrom = session.watchedFromMs;
  const seenEnd = session.observedEndMs;
  if (seenFrom === null || seenEnd === null) return stateOnly(session);
  if (status.firstPdtMs > seenEnd || status.headPdtMs < seenFrom) return stateOnly(session);
  return stateOnly({
    ...session,
    phase: {
      kind: 'vod-offered',
      url: status.url,
      firstPdtMs: status.firstPdtMs,
      headPdtMs: status.headPdtMs,
      atEnd: phase.atEnd,
    },
    offer: null,
  });
}

/** 회차 종국(회차별 ended · 재접속 대기 뒤 404 · 20분 시한) — 끝에 닿았으면 녹화로, 아니면 끝까지 본 뒤 녹화로 */
function roundFinal(session: PlaybackSessionState, phase: EndingPhase): Transition {
  if (phase.atEnd) return close(session, 'recorded');
  if (phase.recordOnEnd) return stateOnly(session);
  return stateOnly({ ...session, phase: { ...phase, recordOnEnd: true } });
}

function onEndingButton(session: PlaybackSessionState, which: SessionButton): Transition {
  if (which === 'record') return close(session, 'recorded');
  if (which === 'go-live' && session.offer?.kind === 'go-live') return close(session, 'go-live');
  if (which === 'new-broadcast' && session.offer?.kind === 'new-broadcast') {
    return close(session, 'left', session.offer.goTo);
  }
  // 세션에 묶인 live 가 띠(다른 방송)를 세운 채 끝남 대기로 왔다 — 띠의 단추도 그 방송으로 닫는다
  if (which === 'new-broadcast' && session.foreignLive !== null) {
    return close(session, 'left', session.foreignLive);
  }
  return stateOnly(session);
}

function onEndingSeek(
  session: PlaybackSessionState,
  phase: EndingPhase,
  targetMs: number,
  view: SeekView,
): Transition {
  if (phase.origin === 'live') {
    if (phase.atEnd) return stateOnly(session);
    return seekLive(session, targetMs, view);
  }
  const moved = seekInList(session, targetMs, view);
  if (!phase.atEnd) return moved;
  return { state: { ...moved.state, phase: { ...phase, atEnd: false } }, effects: moved.effects };
}
