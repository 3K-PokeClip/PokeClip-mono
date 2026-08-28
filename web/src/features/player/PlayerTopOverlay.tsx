import { MessageSquare } from 'lucide-react';
import { Avatar } from '@/ui';
import styles from './GlassPlayer.module.css';

// 플레이어 상단 오버레이 — 채널 필 + 채팅 열기 (시안 영상 플레이어 글래스).
// 뒤로가기는 1b에서 페이지 헤더가 사라진 뒤 사이드 메뉴가 담당하므로 여기엔 없다.
//
// 방송 경과 시간은 여기 두지 않는다 — 1b에서 영상 바로 아래 방송 정보 바가
// 「1:24:03 스트리밍 중」을 말하므로 같은 값이 두 번 서는 자리였다.
export function PlayerTopOverlay({
  channelName,
  title,
  viewersNote,
  chatPanelOpen,
  onToggleChatPanel,
}: {
  channelName: string;
  title: string;
  viewersNote: string;
  /** 바깥 채팅 패널이 열려 있는가 — 닫혀 있을 때만 여는 버튼이 뜬다 */
  chatPanelOpen?: boolean;
  onToggleChatPanel?: () => void;
}) {
  return (
    <div className={styles.topOverlay}>
      <div className={styles.channelPill}>
        <Avatar size="sm" name={channelName} />
        <div className={styles.channelText}>
          <div className={styles.channelRow}>
            <span className={styles.channelName}>{channelName}</span>
            <span className={styles.liveBadge}>
              <span className={styles.liveBadgeDot} aria-hidden />
              LIVE
            </span>
          </div>
          <div className={styles.channelSub}>
            {title} · {viewersNote}
          </div>
        </div>
      </div>
      {/* 패널은 플레이어 밖이라 접으면 되살릴 곳이 없다 — 닫혔을 때만 이 자리에 통로가 선다 */}
      {onToggleChatPanel && chatPanelOpen === false ? (
        <button
          type="button"
          className={styles.chatOpenBtn}
          aria-label="채팅 열기"
          onClick={onToggleChatPanel}
        >
          <MessageSquare size={17} aria-hidden />
        </button>
      ) : null}
    </div>
  );
}
