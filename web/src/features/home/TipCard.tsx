'use client';

import { useState } from 'react';
import Image from 'next/image';
import Link from 'next/link';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { Button, IconButton, LinkButton } from '@/ui';
import { useChannelRelation } from '@/features/channel/ChannelScope';
import { isOwnerOnlyPath } from '@/features/channel/ownerOnlyPaths';
import styles from './HomeScreen.module.css';
import { useRandomIndex } from './useRandomIndex';

// 홈 오른쪽 — 포키의 팁(POK-378). 데이터 없이 들어올 때마다 하나를 고르고, 화살표로 넘겨 본다.
// 있는 기능만 말한다 — 기능이 바뀌면 여기 문구도 같이 고친다.
// Card outline은 배경을 못 덮어(`.card[data-variant]`가 더 구체적) 같은 테두리의 상자를 따로 만든다.

export const TIPS: readonly { text: string; action: { label: string; href: string } }[] = [
  {
    text: 'OBS에서 단축키를 누르면 그 순간이 카드로 바로 남아요.',
    action: { label: '플러그인 설정', href: '/settings/plugin' },
  },
  {
    text: '편집자를 초대하면 카드 고르기와 편집을 나눠 맡길 수 있어요.',
    action: { label: '편집자 초대', href: '/settings/editors' },
  },
  {
    text: '지난 방송은 60일 동안 보관돼요. 만료 전에 클립으로 남겨 두세요.',
    action: { label: '지난 방송', href: '/broadcast/vod' },
  },
  {
    text: '필요 없는 카드는 숨겨 두면 목록이 깔끔해져요. 언제든 다시 꺼낼 수 있어요.',
    action: { label: '라이브 화면', href: '/broadcast/livenow' },
  },
  {
    text: '유튜브를 연결해 두면 편집기에서 만든 클립을 바로 올릴 수 있어요.',
    action: { label: '채널 연동', href: '/settings/channels' },
  },
];

export function TipCard() {
  const first = useRandomIndex(TIPS.length);
  const [step, setStep] = useState(0);
  const i = first === null ? null : (first + step) % TIPS.length;
  const tip = i === null ? undefined : TIPS[i];
  const move = (d: 1 | -1) => setStep((x) => (x + d + TIPS.length) % TIPS.length);
  // 남의 채널이면 주인 전용 화면으로 가는 팁 버튼은 비활성(POK-390): 팁 글은 그대로 둔다
  const blocked =
    useChannelRelation() === 'EDITOR' && tip !== undefined && isOwnerOnlyPath(tip.action.href);

  return (
    <section className={styles.tipCard} aria-labelledby="home-tip-title">
      <div className={styles.asideCardHeader}>
        <h2 id="home-tip-title" className={styles.asideCardTitle}>
          포키의 팁
        </h2>
        <div className={styles.tipNav}>
          <span className={styles.tipCount} aria-hidden>
            {i === null ? '' : `${i + 1} / ${TIPS.length}`}
          </span>
          <IconButton variant="ghost" size="sm" aria-label="이전 팁" onClick={() => move(-1)}>
            <ChevronLeft aria-hidden />
          </IconButton>
          <IconButton variant="ghost" size="sm" aria-label="다음 팁" onClick={() => move(1)}>
            <ChevronRight aria-hidden />
          </IconButton>
        </div>
      </div>
      {/* 말풍선 — 꼬리가 아래 오른쪽 포키를 가리킨다. 팁이 바뀌면 key로 새로 붙어 살짝 떠오른다.
          알림 영역은 바깥 상자에 둔다 — 새로 붙는 요소에 걸면 화면 낭독기가 바뀐 팁을 놓친다 */}
      <div aria-live="polite">
        <p className={styles.tipBubble} key={i ?? 'none'}>
          {tip?.text ?? ' '}
        </p>
      </div>
      <div className={styles.tipFoot}>
        {tip && blocked ? (
          <Button variant="outline" size="sm" disabled className={styles.tipAction}>
            {tip.action.label}
          </Button>
        ) : tip ? (
          <LinkButton
            as={Link}
            href={tip.action.href}
            variant="outline"
            size="sm"
            className={styles.tipAction}
          >
            {tip.action.label}
          </LinkButton>
        ) : (
          <span className={styles.tipActionSlot} aria-hidden />
        )}
        <Image
          className={styles.tipPoki}
          src="/brand/poki-peek.webp"
          alt=""
          width={480}
          height={480}
        />
      </div>
    </section>
  );
}
