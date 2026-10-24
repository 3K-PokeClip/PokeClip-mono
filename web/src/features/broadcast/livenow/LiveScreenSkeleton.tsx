import { LoadingRegion, Skeleton } from '@/ui';
import styles from './LiveScreen.module.css';

const u = (n: number) => `calc(${n} * var(--pc-u))`;

/** 채팅 자리 줄의 폭. 고정 무늬다: 그릴 때마다 바뀌면 기다리는 동안 줄이 움직여 보인다 */
const CHAT_LINES = ['72%', '48%', '85%', '60%', '38%', '76%', '54%'];

// 방송을 정하기 전의 라이브 대시보드(POK-445). 대시보드와 같은 클래스(.container·.grid·.mainCol·.playerFrame·.infoBar·.cardPanel·
// .chatCol·.chatPanel)를 그대로 써서, 대시보드가 서도 영상·정보 줄·카드·채팅 칸의 자리가 바뀌지 않는다. 정보 줄은 글자 없이
// 막대만 둔다: 빈 방송 번호로 대시보드를 세우면 시계가 「방송 중이 아니에요」를 먼저 말했다가 바꿨다. 경과·시청자 말은 방송을
// 정한 뒤 시계와 통계가 한다. 카드 줄은 대시보드가 서자마자 보일 「카드를 불러오는 중…」 틀(.cardEmptyNote)과 같은 높이다.
// 통계 패널은 스크롤로 내려와 만나는 자리라 그리지 않는다(서면 그래프 자리에 제 스켈레톤이 선다)
export function LiveScreenSkeleton() {
  return (
    <main className={styles.container}>
      <LoadingRegion label="라이브 대시보드를 불러오는 중">
        <div className={styles.grid} aria-hidden>
          <div className={styles.mainCol}>
            {/* 플레이어 Suspense 폴백과 같은 16:9 자리 — 영상이 서기 전의 플레이어도 이 바탕이다 */}
            <div className={styles.playerFrame}>
              <div className={styles.playerFallback} />
            </div>
            <div className={styles.infoBar}>
              {/* 카테고리 칸(.infoThumb)의 치수(46u·61u·9u). 바의 높이를 이 칸이 정한다 */}
              <Skeleton width={u(46)} height={u(61)} style={{ borderRadius: u(9), flex: 'none' }} />
              <div className={styles.infoBody}>
                <Skeleton width="45%" height={u(17)} />
                <Skeleton width="25%" height={u(12)} />
              </div>
              <div className={styles.infoStats}>
                <Skeleton width={u(150)} height={u(12)} />
              </div>
              <span className={styles.infoRule} />
              <div className={styles.markBlock}>
                {/* 수동 마킹 단추(Button sm) 자리 */}
                <Skeleton height={u(32)} radius="md" />
              </div>
            </div>
            <div className={styles.cardPanel}>
              <div className={styles.cardPanelHeader}>
                <Skeleton width={u(96)} height={u(14)} />
                {/* 필터 칩(전체·자동·수동)은 카드 수와 무관하게 늘 서는 자리다 */}
                <div className={styles.filterGroup}>
                  <Skeleton width={u(52)} height={u(22)} radius="pill" />
                  <Skeleton width={u(52)} height={u(22)} radius="pill" />
                  <Skeleton width={u(52)} height={u(22)} radius="pill" />
                </div>
              </div>
              <div className={styles.cardEmptyNote}>
                {/* 글줄 높이(12u × 1.5)만큼 위아래를 띄운다 */}
                <Skeleton width={u(140)} height={u(12)} style={{ marginBlock: u(3) }} />
              </div>
            </div>
          </div>
          <div className={styles.chatCol}>
            <div className={styles.chatPanel}>
              <div className={styles.chatHeader}>
                <Skeleton width={u(84)} height={u(13)} />
              </div>
              <div className={styles.chatList}>
                {CHAT_LINES.map((width, i) => (
                  <Skeleton key={i} width={width} height={u(12)} />
                ))}
              </div>
              <div className={styles.chatFooter}>
                <Skeleton width={u(120)} height={u(10)} />
              </div>
            </div>
          </div>
        </div>
      </LoadingRegion>
    </main>
  );
}
