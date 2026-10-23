'use client';

import type { ReactNode } from 'react';
import { Lock } from 'lucide-react';
import { Button, EmptyState } from '@/ui';
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

function Blocked() {
  const me = useMe().data;
  const switchTo = useSwitchChannel();
  return (
    <div className={styles.root}>
      <EmptyState
        icon={<Lock />}
        title="이 채널에서는 열 수 없어요"
        description="채널 연동 · 편집자 관리 · 플러그인은 채널 주인만 다뤄요. 내 채널로 돌아가면 열 수 있어요."
      />
      <Button
        variant="soft"
        size="sm"
        disabled={!me}
        onClick={() => me && switchTo({ streamerId: me.id, name: me.name, relation: 'OWNER' })}
      >
        내 채널로 전환
      </Button>
    </div>
  );
}
