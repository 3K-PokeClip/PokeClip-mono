'use client';

import { MessageSquare, PanelRightClose } from 'lucide-react';
import { Badge, IconButton, Tag } from '@/ui';
import styles from './LiveScreen.module.css';
import type { ChatPanelMessage, ChatSurge } from './useChatPanelMockState';

// 실시간 채팅 패널(시안 1b) — 급증 키워드 · 후원 강조 · 하이라이트 감지 알림.
//
// 수집 상태는 이 헤더의 배지가 말한다(옛 본문 경고 배너에서 옮겨 왔다) — 티켓이 정한 자리이고,
// 채팅이 흐르는 곳 바로 위라 "지금 안 들어오고 있다"가 눈에 들어온다.
// ADR-048에 따라 「다시 하면 복구」 같은 문구는 두지 않는다 — 실연동 뒤 거짓이 된다.

function ChatLine({ message }: { message: ChatPanelMessage }) {
  if (message.kind === 'donation') {
    return (
      <li className={styles.chatDonation}>
        <span className={styles.chatDonationHead}>
          {message.name} · {message.amountLabel}
        </span>
        <span className={styles.chatDonationText}>{message.text}</span>
      </li>
    );
  }
  if (message.kind === 'system') {
    return <li className={styles.chatSystem}>{message.text}</li>;
  }
  return (
    <li className={styles.chatLine}>
      <b className={styles.chatName} data-color={message.colorIndex}>
        {message.name}
      </b>{' '}
      <span className={styles.chatText}>{message.text}</span>
    </li>
  );
}

export function ChatPanel({
  surges,
  messages,
  collectionWarning,
  onCollapse,
}: {
  surges: ChatSurge[];
  messages: ChatPanelMessage[];
  /** 수집이 끊겼는가 — 동결 계약의 chatWarning을 그대로 받는다 */
  collectionWarning: boolean;
  onCollapse: () => void;
}) {
  return (
    <aside className={styles.chatPanel} aria-label="실시간 채팅">
      <div className={styles.chatHeader}>
        <MessageSquare size={15} aria-hidden className={styles.chatHeaderIcon} />
        <span className={styles.chatHeading}>실시간 채팅</span>
        {collectionWarning ? (
          <Badge tone="warning" variant="soft" size="sm">
            수집 끊김
          </Badge>
        ) : (
          <Badge tone="success" variant="soft" size="sm">
            수집 중
          </Badge>
        )}
        <IconButton
          variant="ghost"
          size="sm"
          aria-label="채팅 패널 접기"
          className={styles.chatCollapse}
          onClick={onCollapse}
        >
          <PanelRightClose size={14} aria-hidden />
        </IconButton>
      </div>
      <div className={styles.chatSurges}>
        <span className={styles.chatSurgeLabel}>급증</span>
        {surges.map((surge) => (
          <Tag key={surge.keyword} variant="soft" size="sm">
            {surge.keyword} ×{surge.count}
          </Tag>
        ))}
      </div>
      {/* 최근 줄이 아래에 쌓인다 — 넘치면 위쪽이 잘린다(시안과 같이 자체 스크롤은 두지 않는다) */}
      <ul className={styles.chatList}>
        {messages.map((message) => (
          <ChatLine key={message.id} message={message} />
        ))}
      </ul>
    </aside>
  );
}
