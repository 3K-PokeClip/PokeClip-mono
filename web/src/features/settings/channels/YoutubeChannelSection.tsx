'use client';

import { Info } from 'lucide-react';
import { YoutubeChannelRow } from './YoutubeChannelRow';
import type { YoutubeLinkViewState } from './useYoutubeLinkState';
import styles from './ChannelSettingsScreen.module.css';

// 유튜브 계정 구획 (디자인 1k, POK-221) — POK-205가 「준비 중」 세 겹으로 남겨 둔 자리를
// POK-121 백엔드(youtube-link 4문)에 실배선했다. 조립 규약은 치지직과 같다: 상태 판단은
// useYoutubeLinkState, 상태별 표현은 YoutubeChannelRow, 여기는 구획 골격만 갖는다.
//
// 1k이 그린 「계정 추가 연동」·계정 트리(다계정)는 만들지 않는다 — 실측(ADR-052)으로
// 계정 1개 · 채널 1개가 확정됐다. 채널은 구글 동의 화면에서 확정되고 그 토큰으로는 그
// 채널 하나만 보이므로, 채널 목록·재선택이라는 개념 자체가 없다. 채널을 바꾸는 수단은
// 「해제 후 다시 연동」뿐이고 아래 안내가 그것을 말한다.
//
// **여기에 상시 재연동 배너를 만들지 말 것.** 1k도 "감지될 때만 해당 계정 카드가 이 상태로
// 바뀜 (평소엔 표시 안 함)"이라고 못박았다. 재연동 유도는 서버가 BROKEN을 줄 때만 행
// 배지·문구가 바뀌는 이벤트 기반이다 — 상시 경고는 사람이 무시하게 된다.
export function YoutubeChannelSection({ state }: { state: YoutubeLinkViewState }) {
  return (
    <section className={styles.uploadSection} aria-labelledby="youtube-accounts-title">
      <h2 id="youtube-accounts-title" className={styles.sectionTitle}>
        유튜브 계정
      </h2>
      <div className={styles.rows}>
        <YoutubeChannelRow state={state} />
        <div className={styles.note}>
          <Info aria-hidden />
          <span>
            연동은 한 번 해두면 계속 유지되고, 연동이 끊긴 경우에만 이 화면과 알림으로 재연동을
            안내해 드려요. 업로드할 채널을 바꾸려면 연동을 해제한 뒤 다시 연동하면서 구글 화면에서
            원하는 채널을 선택해 주세요.
          </span>
        </div>
      </div>
    </section>
  );
}
