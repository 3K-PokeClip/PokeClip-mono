'use client';

import { CirclePlay } from 'lucide-react';
import { Badge, Button } from '@/ui';
import { ChannelRow } from './ChannelRow';
import styles from './ChannelSettingsScreen.module.css';

// 업로드 채널 구획 (POK-205) — **백엔드가 아직 없다.** 유튜브 OAuth·토큰 보관은 POK-121이
// 맡는데 그 티켓이 아직 `할 일`이다. 그래서 이 구획은 "없다는 사실"을 그리는 것이 전부다.
//
// 섹션을 생략하지 않는 이유: 화면 헤더가 이미 "유튜브로 클립을 업로드합니다"라고 약속한다.
// 자리가 없으면 사용자는 "어디 있지?"를 묻게 되고, 화면은 「아직 없다」를 말하지 않은 게 된다.
// 부재는 세 겹으로 드러낸다 — 배지 + 문구 + 누를 수 없는 버튼. 배지만 있으면 눌러 보고,
// 버튼만 비활성이면 "왜?"가 남는다.
//
// 섹션 이름을 「방송 채널」과 가른 이유: 치지직·SOOP은 하이라이트를 **감지**하는 자리이고
// 유튜브는 클립을 **올리는** 자리다. IA상 유튜브를 방송 채널 목록에 끼워 넣으면 안 된다.
//
// **POK-121 이후 여기 붙는 것:** OAuth 연동·해제, 업로드 대상 채널 선택, 재연동 유도.
// 다계정은 지원하지 않는다 — 계정 1개 · 채널 N개 전제다(2026-08-19 보류, 별도 티켓).
//
// **여기에 상시 재연동 배너를 만들지 말 것.** 재연동 안내는 이벤트 기반이다 — 권한 해제·
// 장기 미사용이 감지됐을 때만 뜬다. 그 모양은 Toast.stories.tsx가 이미 프로토타입해 뒀다
// (업로드 실패 토스트 + 「재인증」 액션). 상시 경고는 사람이 무시하게 된다.
export function YoutubeChannelSection() {
  return (
    <section aria-labelledby="upload-channel-title">
      <h2 id="upload-channel-title" className={styles.sectionTitle}>
        업로드 채널
      </h2>
      <div className={styles.rows}>
        <ChannelRow
          /* 유튜브 브랜드 자산은 실구현(POK-121 이후)에서 — 그전까지 lucide 플레이스홀더 */
          icon={<CirclePlay aria-hidden />}
          iconClassName={styles.youtubeIcon}
          name="유튜브"
          badge={
            <Badge tone="neutral" variant="soft" size="sm">
              준비 중
            </Badge>
          }
          meta="클립 업로드 연동은 준비 중이에요. 준비되면 여기에서 유튜브 계정 하나를 연결할 수 있어요"
          action={
            <Button variant="soft" size="sm" disabled>
              연동
            </Button>
          }
        />
      </div>
    </section>
  );
}
