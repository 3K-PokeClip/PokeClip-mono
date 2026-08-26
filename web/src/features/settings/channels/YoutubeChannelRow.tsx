'use client';

import { Badge, Button, Skeleton } from '@/ui';
import { ChannelRow } from './ChannelRow';
import { YoutubeMark } from './ChannelMarks';
import type { YoutubeLinkViewState } from './useYoutubeLinkState';
import styles from './ChannelSettingsScreen.module.css';

// 유튜브 행 — 훅이 접어 준 view만 읽는다. 상태 판단은 여기서 하지 않는다 (ChzzkChannelRow와
// 같은 규약). 치지직과 달리 expired 분기가 없다 — 계약에 EXPIRED 자체가 없다.
export function YoutubeChannelRow({ state }: { state: YoutubeLinkViewState }) {
  const { view, channelName, starting, retrying, startLink, retry, openConfirm } = state;

  return (
    <ChannelRow
      icon={<YoutubeMark />}
      iconClassName={styles.youtubeIcon}
      name="유튜브"
      badge={badgeOf(view)}
      meta={metaOf(view, channelName)}
      action={actionOf(view, starting, retrying, startLink, retry, openConfirm)}
    />
  );
}

function badgeOf(view: YoutubeLinkViewState['view']) {
  switch (view) {
    case 'active':
      return (
        <Badge tone="success" variant="soft" size="sm">
          정상
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

function metaOf(view: YoutubeLinkViewState['view'], channelName: string | undefined) {
  switch (view) {
    case 'loading':
      // 행 높이를 유지해 로드 후 레이아웃이 튀지 않게 한다. Skeleton은 높이를 인라인
      // style로 적용하므로 클래스가 아니라 props로 넘겨야 먹는다.
      return <Skeleton height="calc(12 * var(--pc-u))" width="calc(240 * var(--pc-u))" />;
    case 'unavailable':
      return '연동 상태를 불러오지 못했어요';
    case 'active':
      // 1k은 채널명 한 줄이다 — 연동일·구독자 같은 걸 덧붙이지 않는다.
      return channelName ?? '유튜브 채널';
    case 'broken':
      return `${channelName ?? '유튜브 채널'} · 유튜브 연동이 끊겼어요. 다시 연동해 주세요`;
    default:
      return '연동하면 하이라이트 클립을 유튜브에 업로드할 수 있어요';
  }
}

function actionOf(
  view: YoutubeLinkViewState['view'],
  starting: boolean,
  retrying: boolean,
  startLink: () => void,
  retry: () => void,
  openConfirm: () => void,
) {
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
      // 누르면 바로 해제하지 않는다 — 확인 모달이 받는다 (ADR-044).
      return (
        <Button variant="ghost" size="sm" onClick={openConfirm}>
          연동 해제
        </Button>
      );
    case 'broken':
      // 치지직 broken과 같은 이유로 「연동 해제」를 두지 않는다 — BROKEN 행은 이미
      // revokedAt이 박힌 닫힌 행이라(YoutubeLinkWriter.closeAlive는 살아있는 행만 닫는다)
      // DELETE가 204만 주고 아무것도 하지 않는다. 눌러도 상태가 안 변하는 버튼이 된다.
      //
      // 재연동은 409가 아니다 — 새 동의가 옛 행을 대체한다(POST 하나가 그것을 이미 한다).
      // 그래서 복구가 동의 재왕복 한 번으로 성립한다.
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
