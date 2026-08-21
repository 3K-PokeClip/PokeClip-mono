'use client';

import { Tv } from 'lucide-react';
import { Badge, Button, Skeleton } from '@/ui';
import { ChannelRow } from './ChannelRow';
import type { ChzzkLinkViewState } from './useChzzkLinkState';
import styles from './ChannelSettingsScreen.module.css';

// 치지직 행 — 훅이 접어 준 view만 읽는다. 상태 판단은 여기서 하지 않는다.
export function ChzzkChannelRow({ state }: { state: ChzzkLinkViewState }) {
  const { view, channelName, linkedAt, starting, startLink, retry, openConfirm } = state;

  return (
    <ChannelRow
      icon={<Tv aria-hidden />}
      iconClassName={styles.chzzkIcon}
      name="치지직"
      badge={badgeOf(view)}
      meta={metaOf(view, channelName, linkedAt)}
      action={actionOf(view, starting, startLink, retry, openConfirm)}
    />
  );
}

function badgeOf(view: ChzzkLinkViewState['view']) {
  switch (view) {
    case 'active':
      return (
        <Badge tone="success" variant="soft" size="sm">
          연동됨
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

function metaOf(
  view: ChzzkLinkViewState['view'],
  channelName: string | undefined,
  linkedAt: string | undefined,
) {
  switch (view) {
    case 'loading':
      // 행 높이를 유지해 로드 후 레이아웃이 튀지 않게 한다. Skeleton은 높이를 인라인
      // style로 적용하므로 클래스가 아니라 props로 넘겨야 먹는다.
      return <Skeleton height="calc(12 * var(--pc-u))" width="calc(240 * var(--pc-u))" />;
    case 'unavailable':
      return '연동 상태를 불러오지 못했어요';
    case 'active':
      // 시안 1k의 「팔로워 4.2만 · 마지막 방송 오늘」은 백엔드가 주지 않는 값이라
      // 채널명 + 연동일로 대체했다. 없는 숫자를 지어내지 않는다.
      return [channelName, linkedAt && `${linkedAt} 연동`].filter(Boolean).join(' · ');
    case 'expired':
      return `${channelName ?? '치지직 채널'} · 연동이 만료돼 하이라이트 감지가 멈출 수 있어요`;
    case 'broken':
      return `${channelName ?? '치지직 채널'} · 치지직에서 연동이 끊겼어요. 다시 연동해 주세요`;
    default:
      return '연동하면 치지직 방송에서 하이라이트를 감지해요';
  }
}

function actionOf(
  view: ChzzkLinkViewState['view'],
  starting: boolean,
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
        <Button variant="soft" size="sm" onClick={retry}>
          다시 시도
        </Button>
      );
    case 'active':
      // 누르면 바로 해제하지 않는다 — 확인 모달이 받는다 (ADR-044).
      return unlinkButton;
    case 'expired':
      // 살아 있는 행이라(revoked_at IS NULL) 해제가 실제로 먹는다. 복구가 앞이다.
      return (
        <>
          <Button variant="soft" size="sm" loading={starting} onClick={startLink}>
            다시 연동
          </Button>
          {unlinkButton}
        </>
      );
    case 'broken':
      // 「연동 해제」를 두지 않는다. 서버 기준 이미 revoke된 행이라(revoke는
      // revoked_at IS NULL인 행만 닫는다) DELETE가 204만 주고 아무것도 하지 않는다 —
      // 버튼을 달면 눌러도 상태가 안 변하는 버튼이 된다. 누락이 아니다.
      //
      // 재연동은 409가 아니다 — 서버의 중복 검사가 다른 사용자만 걸러내고(create의
      // userId 필터), 옛 행은 닫힌다. 그래서 복구가 동의 재왕복 한 번으로 성립한다.
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
