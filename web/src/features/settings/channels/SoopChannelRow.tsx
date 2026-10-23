'use client';

import { Badge, Button, Skeleton } from '@/ui';
import { ChannelRow } from './ChannelRow';
import { SoopMark } from './ChannelMarks';
import type { SoopLinkViewState } from './useSoopLinkState';
import styles from './ChannelSettingsScreen.module.css';

// SOOP 행 (POK-396). ChzzkChannelRow의 쌍둥이로 1k 행 규격(ChannelRow)을 그대로 쓴다.
// 다른 점: 끊긴 연동에는 닉네임이 없어(서버가 파기) 끊김 문구에 이름을 넣지 않는다.
export function SoopChannelRow({ state }: { state: SoopLinkViewState }) {
  const { view, nickname, starting, retrying, startLink, retry, openConfirm } = state;

  return (
    <ChannelRow
      icon={<SoopMark />}
      iconClassName={styles.soopIcon}
      name="SOOP"
      badge={badgeOf(view)}
      meta={metaOf(view, nickname)}
      action={actionOf(view, starting, retrying, startLink, retry, openConfirm)}
    />
  );
}

function badgeOf(view: SoopLinkViewState['view']) {
  switch (view) {
    case 'active':
      return (
        <Badge tone="success" variant="soft" size="sm">
          정상
        </Badge>
      );
    case 'expired':
      return (
        <Badge tone="warning" variant="soft" size="sm">
          갱신 필요
        </Badge>
      );
    case 'broken':
      return (
        <Badge tone="danger" variant="soft" size="sm">
          연동 끊김
        </Badge>
      );
    default:
      return null;
  }
}

function metaOf(view: SoopLinkViewState['view'], nickname: string | undefined) {
  switch (view) {
    case 'loading':
      return <Skeleton height="calc(12 * var(--pc-u))" width="calc(240 * var(--pc-u))" />;
    case 'unavailable':
      return '연동 상태를 불러오지 못했어요';
    case 'active':
      return nickname ?? 'SOOP 방송국';
    case 'expired':
      return `${nickname ?? 'SOOP 방송국'} · 연동이 만료돼 하이라이트 감지가 멈출 수 있어요`;
    case 'broken':
      return 'SOOP 연동이 끊겼어요';
    default:
      return '연동하면 SOOP 방송에서도 하이라이트를 감지해요';
  }
}

function actionOf(
  view: SoopLinkViewState['view'],
  starting: boolean,
  retrying: boolean,
  startLink: () => void,
  retry: () => void,
  openConfirm: () => void,
) {
  const unlinkButton = (
    <Button variant="ghost" size="sm" onClick={openConfirm}>
      연동 해제
    </Button>
  );

  switch (view) {
    case 'loading':
      return <Skeleton height="calc(28 * var(--pc-u))" width="calc(56 * var(--pc-u))" />;
    case 'unavailable':
      return (
        <Button variant="soft" size="sm" loading={retrying} onClick={retry}>
          다시 시도
        </Button>
      );
    case 'active':
      return unlinkButton;
    case 'expired':
      return (
        <>
          <Button variant="soft" size="sm" loading={starting} onClick={startLink}>
            다시 연동
          </Button>
          {unlinkButton}
        </>
      );
    case 'broken':
      // 치지직과 같은 이유로 해제를 두지 않는다: 이미 닫힌 행이라 DELETE가 204만 주고 아무것도 안 한다.
      return (
        <Button variant="soft" size="sm" loading={starting} onClick={startLink}>
          다시 연동
        </Button>
      );
    default:
      return (
        <Button variant="soft" size="sm" loading={starting} onClick={startLink}>
          연동
        </Button>
      );
  }
}
