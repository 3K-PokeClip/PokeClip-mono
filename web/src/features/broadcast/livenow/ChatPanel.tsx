'use client';

import { memo } from 'react';
import clsx from 'clsx';
import { MessageSquare, PanelRightClose } from 'lucide-react';
import { Badge, IconButton, Tag } from '@/ui';
import styles from './LiveScreen.module.css';
import type { ChatPanelMessage, ChatSurge } from './useChatPanelMockState';

// 실시간 채팅 패널(시안 1b) — 급증 키워드 · 후원 강조 · 하이라이트 감지 알림 · 하단 상태줄.
// 시안 갱신(2026-09-11, POK-239): 패널은 화면 높이에 sticky로 붙고(CSS), 목록은 자체 스크롤.
// 접으면 패널이 사라지고 플레이어 상단의 「채팅 열기」가 되살린다(시안의 세로 레일은 사용자
// 결정으로 쓰지 않는다).
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

export const ChatPanel = memo(function ChatPanel({
  surges,
  messages,
  ratePerMinute,
  collectionWarning,
  onCollapse,
}: {
  surges: ChatSurge[];
  /** 오래된 → 최신 순. 그리기는 뒤집는다(아래 주석) — 데이터 순서는 실연동 계약 몫이라 여기서 안 건드린다 */
  messages: ChatPanelMessage[];
  ratePerMinute: number;
  /** 수집이 끊겼는가 — 동결 계약의 chatWarning을 그대로 받는다 */
  collectionWarning: boolean;
  onCollapse: () => void;
}) {
  // 목록은 column-reverse다(시안) — 시각 순서가 DOM의 역순이라 최신을 DOM 맨 앞에 둬야
  // 화면 아래에 선다. 스크롤 원점도 그쪽이라 새 줄이 와도 보던 자리가 흔들리지 않는다.
  const newestFirst = messages.slice().reverse();

  return (
    <aside className={styles.chatPanel} aria-label="실시간 채팅">
      <div className={styles.chatHeader}>
        <MessageSquare size={15} aria-hidden className={styles.chatHeaderIcon} />
        <span className={styles.chatHeading}>실시간 채팅</span>
        {/* 배지를 갈아끼우는 게 아니라 늘 서 있는 리전 안에서 글만 바꾼다 —
            지운 경고 배너가 갖고 있던 role="status"를 여기가 승계한다. 없으면 수집이
            끊기는 순간을 눈으로 보는 사람만 알고, 듣는 사람은 헤더로 돌아와야 안다. */}
        <span role="status">
          {collectionWarning ? (
            <Badge tone="warning" variant="soft" size="sm">
              수집 끊김
            </Badge>
          ) : (
            <Badge tone="success" variant="soft" size="sm">
              수집 중
            </Badge>
          )}
        </span>
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
      <ul className={styles.chatList}>
        {newestFirst.map((message) => (
          <ChatLine key={message.id} message={message} />
        ))}
      </ul>
      {/* 끊겼는데 「따라가는 중」이라 하면 헤더 배지와 한 화면에서 모순된다 — 문구도 같이 바꾼다 */}
      <div className={styles.chatFooter}>
        <span
          className={clsx(styles.chatDot, collectionWarning && styles.chatDotWarning)}
          aria-hidden
        />
        <span>
          {collectionWarning
            ? '수집 끊김 · 새 메시지 없음'
            : `최신 메시지 따라가는 중 · 분당 ${ratePerMinute}`}
        </span>
        {/* 키워드 설정 화면은 라우트가 없다 — 「제목 수정」(StreamInfoBar)처럼 자리만 지킨다 */}
        <button type="button" className={styles.chatFooterLink} disabled>
          키워드 설정
        </button>
      </div>
    </aside>
  );
});
