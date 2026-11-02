'use client';

import { useId, useState } from 'react';
import { BellOff } from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { ModeSegment, Switch, type ModeOption } from '@/ui';
import { delegationsAsEditorQueryOptions } from '@/api/editors';
import { SettingsPageHeader } from '../SettingsPageHeader';
import {
  DO_NOT_DISTURB_DEFAULT,
  NOTIFICATION_ITEMS,
  type NotificationItem,
  type NotificationKey,
  type NotificationMode,
} from './notificationItems';
import styles from './NotificationSettingsScreen.module.css';

// 설정 · 알림.
//
// 알림마다 「끔 · 받기 · 중요」 중 하나를 고르고, 맨 위 「방송 중 방해 금지」를 켜면 방송 중에는
// 「중요」로 고른 알림만 온다. 처음 값만 우리가 정해 두고(notificationItems) 전부 사용자가 바꾼다.
//
// 고른 값은 어디에도 저장하지 않는다 — 서버·localStorage·전역 스토어·모듈 캐시 전부 없다. 알림을
// 보내는 서버가 아직 없어서다. 아래 useState는 고르는 동작을 보여 주기 위한 렌더 상태이고 화면을
// 벗어나면 사라진다. 저장은 알림 서버 티켓에서 정하며, 그때 이 useState가 상태 훅으로 승격된다.

const MODE_OPTIONS: readonly ModeOption<NotificationMode>[] = [
  { key: 'off', label: '끔' },
  { key: 'on', label: '받기' },
  { key: 'important', label: '중요', accent: true },
];

function initialModes(): Record<NotificationKey, NotificationMode> {
  // 정확히 목록을 훑어 만들므로 키가 빠질 수 없다 — 그 사실을 단언으로 옮긴다
  return Object.fromEntries(
    NOTIFICATION_ITEMS.map((item) => [item.key, item.defaultMode]),
  ) as Record<NotificationKey, NotificationMode>;
}

/** 방해 금지 설명 — 지금 고른 값으로 방송 중에 실제로 몇 개가 오는지 말한다 */
function dndDescription(dnd: boolean, importantCount: number): string {
  if (!dnd) return '라이브 중에도 켜 둔 알림이 모두 와요';
  if (importantCount === 0) return '라이브 중엔 알림이 오지 않아요';
  return `라이브 중엔 중요 알림 ${importantCount}개만 와요`;
}

export function NotificationSettingsScreen() {
  const [modes, setModes] = useState(initialModes);
  const [dnd, setDnd] = useState(DO_NOT_DISTURB_DEFAULT);
  const dndTitleId = useId();
  const dndDescId = useId();
  const listId = useId();

  // 편집을 맡은 채널이 하나라도 있어야 편집자용 줄을 보인다 — 불러오는 중·실패면 숨긴다
  const asEditor = useQuery(delegationsAsEditorQueryOptions);
  const isEditor = (asEditor.data?.length ?? 0) > 0;
  const items = NOTIFICATION_ITEMS.filter((item) => isEditor || !item.editorOnly);
  // 화면에 보이는 줄만 센다 — 숨긴 편집자용 줄이 개수에 섞이면 설명이 화면과 어긋난다
  const importantCount = items.filter((item) => modes[item.key] === 'important').length;

  const setMode = (key: NotificationKey, mode: NotificationMode) =>
    setModes((prev) => ({ ...prev, [key]: mode }));

  return (
    <div>
      {/* 「인앱」 열 이름이 사라져 알림이 어디로 오는지 머리말이 말한다 */}
      <SettingsPageHeader
        title="알림 설정"
        description="알림은 오른쪽 위 종 아이콘에서 볼 수 있어요"
      />

      <section className={`${styles.card} ${styles.cardGap}`} aria-labelledby={dndTitleId}>
        <div className={styles.dnd}>
          <span className={styles.dndIcon} aria-hidden>
            <BellOff />
          </span>
          <div className={styles.rowBody}>
            <div id={dndTitleId} className={styles.dndTitle}>
              방송 중 방해 금지
            </div>
            <div id={dndDescId} className={styles.rowDesc} aria-live="polite">
              {dndDescription(dnd, importantCount)}
            </div>
          </div>
          {/* 이름은 aria-label로 직접 준다 — 위 제목을 aria-labelledby로만 가리키면 axe가
              label-title-only로 잡는다 */}
          <Switch
            size="sm"
            aria-label="방송 중 방해 금지"
            aria-describedby={dndDescId}
            checked={dnd}
            onChange={(e) => setDnd(e.target.checked)}
          />
        </div>
      </section>

      <section className={styles.card} aria-labelledby={listId}>
        <h2 id={listId} className={styles.cardTitle}>
          알림
        </h2>
        {items.map((item) => (
          <NotificationRow
            key={item.key}
            item={item}
            mode={modes[item.key]}
            onChange={(mode) => setMode(item.key, mode)}
          />
        ))}
      </section>
    </div>
  );
}

// 세그먼트가 항목 제목을 이름으로, 설명을 설명으로 든다 — 같은 「끔 · 받기 · 중요」가 줄마다 반복된다
function NotificationRow({
  item,
  mode,
  onChange,
}: {
  item: NotificationItem;
  mode: NotificationMode;
  onChange: (mode: NotificationMode) => void;
}) {
  const titleId = useId();
  const descId = useId();
  return (
    <div className={styles.row}>
      <div className={styles.rowBody}>
        <div id={titleId} className={styles.rowTitle}>
          {item.title}
        </div>
        <div id={descId} className={styles.rowDesc}>
          {item.desc}
        </div>
      </div>
      <ModeSegment
        className={styles.segment}
        options={MODE_OPTIONS}
        value={mode}
        onChange={onChange}
        labelledBy={titleId}
        describedBy={descId}
      />
    </div>
  );
}
