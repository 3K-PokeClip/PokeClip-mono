import { seenDrainedAt } from './liveHandoff';
import type { PlaybackSessionState, SessionEvent, SessionPhase, Transition } from './rewindSwitch';
import {
  closePast,
  liveFromAway,
  offeredRange,
  seekInList,
  seekLive,
  stateOnly,
} from './sessionShared';

// 재생 세션 상태기계의 다시보기 제안 표(rewindSwitch.ts 에서 옮김 · POK-547 — 줄 수
// 800 처분). 동작은 옮기기 전과 같다.

type OfferedPhase = Extract<SessionPhase, { kind: 'vod-offered' }>;

export function onVodOffered(
  session: PlaybackSessionState,
  phase: OfferedPhase,
  event: SessionEvent,
  origin: 'live' | 'rewind',
): Transition {
  switch (event.type) {
    case 'button':
      return event.which === 'vod'
        ? closePast(session, offeredRange(phase), { atEnd: phase.atEnd, chosen: true })
        : stateOnly(session);
    case 'end-reached':
      return phase.atEnd
        ? stateOnly(session)
        : stateOnly({ ...session, phase: { ...phase, atEnd: true } });
    case 'seek':
      // 막힘 · 마지막 확인에서 멈춘 그대로 온 제안은 시크를 받지 않는다 — 재생기가 받기를 멈췄다(POK-528)
      if (phase.stopped === 'held') return stateOnly(session);
      if (phase.atEnd) {
        if (origin === 'live') return stateOnly(session);
        const moved = seekInList(session, event.targetMs, event.view);
        return {
          state: { ...moved.state, phase: { ...phase, atEnd: false } },
          effects: moved.effects,
        };
      }
      return origin === 'live'
        ? seekLive(session, event.targetMs, event.view)
        : seekInList(session, event.targetMs, event.view);
    case 'dvr-segment-gone':
      // W4 — 받기만 멈추고 받은 버퍼를 계속 튼다(받기 중단은 dvr-load-stopped 가 적었다). 다시보기로 옮기는 것은 단추다
      return stateOnly(session);
    case 'dvr-drain':
      return offerDrain(session, phase, event);
    case 'live-button':
      if (phase.atEnd) return stateOnly(session);
      // live 기원은 live 표의 LIVE 행 — 라이브 끝으로 시크 · 국면 그대로(종결 정정 live 표 · VOD 제안을 잃지 않는다)
      if (origin === 'live') return { state: session, effects: [{ kind: 'seek-live-edge' }] };
      return liveFromAway(session, event.x, event.attempt);
    default:
      return stateOnly(session);
  }
}

/**
 * 받기를 멈춘 재생기의 고갈 · 회복(POK-547) — 고갈이면 덮개 단추로 바꾸고 멎은 자리를 확인된 위치로 적는다(끝나지 않은
 * 시크가 있으면 그 목표가 이어 볼 자리라 그대로 둔다). 회복이면 띠 단추로 돌린다. 막힘 · 마지막 확인에서 온 제안은 그대로다
 */
function offerDrain(
  session: PlaybackSessionState,
  phase: OfferedPhase,
  event: Extract<SessionEvent, { type: 'dvr-drain' }>,
): Transition {
  if (phase.stopped === 'held') return stateOnly(session);
  if (!event.drained) {
    return phase.stopped === 'drained'
      ? stateOnly({ ...session, phase: { ...phase, stopped: null } })
      : stateOnly(session);
  }
  const seen = seenDrainedAt(session, event.atPdtMs);
  return stateOnly({ ...seen, phase: { ...phase, stopped: 'drained' } });
}
