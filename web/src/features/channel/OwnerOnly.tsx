'use client';

import type { ReactNode } from 'react';
import { Lock } from 'lucide-react';
import { Button } from '@/ui';
import { useMe } from '@/features/auth/useSession';
import { useChannelRelation } from './ChannelScope';
import { useSwitchChannel } from './useActiveChannel';
import styles from './OwnerOnly.module.css';

// 채널 주인만 여는 설정 화면(채널 연동 · 편집자 관리 · 플러그인, POK-390). 남의 채널에서 주소로 바로 와도 그 채널의 설정이
// 아니라 「내」 연동·편집자·송출 키가 나와 착각하게 된다: 화면 대신 안내를 그리고 내 채널로 돌아갈 길을 준다.
// 채널을 모르면(ChannelScope 밖, me 실패) 막지 않는다: 이 카드 전과 같은 동작.
export function OwnerOnly({ children }: { children: ReactNode }) {
  const relation = useChannelRelation();
  if (relation !== 'EDITOR') return <>{children}</>;
  return <Blocked />;
}

/** 막힌 화면(시안 비교 B안 「잠긴 미리보기」에서 흐린 목록을 뺀 것). 안내는 본문의 가로·세로 한가운데에 둔다. */
function Blocked() {
  const me = useMe().data;
  const switchTo = useSwitchChannel();
  return (
    <div className={styles.root}>
      <div className={styles.center}>
        <span className={styles.icon} aria-hidden>
          <Lock />
        </span>
        <p className={styles.title}>채널 주인만 볼 수 있는 화면이에요</p>
        <p className={styles.text}>지금은 남의 채널에서 편집자로 일하는 중이에요</p>
        <Button
          variant="solid"
          size="sm"
          disabled={!me}
          onClick={() => me && switchTo({ streamerId: me.id, name: me.name, relation: 'OWNER' })}
        >
          내 채널로 전환
        </Button>
      </div>
    </div>
  );
}
