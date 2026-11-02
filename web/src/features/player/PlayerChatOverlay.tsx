import clsx from 'clsx';
import { REPLAY_BLOCK_CLASS } from '@/lib/analytics';
import styles from './GlassPlayer.module.css';
import type { SimChatMessage } from './useSimulatedChat';

// 우하단 반투명 유리 채팅 말풍선 — 장식성 오버레이라 보조기술에는 숨긴다.
export function PlayerChatOverlay({ messages }: { messages: SimChatMessage[] }) {
  return (
    // 시청자 채팅은 세션 녹화에서 통째로 뺀다 (POK-358)
    <div className={clsx(styles.chatOverlay, REPLAY_BLOCK_CLASS)} aria-hidden>
      {messages.map((message) => (
        <div key={message.id} className={styles.chatBubble}>
          <span className={clsx(styles.chatName, styles[`chatColor${message.colorIndex}`])}>
            {message.name}
          </span>
          <span className={styles.chatText}>{message.text}</span>
        </div>
      ))}
    </div>
  );
}
