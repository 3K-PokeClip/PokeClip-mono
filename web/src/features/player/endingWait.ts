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
  isThisRound,
  liveFromAway,
  openVodHere,
  readyLive,
  replace,
  resumeAtMs,
  seekInList,
  seekLive,
  stateOnly,
  within,
  withUrl,
  type RewindRange,
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
      if (phase.closeOnEnd) return stateOnly(doored);
      return onEndingStreamPoll(doored, phase, event, now);
    }
    case 'round-poll':
      if (phase.finalCheck) return onFinalCheckRoundPoll(session, event.result);
      return phase.closeOnEnd
        ? stateOnly(session)
        : onEndingRoundPoll(session, phase, event.result, now);
    case 'end-reached':
    case 'live-closed':
      if (phase.atEnd) return stateOnly(session);
      if (phase.closeOnEnd) return close(session, 'past');
      return stateOnly({ ...session, phase: { ...phase, atEnd: true } });
    case 'tick':
      if (session.endingSince === null || now - session.endingSince < ENDING_WAIT_MS) {
        return stateOnly(session);
      }
      return roundFinal(session, phase);
    case 'button':
      return onEndingButton(session, event.which);
    case 'seek':
      // 막힌 동안 · 마지막 확인 중 시크는 무시한다 — 재생기가 받기를 멈췄고 복구는 세션 효과만 한다(POK-528 P15)
      if (phase.blocked || phase.finalCheck) return stateOnly(session);
      return onEndingSeek(session, phase, event.targetMs, event.view);
    case 'live-button':
      return phase.atEnd ? stateOnly(session) : liveFromAway(session, event.x, event.attempt);
    case 'dvr-segment-gone':
      return onEndingSegmentGone(session, phase);
    default:
      return stateOnly(session);
  }
}

/**
 * 이음 지점 — rewind 기원이면 atEnd ? E : 이어 볼 자리(resumeAtMs — 첫 위치 전이면 시크 목표 · 청한 자리 ·
 * POK-528 계획 3-4), live 기원이면 lastAtMs. url 이 바뀌어 list 가 비었으면 E 대신 lastAtMs(T12)
 */
function joinPoint(session: PlaybackSessionState, phase: EndingPhase): number | null {
  if (phase.origin !== 'rewind') return session.lastAtMs;
  if (!phase.atEnd) return resumeAtMs(session);
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
  const sameRound = isThisRound(session, currentX);
  // 되감기의 자동 이음은 같은 방송(X = 이 회차)이 끊겼다 돌아온 경우뿐이다 — 다른 방송 · 모름은 버튼 규칙으로.
  // 막힌 세션은 잇지 않는다 — 회차별 조회의 겹치는 vod · 종국 · 20분과 사람 단추(라이브 보기 · 새 방송 보기 — 제안됐을
  // 때)로만 풀린다(POK-528 U1)
  const joinable = !phase.blocked && (phase.origin === 'live' || sameRound);
  const join = joinPoint(session, phase);
  if (joinable && within(join, live.firstPdtMs, live.headPdtMs)) {
    const resumed = {
      ...session,
      roundRewindUrls: withUrl(session.roundRewindUrls, live.url),
      endingSince: null,
      offer: null,
      entry: { url: live.url, firstPdtMs: live.firstPdtMs, headPdtMs: live.headPdtMs },
    };
    if (phase.origin === 'rewind') {
      return stateOnly({
        ...resumed,
        phase: { kind: 'rewind', pendingNext: null },
        source: replace(session, 'rewind', live.url, join),
        handoffDoor: movedDoor(live.url, event.ask),
      });
    }
    return stateOnly({
      ...resumed,
      phase: { kind: 'live' },
      source: replace(session, 'live', session.liveUrl, null),
    });
  }
  // 되감기 기원의 「새 방송 보기」는 끝에 닿은 뒤에만 — 다른 방송은 끝까지 두고 끝 도달 뒤 제안한다(kty K1 확장 (가)).
  // 막힌 세션은 끝까지 볼 수 없으니 곧바로 제안한다(POK-528 W7)
  const offersNew = phase.origin === 'live' || phase.atEnd || phase.blocked === true;
  let offer: Offer | null = null;
  if (sameRound) offer = { kind: 'go-live' };
  else if (offersNew && currentX?.kind === 'known' && currentX.id !== '') {
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
  if (result.kind === 'ok' && result.status.mode === 'ended') return roundFinal(session, phase);
  const vod = seenRoundVod(session, result);
  if (vod === null) return stateOnly(session);
  // 막힌 세션은 제안을 거치지 않고 같은 자리 다시보기로 옮긴다 — 끝까지 볼 수 없으니 기다릴 것이 없다(POK-528 W5)
  if (phase.blocked) return openVodHere(session, vod);
  return stateOnly({
    ...session,
    phase: { kind: 'vod-offered', ...vod, atEnd: phase.atEnd },
    offer: null,
  });
}

/**
 * 마지막 확인(POK-528 W6 · E76) — 닫기로 정한 뒤 404 로 끝에 닿을 수 없게 된 세션의 회차별
 * 답 하나로 끝낸다. 본 구간과 겹치는 vod 면 같은 자리 다시보기, 그 밖(ending · ended · 404 ·
 * 잠시 불가 · 요청 시한 · 겹침 불통과)이면 곧바로 닫는다(past) — 두 번째 확인은 없다
 */
function onFinalCheckRoundPoll(
  session: PlaybackSessionState,
  result: RewindStatusResult,
): Transition {
  const vod = seenRoundVod(session, result);
  return vod === null ? close(session, 'past') : openVodHere(session, vod);
}

/**
 * 회차별 답이 세션이 본 구간 [watchedFromMs, observedEndMs] 과 겹치는 다시보기면 그 범위 —
 * 겹쳐야 그 회차의 문서다(다른 회차 문서 방어 · R46). 그 밖이면 null
 */
function seenRoundVod(
  session: PlaybackSessionState,
  result: RewindStatusResult,
): RewindRange | null {
  if (result.kind !== 'ok') return null;
  const { mode, url, firstPdtMs, headPdtMs } = result.status;
  if (mode !== 'vod' || url === null || firstPdtMs === null || headPdtMs === null) return null;
  const seenFrom = session.watchedFromMs;
  const seenEnd = session.observedEndMs;
  if (seenFrom === null || seenEnd === null) return null;
  if (firstPdtMs > seenEnd || headPdtMs < seenFrom) return null;
  return { url, firstPdtMs, headPdtMs };
}

/**
 * 회차 종국(회차별 ended · 재접속 대기 뒤 404 · 20분 시한) — 끝에 닿았거나 막혔으면(끝까지 볼 수 없다 · POK-528 W5)
 * 곧바로, 아니면 끝까지 본 뒤 세션을 닫는다(past — 지난 방송 판정으로 넘김 · POK-547)
 */
function roundFinal(session: PlaybackSessionState, phase: EndingPhase): Transition {
  if (phase.atEnd || phase.blocked) return close(session, 'past');
  if (phase.closeOnEnd) return stateOnly(session);
  return stateOnly({ ...session, phase: { ...phase, closeOnEnd: true } });
}

/**
 * 되감기 조각 404(POK-528 계획 3-2) — 이 목록을 끝까지 재생할 수 없으니 지금 프레임에서 멈춘다.
 * 닫기로 정하기 전이면 막고, 닫기로 정한 뒤면 끝 도달을 기다릴 수 없으니 회차별 조회를 마지막으로
 * 한 번 한다(W6). 그 물음은 보통 pollTargets 가 회차별 경로를 켜는 렌더에서 곧바로 나간다. 다만
 * 경로가 꺼졌다 켜지는 일이 한 렌더 묶음에 겹쳐 켜짐이 렌더에 드러나지 않으면, 이미 걸려 있던
 * 다음 정기 물음(REWIND_POLL_MS 주기)이 그 물음이 된다. 이미 막혔거나 마지막 확인 중이면 그대로다
 */
function onEndingSegmentGone(session: PlaybackSessionState, phase: EndingPhase): Transition {
  if (phase.blocked || phase.finalCheck) return stateOnly(session);
  const next: EndingPhase = phase.closeOnEnd
    ? { ...phase, finalCheck: true }
    : { ...phase, blocked: true };
  return { state: { ...session, phase: next }, effects: [{ kind: 'hold-frame' }] };
}

function onEndingButton(session: PlaybackSessionState, which: SessionButton): Transition {
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
