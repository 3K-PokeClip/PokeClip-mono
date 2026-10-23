'use client';

import { useState } from 'react';
import Link from 'next/link';
import {
  Clapperboard,
  Mail,
  Plug,
  Radio,
  Rocket,
  Scissors,
  UserCog,
  Users,
  type LucideIcon,
} from 'lucide-react';
import { LinkButton, Tabs } from '@/ui';
import { useOnboardingStore } from '@/stores/onboarding';
import { SettingsPageHeader } from '../SettingsPageHeader';
import { DiscordMark } from './DiscordMark';
import { FaqList } from './FaqList';
import { FAQ_ITEMS, FAQ_TOPICS, type FaqTopic } from './faqItems';
import styles from './HelpSettingsScreen.module.css';

// 디자인 1q 설정 · 도움말 문의 — 가이드 카드 3개 · 자주 묻는 질문 · 문의 카드.
//
// 문의 API는 없다 — 1:1 문의는 디스코드로, 그 밖은 메일로 받는다(메일은 처리방침·탈퇴 창과 같은 주소).
// 메일 버튼은 메일 앱을 여는데, 메일 앱이 없는 사람도 옮겨 적을 수 있게 주소를 글로도 보인다.

const SUPPORT_EMAIL = '3k.pokeclip@gmail.com';
const DISCORD_INVITE_URL = 'https://discord.gg/rJM9egeuwr';

const TOPIC_ICONS: Record<FaqTopic, LucideIcon> = {
  start: Rocket,
  plugin: Plug,
  channel: Radio,
  clip: Clapperboard,
  editor: Users,
  account: UserCog,
};

// 가이드 문서는 아직 없다 — 카드는 그 일을 실제로 하는 곳으로 보낸다. 시작 가이드는 홈의 처음 사용 안내를
// 처음부터 다시 띄운다(TourChip 「둘러보기」와 같은 동작). 편집은 하이라이트 카드에서 시작하므로 지난 방송으로 간다.
// 설명은 시안 문구에서 없는 기능을 뺐다: 자막·템플릿은 준비 중, 업로드 승인은 폐기(POK-254)
const GUIDES: readonly {
  key: 'start' | 'edit' | 'editors';
  title: string;
  desc: string;
  href: string;
  Icon: LucideIcon;
}[] = [
  {
    key: 'start',
    title: '시작 가이드',
    desc: 'OBS 플러그인 설치부터 첫 클립 업로드까지',
    href: '/home',
    Icon: Plug,
  },
  {
    key: 'edit',
    title: '클립 편집 가이드',
    desc: '구간·레이아웃·오디오 트랙 다듬기와 업로드 정보',
    href: '/broadcast/vod',
    Icon: Scissors,
  },
  {
    key: 'editors',
    title: '편집자 협업 가이드',
    desc: '편집자 초대·수락·내보내기',
    href: '/settings/editors',
    Icon: Users,
  },
];

export function HelpSettingsScreen() {
  const startTour = useOnboardingStore((s) => s.startTour);

  return (
    <div className={styles.screen}>
      <div className={styles.header}>
        <SettingsPageHeader title="도움말 · 문의" />
      </div>

      <ul className={styles.guides}>
        {GUIDES.map(({ key, title, desc, href, Icon }) => (
          <li key={key}>
            {/* 투어는 이동 전에 켠다 — 홈이 마운트되며 tourStep을 읽어 바로 첫 단계를 띄운다.
                수정 키를 누른 클릭은 새 탭·창으로 열려 이 탭은 홈으로 가지 않는다. 그때 켜면 tourStep(메모리 전용)이
                이 탭에 남아 나중에 홈에서 뜬금없이 투어가 뜨고, welcomeSeen까지 참으로 굳는다(PR #318 리뷰) */}
            <Link
              href={href}
              className={styles.guide}
              onClick={
                key === 'start'
                  ? (e) => {
                      if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
                      startTour();
                    }
                  : undefined
              }
            >
              <span className={styles.guideIcon}>
                <Icon aria-hidden />
              </span>
              <span className={styles.guideTitle}>{title}</span>
              <span className={styles.guideDesc}>{desc}</span>
            </Link>
          </li>
        ))}
      </ul>

      <h2 className={styles.sectionTitle}>자주 묻는 질문</h2>
      <FaqTopics />

      <section className={styles.contact}>
        <div className={styles.contactBody}>
          <h2 className={styles.contactTitle}>해결되지 않았나요?</h2>
          <p className={styles.contactDesc}>
            디스코드로 1:1 문의하거나 메일을 보내 주세요 ·{' '}
            <span className={styles.contactEmail}>{SUPPORT_EMAIL}</span>
          </p>
        </div>
        {/* 두 버튼은 같은 크기로 나란히 — 한쪽만 작으면 덜 중요한 길처럼 보인다 */}
        <div className={styles.contactButtons}>
          <LinkButton
            variant="soft"
            size="sm"
            href={DISCORD_INVITE_URL}
            target="_blank"
            rel="noopener noreferrer"
            iconStart={<DiscordMark />}
          >
            디스코드 1:1
          </LinkButton>
          <LinkButton
            variant="solid"
            size="sm"
            href={`mailto:${SUPPORT_EMAIL}`}
            iconStart={<Mail />}
          >
            이메일
          </LinkButton>
        </div>
      </section>
    </div>
  );
}

// 20문항을 한 줄로 늘어놓으면 찾기 어렵다(2026-10-23 지적) — 주제 탭으로 한 번에 한 주제(2~5개)만 보인다.
// 탭 이동·화살표 키·패널 연결은 UI Tabs가 맡고, 여기서는 칩 모양만 입힌다.
function FaqTopics() {
  const [topic, setTopic] = useState<FaqTopic>(FAQ_TOPICS[0].key);

  return (
    <Tabs value={topic} onValueChange={(v) => setTopic(v as FaqTopic)}>
      <Tabs.List aria-label="질문 주제" className={styles.topicTabs}>
        {FAQ_TOPICS.map(({ key, label }) => {
          const Icon = TOPIC_ICONS[key];
          const count = FAQ_ITEMS.filter((item) => item.topic === key).length;
          return (
            <Tabs.Trigger key={key} value={key} className={styles.topicTab}>
              <Icon aria-hidden />
              {label}
              <span className={styles.topicCount}>{count}</span>
            </Tabs.Trigger>
          );
        })}
      </Tabs.List>
      {/* 패널은 주제마다 두지 않고 하나만 두어 내용만 바꾼다. Tabs.Panel은 고르지 않은 패널을 그리지 않으므로,
          주제마다 따로 두면 탭을 옮길 때 FaqList가 언마운트돼 평가 기록이 날아가고 같은 질문을 또 평가할 수 있다
          (faq_feedback_sent 중복 집계 — PR #318 리뷰). 같은 자리의 같은 컴포넌트라 FaqList는 화면을 떠날 때까지 산다 */}
      <Tabs.Panel value={topic} className={styles.topicPanel}>
        <FaqList items={FAQ_ITEMS.filter((item) => item.topic === topic)} />
      </Tabs.Panel>
    </Tabs>
  );
}
