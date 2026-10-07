import { KEY_SUSPECT_HINT, reasonText } from './copy';
import { formatCountdown } from './format';
import type { BridgeState } from './types';

export type Tone = 'ready' | 'live' | 'warning' | 'danger' | undefined;

// 상태 카드가 그릴 내용 — 단계·재시도 진행·버튼 조건을 한곳에서 정한다(컴포넌트 밖이라 시험할 수 있다).
export interface StatusView {
  title: string;
  desc: string;
  tone: Tone;
  alert: string; // 원인 안내(거절이 이어지면 키 확인 안내). 없으면 빈 문자열
  retrying: boolean; // 자동 재시도 중 — 기다리는 중이거나 접속 중
  retryText: string; // 「12초 뒤 다시 시도해요」 · 접속 중이면 「다시 연결하는 중이에요」
  retryCount: string; // 「3번째」 — 끊긴 뒤 몇 번째 재시도인가
  canRetryNow: boolean; // 기다리는 중이라 건너뛸 수 있다 — 「다시 시도」
  canSendNow: boolean; // 멈춰 있고 본방은 나간다 — 「다시 연결」
}

const READY_TITLE = '준비됐어요';
const READY_DESC = 'OBS에서 방송을 시작하면 PokeClip으로 함께 전송돼요.';

// 플러그인 재시도 정책의 포기 시각(src/retry-policy.hpp kRetryGiveUpMs)과 같은 값이다.
const GAVE_UP = '65분 동안 붙지 못해 멈췄어요.';

export function statusView(state: BridgeState, nowMs: number): StatusView {
  const { phase, retry } = state;
  const retrying = retry.attempt > 0 && (phase === 'starting' || phase === 'reconnecting');
  const view: StatusView = {
    title: READY_TITLE,
    desc: READY_DESC,
    tone: 'ready',
    alert: '',
    retrying,
    retryText: '',
    retryCount: '',
    canRetryNow: false,
    canSendNow: false,
  };

  if (retrying) {
    const waiting = retry.nextAt > 0;
    const wasLive = phase === 'reconnecting'; // 붙어 있다 끊겼다 / 처음부터 붙지 못했다
    view.title = wasLive ? '재연결 중' : '연결 재시도 중';
    // 원인은 아래 상자가, 진행은 재시도 줄이 말한다 — 설명은 스트리머가 가장 궁금한 한 가지만 남긴다
    view.desc = '본방은 그대로 나가고 있어요.';
    view.tone = 'warning';
    // 거절이 이어지면 원인 자리에 키 확인 안내를 대신 싣는다 — 문단을 하나 더 쌓지 않는다
    view.alert = retry.keySuspect ? KEY_SUSPECT_HINT : state.errorCode ? reasonText(state.errorCode) : '';
    // 「언제 무엇을 하는지」를 문장으로 쓴다. 횟수는 덜 중요해서 따로 떼어 작게 보여 준다
    view.retryText = waiting ? `${formatCountdown(retry.nextAt - nowMs)} 다시 시도해요` : '다시 연결하는 중이에요';
    view.retryCount = `${retry.attempt}번째`;
    view.canRetryNow = waiting;
    return view;
  }

  switch (phase) {
    case 'live':
      view.title = 'PokeClip으로 전송 중';
      view.desc = '본방과 같은 인코더로 함께 보내고 있어요.';
      view.tone = 'live';
      return view;
    // 'reconnecting'은 여기 없다 — 플러그인은 그 단계를 재시도 정보(retry.attempt ≥ 1)와 함께만 보내 위에서 끝난다
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
    // 거절이 이어지다 재시도를 멈췄거나 포기했으면 키 확인 안내를 남긴다 — 다음에 할 일이 그것이다
    view.alert = retry.keySuspect ? KEY_SUSPECT_HINT : reasonText(state.errorCode);
    view.canSendNow = state.canSendNow;
    const next = state.canSendNow
      ? '본방은 그대로 두고 다시 연결할 수 있어요.'
      : state.errorCode === 'invalid_key'
        ? '새 코드를 입력하면 다시 전송할 수 있어요.' // 본방을 다시 켜도 같다 — 키를 다시 받아야 한다
        : state.obsStreaming
        ? '본 방송을 다시 시작해야 전송할 수 있어요.'
        : '다음 방송을 시작하면 다시 전송해요.';
    view.desc = retry.gaveUp ? `${GAVE_UP} ${next}` : next;
    return view;
  }

  // 대기 — 본방이 나가는데 우리만 안 보내고 있으면(방송 중에 페어링했다 등) 버튼으로 시작하게 한다.
  if (state.canSendNow) {
    view.title = 'PokeClip 전송이 꺼져 있어요';
    view.desc = '「다시 연결」을 누르면 지금부터 함께 보내요.';
    view.tone = 'warning';
    view.canSendNow = true;
  }
  return view;
}
