'use client';

import { useId, useState, type ReactNode } from 'react';
import { Lock } from 'lucide-react';
import { Switch } from '@/ui';
import { SettingsPageHeader } from '../SettingsPageHeader';
import {
  CRITICAL_ITEMS,
  DO_NOT_DISTURB_DEFAULT,
  NORMAL_ITEMS,
  type NotificationItem,
} from './notificationItems';
import styles from './NotificationSettingsScreen.module.css';

// 디자인 1n 설정 · 알림 (POK-209).
//
// 스위치 값은 어디에도 저장하지 않는다 — 서버·localStorage·전역 스토어·모듈 캐시 전부 없다.
// 아래 useState는 저장이 아니라 스위치가 눌리는 것을 보여 주기 위한 렌더 상태이고,
// 화면을 벗어나면 사라진다. 저장 위치는 백엔드 연결 티켓에서 정하며, 그때 이 useState가
// 상태 훅으로 승격된다 — useChannelMockState → useChzzkLinkState가 밟은 길과 같다.

interface Toggles {
  /** 항목 key → 이메일 on/off (중요·일반 전부) */
  email: Record<string, boolean>;
  /** 항목 key → 인앱 on/off (일반만 — 중요 알림의 인앱은 항상 켜져 있어 값을 갖지 않는다) */
  inapp: Record<string, boolean>;
  /** 방송 중 방해 금지 */
  dnd: boolean;
}

function initialToggles(): Toggles {
  const email: Record<string, boolean> = {};
  const inapp: Record<string, boolean> = {};
  for (const item of [...CRITICAL_ITEMS, ...NORMAL_ITEMS]) {
    email[item.key] = item.email;
    if (item.inapp !== undefined) inapp[item.key] = item.inapp;
  }
  return { email, inapp, dnd: DO_NOT_DISTURB_DEFAULT };
}

export function NotificationSettingsScreen() {
  const [toggles, setToggles] = useState<Toggles>(initialToggles);
  const criticalId = useId();
  const normalId = useId();

  const setChannel = (channel: 'email' | 'inapp', key: string, next: boolean) =>
    setToggles((prev) => ({ ...prev, [channel]: { ...prev[channel], [key]: next } }));

  return (
    <div className={styles.screen}>
      {/* 시안 1n의 제목-카드 간격 20u — 헤더가 자체로 4u를 갖는다 */}
      <div className={styles.header}>
        <SettingsPageHeader title="알림 설정" />
      </div>

      <section className={`${styles.card} ${styles.criticalCard}`} aria-labelledby={criticalId}>
        <ColumnHeader groupId={criticalId} group="중요 알림" />
        {CRITICAL_ITEMS.map((item) => (
          <NotificationRow key={item.key} item={item}>
            {/* 끌 수 없다는 사실을 접근 이름이 든다 — disabled 입력은 포커스를 받지 못해
                aria-describedby가 낭독되지 않는다. 아래 자물쇠 문구가 시각 채널로 같은 말을 한다. */}
            <span className={styles.switchCell}>
              <Switch size="sm" aria-label="인앱 — 항상 켜져 있어요" checked disabled />
            </span>
            <span className={styles.switchCell}>
              <Switch
                size="sm"
                aria-label="이메일"
                checked={toggles.email[item.key]}
                onChange={(e) => setChannel('email', item.key, e.target.checked)}
              />
            </span>
          </NotificationRow>
        ))}
        <p className={styles.lockNote}>
          <Lock aria-hidden />
          인앱 중요 알림은 항상 켜져 있어요
        </p>
      </section>

      <section className={styles.card} aria-labelledby={normalId}>
        <ColumnHeader groupId={normalId} group="일반 알림" />
        {NORMAL_ITEMS.map((item) => (
          <NotificationRow key={item.key} item={item}>
            <span className={styles.switchCell}>
              <Switch
                size="sm"
                aria-label="인앱"
                checked={toggles.inapp[item.key]}
                onChange={(e) => setChannel('inapp', item.key, e.target.checked)}
              />
            </span>
            <span className={styles.switchCell}>
              <Switch
                size="sm"
                aria-label="이메일"
                checked={toggles.email[item.key]}
                onChange={(e) => setChannel('email', item.key, e.target.checked)}
              />
            </span>
          </NotificationRow>
        ))}
        {/* 방해 금지는 알림 종류가 아니라 그 위에 걸리는 규칙이라 2열 격자 밖에 선다 */}
        <div className={styles.dndRow}>
          <div className={styles.rowBody}>
            <div className={styles.rowTitle}>방송 중 방해 금지</div>
            <div className={styles.rowDesc}>라이브 중에는 중요 알림만 받아요</div>
          </div>
          <Switch
            size="sm"
            aria-label="방송 중 방해 금지"
            checked={toggles.dnd}
            onChange={(e) => setToggles((prev) => ({ ...prev, dnd: e.target.checked }))}
          />
        </div>
      </section>
    </div>
  );
}

function ColumnHeader({ groupId, group }: { groupId: string; group: string }) {
  return (
    <div className={styles.columnHeader}>
      <span id={groupId}>{group}</span>
      <span className={styles.channelHead}>인앱</span>
      <span className={styles.channelHead}>이메일</span>
    </div>
  );
}

// 행을 role="group"으로 묶고 항목 제목으로 이름을 준다 — 「인앱」·「이메일」 스위치가
// 열한 행에 같은 이름으로 반복되어 낱개로는 어느 항목의 것인지 알 수 없다.
function NotificationRow({ item, children }: { item: NotificationItem; children: ReactNode }) {
  const titleId = useId();
  return (
    <div className={styles.row} role="group" aria-labelledby={titleId}>
      <div className={styles.rowBody}>
        <div id={titleId} className={styles.rowTitle}>
          {item.title}
        </div>
        <div className={styles.rowDesc}>{item.desc}</div>
      </div>
      {children}
    </div>
  );
}
