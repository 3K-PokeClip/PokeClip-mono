import { reasonText } from './copy';
import { formatCountdown } from './format';
import type { BridgeState } from './types';

export type Tone = 'ready' | 'live' | 'warning' | 'danger' | undefined;

// 상태 카드가 그릴 내용 — 단계·재시도 진행·버튼 조건을 한곳에서 정한다(컴포넌트 밖이라 시험할 수 있다).
export interface StatusView {
  title: string;
  desc: string;
  tone: Tone;
  alert: string; // 원인 안내. 없으면 빈 문자열
  retrying: boolean; // 자동 재시도 중 — 기다리는 중이거나 접속 중
  retryLine: string; // 「재시도 3번째 · 12초 뒤」
  canRetryNow: boolean; // 기다리는 중이라 건너뛸 수 있다 — 「지금 다시 시도」
  canSendNow: boolean; // 멈춰 있고 본방은 나간다 — 「지금 보내기」
  keyHint: boolean; // 키 상태 확인 안내를 덧붙인다
}

const READY_TITLE = '준비됐어요';
const READY_DESC = 'OBS에서 방송을 시작하면 PokeClip으로 함께 전송돼요.';

// 플러그인 재시도 정책의 포기 시각(src/retry-policy.hpp kRetryGiveUpMs)과 같은 값이다.
const GAVE_UP = '65분 동안 다시 시도했지만 붙지 못했어요.';

export function statusView(state: BridgeState, nowMs: number): StatusView {
  const { phase, retry } = state;
  const retrying = retry.attempt > 0 && (phase === 'starting' || phase === 'reconnecting');
  const view: StatusView = {
    title: READY_TITLE,
    desc: READY_DESC,
    tone: 'ready',
    alert: '',
    retrying,
    retryLine: '',
    canRetryNow: false,
    canSendNow: false,
    keyHint: false,
  };

  if (retrying) {
    const waiting = retry.nextAt > 0;
    const wasLive = phase === 'reconnecting'; // 붙어 있다 끊겼다 / 처음부터 붙지 못했다
    view.title = wasLive ? '재연결 중' : '연결 재시도 중';
    view.desc = wasLive
      ? '연결이 끊겨 자동으로 다시 붙는 중이에요. 본방은 그대로 나가요.'
      : 'PokeClip 수신 서버에 아직 붙지 못했어요. 자동으로 다시 시도해요.';
    view.tone = 'warning';
    view.alert = state.errorCode ? reasonText(state.errorCode) : '';
    view.retryLine = `재시도 ${retry.attempt}번째 · ${waiting ? formatCountdown(retry.nextAt - nowMs) : '접속 중'}`;
    view.canRetryNow = waiting;
    view.keyHint = retry.keySuspect;
    return view;
  }

  switch (phase) {
    case 'live':
      view.title = 'PokeClip으로 전송 중';
      view.desc = '본방과 같은 인코더로 함께 보내고 있어요.';
      view.tone = 'live';
      return view;
    case 'reconnecting':
      view.title = '재연결 중';
      view.desc = '연결이 끊겨 자동으로 다시 붙는 중이에요. 본방은 그대로 나가요.';
      view.tone = 'warning';
      return view;
    case 'starting':
      view.title = '연결 중';
      view.desc = 'PokeClip 수신 서버에 붙는 중이에요.';
      view.tone = undefined;
      return view;
    case 'stopping':
      view.title = '정지 중';
      view.desc = '본방과 함께 전송을 마무리하고 있어요.';
      view.tone = undefined;
      return view;
    default:
      break;
  }

  if (phase === 'error' && state.errorCode) {
    view.title = '전송이 멈췄어요';
    view.tone = 'danger';
    view.alert = reasonText(state.errorCode);
    view.canSendNow = state.canSendNow;
    const next = state.canSendNow
      ? '본방은 그대로 두고 PokeClip 전송만 다시 시작할 수 있어요.'
      : state.obsStreaming
        ? '본 방송을 다시 시작해야 전송할 수 있어요.'
        : '다음 방송을 시작하면 다시 전송해요.';
    view.desc = retry.gaveUp ? `${GAVE_UP} ${next}` : next;
    return view;
  }

  // 대기 — 본방이 나가는데 우리만 안 보내고 있으면(방송 중에 페어링했다 등) 버튼으로 시작하게 한다.
  if (state.canSendNow) {
    view.title = 'PokeClip 전송이 꺼져 있어요';
    view.desc = '본방은 나가고 있어요. 「지금 보내기」를 누르면 지금부터 함께 전송해요.';
    view.tone = 'warning';
    view.canSendNow = true;
  }
  return view;
}
