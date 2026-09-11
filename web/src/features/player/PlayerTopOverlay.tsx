import { Avatar } from '@/ui';
import styles from './GlassPlayer.module.css';

// 플레이어 상단 오버레이 — 채널 필 (시안 영상 플레이어 글래스).
// 뒤로가기는 1b에서 페이지 헤더가 사라진 뒤 사이드 메뉴가 담당하므로 여기엔 없다.
// 시안에 있던 「채팅 열기」 버튼도 없다 — 바깥 채팅 패널은 접히면 세로 레일(ChatRail)이 되살리고,
// 전체 화면에선 바깥 패널이 안 보여 여는 버튼이 의미가 없다(POK-239).
//
// 필의 둘째 줄은 시청자 수만이다. 시안이 라이브에서 제목을 안 쓰고(제목 줄은 VOD 변형의 것),
// 1b에선 영상 바로 아래 방송 정보 바가 제목과 경과 시간을 이미 말한다 — 여기 두면 같은 값이
// 한 화면에 두 번 선다.
export function PlayerTopOverlay({
  channelName,
  viewersNote,
}: {
  channelName: string;
  /** 필 아래 줄 — 라이브는 「1,842명 시청 중」 */
  viewersNote: string;
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
    </div>
  );
}
