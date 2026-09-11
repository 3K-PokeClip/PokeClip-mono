import { MessageSquare } from 'lucide-react';
import { Avatar } from '@/ui';
import styles from './GlassPlayer.module.css';

// 플레이어 상단 오버레이 — 채널 필 + 채팅 열기 (시안 영상 플레이어 글래스).
// 뒤로가기는 1b에서 페이지 헤더가 사라진 뒤 사이드 메뉴가 담당하므로 여기엔 없다.
//
// 필의 둘째 줄은 시청자 수만이다. 시안이 라이브에서 제목을 안 쓰고(제목 줄은 VOD 변형의 것),
// 1b에선 영상 바로 아래 방송 정보 바가 제목과 경과 시간을 이미 말한다 — 여기 두면 같은 값이
// 한 화면에 두 번 선다.
export function PlayerTopOverlay({
  channelName,
  viewersNote,
  chatPanelOpen,
  onToggleChatPanel,
}: {
  channelName: string;
  /** 필 아래 줄 — 라이브는 「1,842명 시청 중」 */
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
          <div className={styles.channelSub}>{viewersNote}</div>
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
