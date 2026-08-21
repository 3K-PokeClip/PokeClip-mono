'use client';

import { Info } from 'lucide-react';
import { Badge, Button } from '@/ui';
import { ChannelRow } from './ChannelRow';
import { YoutubeMark } from './ChannelMarks';
import styles from './ChannelSettingsScreen.module.css';

// 유튜브 계정 구획 (디자인 1k) — **백엔드가 아직 없다.** OAuth·토큰 보관은 POK-121이 맡는데
// 그 티켓이 아직 `할 일`이다. 그래서 1k의 골격(구획 제목 · 계정 추가 연동 · 하단 안내)은
// 그대로 두되 연결은 하지 않는다.
//
// 구획을 생략하지 않는 이유: 1k에 있는 자리이고, 없으면 화면이 「아직 없다」를 말하지 않은
// 게 된다. 부재는 세 겹으로 드러낸다 — 비활성 버튼 + 준비 중 배지 + 사유 문구. 배지만
// 있으면 눌러 보고, 버튼만 비활성이면 "왜?"가 남는다.
//
// **POK-121 이후 여기 붙는 것:** 계정 카드(계정 헤더 + 그 아래 채널 트리), 계정별 연동 해제,
// 재연동 유도. 1k은 계정 2개·채널 3개를 그리지만 **다계정은 지원하지 않기로 했다** —
// 계정 1개 · 채널 N개 전제다(2026-08-19 보류, 결정 후 별도 티켓). 그래서 1k의 「다른 계정의
// 채널을 쓰려면 그 계정을 추가로 연동해 주세요」 문장은 아래 안내에서 뺐다.
//
// **여기에 상시 재연동 배너를 만들지 말 것.** 1k도 "감지될 때만 해당 계정 카드가 이 상태로
// 바뀜 (평소엔 표시 안 함)"이라고 못박았다. 상시 경고는 사람이 무시하게 된다.
export function YoutubeChannelSection() {
  return (
    <section className={styles.uploadSection} aria-labelledby="youtube-accounts-title">
      <div className={styles.sectionHead}>
        <h2 id="youtube-accounts-title" className={styles.sectionTitle}>
          유튜브 계정
        </h2>
        <span className={styles.sectionSub}>연동된 계정 없음</span>
        <div className={styles.sectionHeadAction}>
          <Button variant="soft" size="sm" disabled>
            계정 추가 연동
          </Button>
        </div>
      </div>
      <div className={styles.rows}>
        <ChannelRow
          icon={<YoutubeMark />}
          iconClassName={`${styles.youtubeIcon} ${styles.pending}`}
          name="유튜브"
          badge={
            <Badge tone="neutral" variant="soft" size="sm">
              준비 중
            </Badge>
          }
          meta="클립 업로드 연동은 준비 중이에요. 준비되면 여기에서 유튜브 계정 하나를 연결할 수 있어요"
          action={null}
        />
        <div className={styles.note}>
          <Info aria-hidden />
          <span>
            같은 Google 계정의 채널은 계정 아래에 함께 보여요. 연동은 한 번 해두면 계속 유지되고,
            권한을 직접 해제했거나 6개월 이상 사용하지 않은 경우에만 이 화면과 알림으로 재연동을
            안내해 드려요.
          </span>
        </div>
      </div>
    </section>
  );
}
