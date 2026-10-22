import Link from 'next/link';
import { Check } from 'lucide-react';
import { Button, Card, LinkButton } from '@/ui';
import { TOUR_TARGET } from '@/features/onboarding/tourSteps';
import styles from './HomeScreen.module.css';
import type { HomeLinks } from './useHomeMockState';

// 홈 오른쪽 — 연결 상태(POK-378). 발행 현황·만료 임박은 실데이터가 자주 비어 이 카드로 바꿨다.
// 연동 창구(방송 채널·유튜브·스트림키)는 늘 답을 주므로 비는 일이 없다. 카드당 상태색 하나 — 「연결됨」만 강조색, 나머지는 무채색 버튼.
// OBS 플러그인은 「연결됨」이라 하지 않는다 — 웹은 플러그인의 지금 연결 상태를 모르고 `issued`는 키 발급 여부일 뿐이다
// (web/README 「플러그인 화면 계약」, #298). 발급했으면 무채색 「코드 발급됨」만 적고 연결 확인은 OBS 독에 맡긴다.
// 플러그인 → 서버 신호(연결 시각 등)가 생기면 그때 「연결됨」으로 올린다.
// 방송 채널은 치지직·SOOP·씨미 모두 되지만 웹이 상태를 읽을 수 있는 건 아직 치지직뿐이다 — 줄 이름은 「방송 채널」로 두고
// 연결된 플랫폼을 설명 앞에 붙인다. SOOP·씨미 창구가 생기면 같은 줄에 함께 센다.

/** ok=연결됨(서버가 확인) · ready=할 일은 끝났지만 연결은 웹이 모른다 · off=할 일 남음 · unknown=못 읽음 */
type RowState = 'ok' | 'ready' | 'off' | 'unknown';

interface Row {
  key: string;
  name: string;
  detail: string;
  state: RowState;
  /** ready일 때 오른쪽에 무채색으로 적는 말 */
  readyLabel?: string;
  action?: { label: string; href: string };
}

function rowsOf(links: HomeLinks): Row[] {
  const channel = (
    key: string,
    name: string,
    link: HomeLinks['chzzk'] | HomeLinks['youtube'],
    /** 연결된 플랫폼 이름 — 줄 이름이 플랫폼을 말하지 않을 때 설명 앞에 붙인다 */
    via?: string,
  ): Row => {
    const label = (channelName: string | undefined) =>
      [via, channelName].filter(Boolean).join(' · ') || '연결됨';
    if (link === null) return { key, name, detail: '확인 중…', state: 'unknown' };
    if (link.linked) return { key, name, detail: label(link.channelName), state: 'ok' };
    // 끊긴 연동은 이름이 남아 온다 — 「연결 안 됨」과 갈라 다시 연결을 권한다
    if (link.status === 'BROKEN') {
      // 「연결됨」 기본값을 쓰지 않는다 — 이름이 없으면 「연결됨 · 다시 연결이 필요해요」가 되어 앞뒤가 어긋난다(리뷰 #299 3차)
      const who = [via, link.channelName].filter(Boolean).join(' · ');
      return {
        key,
        name,
        detail: who ? `${who} · 다시 연결이 필요해요` : '다시 연결이 필요해요',
        state: 'off',
        action: { label: '다시 연결', href: '/settings/channels' },
      };
    }
    return {
      key,
      name,
      detail: '연결 안 됨',
      state: 'off',
      action: { label: '연동', href: '/settings/channels' },
    };
  };
  const plugin: Row =
    links.streamKeyIssued === null
      ? { key: 'plugin', name: 'OBS 플러그인', detail: '확인 중…', state: 'unknown' }
      : links.streamKeyIssued
        ? {
            key: 'plugin',
            name: 'OBS 플러그인',
            detail: '연결 확인은 OBS 독에서 해요',
            state: 'ready',
            readyLabel: '코드 발급됨',
          }
        : {
            key: 'plugin',
            name: 'OBS 플러그인',
            detail: '코드 발급 전',
            state: 'off',
            action: { label: '코드 받기', href: '/settings/plugin' },
          };
  return [
    channel('chzzk', '방송 채널', links.chzzk, '치지직'),
    plugin,
    channel('youtube', '유튜브', links.youtube),
  ];
}

/** 시작 가이드 1·2단계가 짚는 줄(방송 기록이 없는 계정이면 큰 칸의 준비 단계 줄이 같은 id를 단다) */
const ROW_TOUR_TARGET: Record<string, string | undefined> = {
  chzzk: TOUR_TARGET.homeChannel,
  plugin: TOUR_TARGET.homePlugin,
};

export function ConnectionCard({
  links,
  actionsDisabled = false,
}: {
  links: HomeLinks;
  /** 남의 채널(POK-390): 버튼은 남기되 누를 수 없게. 링크면 비활성을 못 나타낸다 */
  actionsDisabled?: boolean;
}) {
  const rows = rowsOf(links);
  // 플러그인은 연결을 모르므로 「모두 연결됨」이 아니라 「준비 완료」라 한다
  const note = rows.every((r) => r.state === 'ok' || r.state === 'ready')
    ? '준비 완료'
    : rows.some((r) => r.state === 'off')
      ? '확인 필요'
      : null;
  return (
    <Card variant="outline" padding={0}>
      <div className={styles.asideCardHeader}>
        <h2 className={styles.asideCardTitle}>연결 상태</h2>
        {note ? <span className={styles.asideCardNote}>{note}</span> : null}
      </div>
      <ul className={styles.asideRows}>
        {rows.map((r) => (
          <li key={r.key} className={styles.asideRow} data-tour-id={ROW_TOUR_TARGET[r.key]}>
            <div className={styles.connBody}>
              <div className={styles.connName}>{r.name}</div>
              <div className={styles.connDetail}>{r.detail}</div>
            </div>
            {r.state === 'ok' ? (
              <span className={styles.connOk}>
                <Check aria-hidden /> 연결됨
              </span>
            ) : r.state === 'ready' ? (
              <span className={styles.connReady}>{r.readyLabel}</span>
            ) : r.action && actionsDisabled ? (
              <Button variant="outline" size="sm" disabled className={styles.connAction}>
                {r.action.label}
              </Button>
            ) : r.action ? (
              <LinkButton
                as={Link}
                href={r.action.href}
                variant="outline"
                size="sm"
                className={styles.connAction}
              >
                {r.action.label}
              </LinkButton>
            ) : null}
          </li>
        ))}
      </ul>
    </Card>
  );
}
