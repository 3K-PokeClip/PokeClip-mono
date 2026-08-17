import clsx from 'clsx';
import styles from './GlassPlayer.module.css';
import type { SimChatMessage } from './useSimulatedChat';

// 우하단 반투명 유리 채팅 말풍선 — 장식성 오버레이라 보조기술에는 숨긴다.
export function PlayerChatOverlay({ messages }: { messages: SimChatMessage[] }) {
  return (
    <div className={styles.chatOverlay} aria-hidden>
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
