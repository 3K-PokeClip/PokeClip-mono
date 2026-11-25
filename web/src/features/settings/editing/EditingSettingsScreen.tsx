'use client';

import { Lock } from 'lucide-react';
import { Button, PRIVATE_TEXT_CLASS } from '@/ui';
import { useMe } from '@/features/auth/useSession';
import { useChannelRelation } from '@/features/channel/ChannelScope';
import { useActiveChannel, useSwitchChannel } from '@/features/channel/useActiveChannel';
import { SettingsPageHeader } from '../SettingsPageHeader';
import { AudioTrackNamesCard } from './AudioTrackNamesCard';
import { PanelSideCard } from './PanelSideCard';
import styles from './EditingSettingsScreen.module.css';

// 설정 › 편집 환경 (POK-614). 편집기에서 쓰는 설정을 모은다.
//   - 도구 패널 위치 — 이 브라우저의 값이라 누구나(남의 채널에서 일하는 편집자도) 고른다
//   - 오디오 트랙 이름 — 설정 › 플러그인에서 옮겨 왔다. 「내」 OBS 트랙 이름(/api/auth/me/audio-tracks)이라
//     남의 채널에서 편집자로 있을 때는 입력 대신 안내 칸과 「내 채널로 전환」을 둔다: 그 채널의 이름으로 착각하게 하지 않으려고(OwnerOnly와 같은 까닭).
//     탭 전체를 막지 않는 것은 패널 위치가 편집자에게도 필요하기 때문이다.
export function EditingSettingsScreen() {
  const relation = useChannelRelation();
  return (
    <div>
      <SettingsPageHeader title="편집 환경" />
      <div className={styles.stack}>
        <PanelSideCard />
        {relation === 'EDITOR' ? <AudioTrackNamesLocked /> : <AudioTrackNamesCard />}
      </div>
    </div>
  );
}

/**
 * 남의 채널에서 본 오디오 트랙 이름(2026-11-20 시안 A 「안내 칸 + 전환 버튼」). 흐린 글 한 줄로 두었을 때 카드 끝에 묻혀
 * 안 보였다 — 눌린 바탕의 칸에 자물쇠 표식 · 까닭(채널 주인만) · 지금 채널 · 「내 채널로 전환」을 한데 둔다.
 * 전환은 막힌 설정 화면(OwnerOnly)과 같은 길이다. 카드 안 상태색은 자물쇠 표식 하나, 버튼은 무채색 테두리
 */
function AudioTrackNamesLocked() {
  const me = useMe().data;
  const active = useActiveChannel();
  const switchTo = useSwitchChannel();
  // 남의 채널 이름을 아직 못 읽었으면(새로고침 직후 등) 이름 없이 말한다 — OwnerOnly와 같다
  const where = active.ready && active.relation === 'EDITOR' ? `${active.name} 채널` : '남의 채널';
  return (
    <section className={styles.card} aria-labelledby="audio-track-names-title">
      <h2 id="audio-track-names-title" className={styles.cardTitle}>
        오디오 트랙 이름
      </h2>
      <p className={styles.cardDesc}>OBS의 트랙 1~6에 붙인 이름이 편집기 오디오 탭에 보여요.</p>
      <div className={styles.lockedCallout}>
        <span className={styles.lockBadge} aria-hidden>
          <Lock />
        </span>
        <div className={styles.lockedText}>
          <p className={styles.lockedTitle}>채널 주인만 바꿀 수 있어요</p>
          <p className={styles.lockedSub}>
            지금은 <span className={PRIVATE_TEXT_CLASS}>{where}</span>에서 편집자로 일하는 중이에요
          </p>
        </div>
        <Button
          variant="outline"
          size="sm"
          disabled={!me}
          onClick={() => me && switchTo({ streamerId: me.id, name: me.name, relation: 'OWNER' })}
        >
          내 채널로 전환
        </Button>
      </div>
    </section>
  );
}
