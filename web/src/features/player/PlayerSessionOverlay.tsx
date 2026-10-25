'use client';

import styles from './PlayerSessionOverlay.module.css';
import type { SessionButton, SessionView } from './rewindSwitch';

// 재생 세션의 덮개 · 띠(설계 5.3 · 5.5절, POK-200 M5). 끝에 닿은 시청자에게는 마지막 장면 위 덮개, 아직 보는 시청자에게는
// 영상 위쪽 띠로 같은 버튼을 둔다. 자동 전환은 상태기계가 정하고, 여기는 그 결과를 그리고 누름을 올릴 뿐이다.
// 버튼 문구는 2번 통지 범위다(설계 13절 ④ 행).

const COVER_TEXT = {
  checking: '방송이 이어지는지 확인하고 있어요',
  preparing: '다시보기 준비 중',
  'vod-ready': '다시보기 준비됨',
} as const;

const BUTTON_TEXT: Record<SessionButton, string> = {
  record: '녹화 보기',
  vod: '다시보기 보기',
  'go-live': '라이브 보기',
  'new-broadcast': '새 방송 보기',
};

interface PlayerSessionOverlayProps {
  view: SessionView;
  onAction: (which: SessionButton) => void;
}

function Actions({ view, onAction }: PlayerSessionOverlayProps) {
  return (
    <div className={styles.actions}>
      {view.actions.map((which) => (
        <button
          key={which}
          type="button"
          className={styles.action}
          data-emphasis={which === 'record' && view.recordEmphasis ? 'true' : undefined}
          onClick={() => onAction(which)}
        >
          {BUTTON_TEXT[which]}
        </button>
      ))}
    </div>
  );
}

/** 아직 보는 시청자의 띠 문구. 띠가 설 까닭이 없으면 null */
function bandText(view: SessionView): string | null {
  if (view.banner !== null) return '새 방송이 시작됐어요';
  if (view.vodReady) return '다시보기 준비됨';
  if (view.actions.includes('go-live')) return '방송이 끊겼어요';
  if (view.actions.includes('new-broadcast')) return '같은 채널에서 새 방송이 시작됐어요';
  if (view.liveChecking) return '지금 방송을 확인하고 있어요';
  return null;
}

export function PlayerSessionOverlay({ view, onAction }: PlayerSessionOverlayProps) {
  if (view.cover !== null) {
    return (
      <div className={styles.cover}>
        <p className={styles.coverText} role="status">
          {COVER_TEXT[view.cover]}
        </p>
        <Actions view={view} onAction={onAction} />
      </div>
    );
  }
  const text = bandText(view);
  if (text === null) return null;
  return (
    <div className={styles.band}>
      <span role="status">{text}</span>
      {view.banner !== null ? (
        <button type="button" className={styles.action} onClick={() => onAction('new-broadcast')}>
          보러 가기
        </button>
      ) : view.liveChecking ? null : (
        <Actions view={view} onAction={onAction} />
      )}
    </div>
  );
}
