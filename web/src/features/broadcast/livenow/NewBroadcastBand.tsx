import styles from './LiveScreen.module.css';

/**
 * 「새 방송이 시작됐어요」 띠(POK-200 M5 · 설계 5.5절) — 재생 보유 중 같은 채널에서 다른 방송이 시작됐다. 보던 재생을 끊지 않으려고
 * 자동으로 넘어가지 않고 알리기만 한다. 누르면 세션을 닫고 보유를 풀어 그 방송으로 간다
 */
export function NewBroadcastBand({ onGo }: { onGo: () => void }) {
  return (
    <div className={styles.newBroadcastBand}>
      <span role="status">새 방송이 시작됐어요</span>
      <button type="button" onClick={onGo}>
        보러 가기
      </button>
    </div>
  );
}
