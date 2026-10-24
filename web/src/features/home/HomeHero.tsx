'use client';

import Image from 'next/image';
import Link from 'next/link';
import { Check } from 'lucide-react';
import { Button, LinkButton, Skeleton } from '@/ui';
import { TOUR_TARGET } from '@/features/onboarding/tourSteps';
import styles from './HomeScreen.module.css';
import { isWaitingForBroadcast, setupSteps, type SetupStepId } from './setupSteps';
import type { HomeLinks, WeekStats } from './useHomeMockState';
import { useRandomIndex } from './useRandomIndex';

// 홈 맨 위 큰 칸(POK-378) — 지금 상황을 한 칸이 말한다.
//   방송 기록 없음: 첫 클립까지 가는 길 + 「포키가 해 주는 일」 · 방송 채널·플러그인 끝: 방송 기다리는 중
//   이번 주에 방송함: 이번 주 숫자 넷 · 이번 주(월요일부터) 0회: 마지막 방송 + 흐린 지난주 숫자
// 포키 그림은 주머니 수정본(landing/assets-pocket-refined)만 쓴다.

/** 이번 주 칸 옆 포키 포즈 후보. peek은 팁 카드 전용이라 뺀다 */
const POKI_POSES = [
  'poki-wave',
  'poki-cheer',
  'poki-detect',
  'poki-upload',
  'poki-run',
  'poki-run-2',
  'poki-headphones',
] as const;

/** 들어올 때마다 아무 포즈나 하나 — 고르기 전엔 빈 칸으로 자리만(그리드 열 폭은 CSS가 지킨다) */
function RandomPoki() {
  const i = useRandomIndex(POKI_POSES.length);
  const pose = i === null ? undefined : POKI_POSES[i];
  if (!pose) return <span aria-hidden />;
  return (
    <Image
      key={pose}
      className={`${styles.heroMascot} ${styles.weekMascot}`}
      src={`/brand/${pose}.webp`}
      alt=""
      width={480}
      height={480}
      // 첫 화면 위쪽이라 늦게 불러오면 큰 그림이 비어 보인다(Next LCP 경고)
      loading="eager"
    />
  );
}

/** 시작 가이드 1·2단계가 짚는 준비 단계 줄(기록이 있는 계정이면 연결 상태 줄이 같은 id를 단다) */
const TRACK_TOUR_TARGET: Partial<Record<SetupStepId, string>> = {
  chzzk: TOUR_TARGET.homeChannel,
  plugin: TOUR_TARGET.homePlugin,
};

const u = (n: number) => `calc(${n} * var(--pc-u))`;

/**
 * 첫 응답 전 — 신규 계정 화면을 잘못 보이지 않게 큰 칸 모양만 잡는다. 막대 치수는 큰 칸처럼 셸 배율(--pc-u)을 탄다: px로 두면
 * 배율이 큰 화면에서 막대만 작게 남는다(POK-445). 격자·오른쪽 칸은 그리지 않는다: 기록 없는 계정은 준비 단계 화면(SetupHome)이라
 * 모양이 다르다
 */
export function HeroSkeleton() {
  return (
    <section
      className={styles.hero}
      aria-label="불러오는 중"
      aria-busy="true"
      data-tour-id={TOUR_TARGET.homeHero}
      // 시작 가이드에게 「홈을 아직 읽는 중」을 알린다 — 그동안 뒤 단계 타깃이 없다고 투어를 끝내지 않게(리뷰 #299)
      data-tour-pending=""
    >
      <div className={styles.skeletonStack}>
        {/* 좁은 화면에서 칸 밖으로 잘리지 않게 폭은 칸 안쪽까지만 */}
        <Skeleton width={u(160)} height={u(13)} style={{ maxWidth: '100%' }} />
        <Skeleton width={u(360)} height={u(28)} style={{ maxWidth: '100%' }} />
        <Skeleton width={u(520)} height={u(44)} style={{ maxWidth: '100%' }} />
      </div>
      <span aria-hidden />
    </section>
  );
}

export function SetupHome({
  userName,
  links,
  actionsDisabled = false,
}: {
  userName: string | null;
  links: HomeLinks;
  /** 남의 채널(POK-390): 단계 버튼은 남기되 누를 수 없게 */
  actionsDisabled?: boolean;
}) {
  // 링크는 비활성을 못 나타낸다: 막을 때는 같은 모양의 비활성 버튼으로 바꾼다
  const action = (href: string, label: string, variant: 'solid' | 'outline', className?: string) =>
    actionsDisabled ? (
      <Button variant={variant} size="sm" disabled className={className}>
        {label}
      </Button>
    ) : (
      <LinkButton as={Link} href={href} variant={variant} size="sm" className={className}>
        {label}
      </LinkButton>
    );
  const steps = setupSteps(links);
  const left = steps.filter((x) => !x.done).length;
  // 못 읽은 단계가 있으면 남은 수를 단정하지 않는다 — 줄은 「확인 중…」인데 제목만 「4단계」라 하지 않게(리뷰 #299 2차)
  const checking = steps.some((x) => x.unknown);
  const waiting = isWaitingForBroadcast(links);
  // 지금 단계는 상태를 아는 것 중 첫 미완료 — 못 읽은 단계(unknown)를 지금 할 일로 내세우지 않는다
  const currentId = steps.find((x) => !x.done && !x.unknown)?.id;

  return (
    <div>
      <section className={styles.hero} aria-label="지금 할 일" data-tour-id={TOUR_TARGET.homeHero}>
        <div>
          <div className={styles.heroHello}>
            {userName ? `${userName}님, 반가워요` : '반가워요'}
          </div>
          <h1 className={styles.heroTitle}>
            {waiting
              ? '이제 방송만 켜면 돼요'
              : checking
                ? '첫 클립까지 남은 단계를 확인하고 있어요'
                : `첫 클립까지 ${left}단계 남았어요`}
          </h1>
          <p className={styles.heroSub}>
            {waiting
              ? 'OBS에서 방송을 시작하면 포키가 바로 하이라이트를 찾기 시작해요.'
              : '방송 중 하이라이트는 포키가 찾아요. 설정 두 가지만 먼저 해 주세요.'}
          </p>
          <ol className={styles.track}>
            {steps.map((step, i) => {
              const state = step.done ? 'done' : step.id === currentId ? 'current' : 'todo';
              return (
                <li
                  key={step.id}
                  className={styles.trackItem}
                  data-state={state}
                  data-tour-id={TRACK_TOUR_TARGET[step.id]}
                >
                  <span className={styles.trackNo}>
                    {step.done ? <Check aria-label="완료" /> : i + 1}
                  </span>
                  <span className={styles.trackTitle}>
                    {step.title}
                    {step.done ? <span className={styles.trackNote}>{step.doneNote}</span> : null}
                    {step.unknown ? <span className={styles.trackNote}>확인 중…</span> : null}
                  </span>
                  {state === 'current' ? (
                    <span className={styles.trackDesc}>{step.desc}</span>
                  ) : null}
                  {state === 'current' && step.action
                    ? action(step.action.href, step.action.label, 'solid', styles.trackAction)
                    : null}
                  {state === 'current' && step.id === 'broadcast' ? (
                    <span className={styles.trackWait}>
                      <span className={styles.pulse} aria-hidden /> 기다리는 중
                    </span>
                  ) : null}
                  {/* 방송 채널·플러그인은 순서가 없다 — 지금 단계가 아니어도 버튼을 둔다 */}
                  {state === 'todo' && step.action && !step.unknown
                    ? action(step.action.href, step.action.label, 'outline', styles.trackAction)
                    : null}
                </li>
              );
            })}
          </ol>
          {waiting && links.youtube !== null && !links.youtube.linked ? (
            <div className={styles.heroFoot}>
              유튜브도 연결해 두면 만든 클립을 바로 올릴 수 있어요.
              {action('/settings/channels', '유튜브 연동', 'outline')}
            </div>
          ) : null}
        </div>
        <Image
          className={styles.heroMascot}
          src={`/brand/${waiting ? 'poki-detect' : 'poki-wave'}.webp`}
          alt=""
          width={480}
          height={480}
          loading="eager"
        />
      </section>

      <section aria-labelledby="home-features-title" data-tour-id={TOUR_TARGET.homeFeatures}>
        <h2 id="home-features-title" className={styles.featureHead}>
          포키가 해 주는 일
        </h2>
        <ul className={styles.features}>
          <li className={styles.feature}>
            <Image
              src="/brand/poki-headphones.webp"
              alt=""
              width={480}
              height={480}
              loading="eager"
            />
            <div>
              <div className={styles.featureTitle}>방송 중 하이라이트 찾기</div>
              <div className={styles.featureDesc}>채팅이 터진 순간을 카드로 모아 둬요</div>
            </div>
          </li>
          <li className={styles.feature}>
            <Image src="/brand/poki-run.webp" alt="" width={480} height={480} loading="eager" />
            <div>
              <div className={styles.featureTitle}>지난 방송 60일 보관</div>
              <div className={styles.featureDesc}>방송이 끝나도 카드를 골라 클립을 만들어요</div>
            </div>
          </li>
          <li className={styles.feature}>
            <Image src="/brand/poki-upload.webp" alt="" width={480} height={480} loading="eager" />
            <div>
              <div className={styles.featureTitle}>세로 클립 바로 발행</div>
              <div className={styles.featureDesc}>쇼츠 비율로 다듬어 유튜브에 올려요</div>
            </div>
          </li>
        </ul>
      </section>
    </div>
  );
}

/** 숫자 넷. 보관함을 못 읽은 칸은 「–」 */
function StatList({ stats, past = false }: { stats: WeekStats; past?: boolean }) {
  const cells: { label: string; value: number | null; unit: string }[] = [
    { label: '방송', value: stats.broadcasts, unit: '회' },
    { label: '찾은 카드', value: stats.cards, unit: '장' },
    { label: '만든 클립', value: stats.clips, unit: '개' },
    { label: '유튜브 발행', value: stats.published, unit: '개' },
  ];
  return (
    <dl className={styles.weekStats} data-past={past || undefined}>
      {cells.map((c) => (
        <div key={c.label}>
          <dt>{c.label}</dt>
          <dd>
            {c.value === null ? (
              '–'
            ) : (
              <>
                {c.value}
                <small>{c.unit}</small>
              </>
            )}
          </dd>
        </div>
      ))}
    </dl>
  );
}

export function WeekHero({ userName, week }: { userName: string | null; week: WeekStats }) {
  return (
    <section className={styles.hero} aria-label="이번 주" data-tour-id={TOUR_TARGET.homeHero}>
      <div>
        <div className={styles.heroHello}>
          {userName ? `${userName}님, 이번 주 기록이에요` : '이번 주 기록이에요'}
        </div>
        <h1 className={styles.heroTitle}>
          방송 {week.broadcasts}번에서 카드 {week.cards}장을 찾았어요
        </h1>
        <StatList stats={week} />
      </div>
      <RandomPoki />
    </section>
  );
}

/**
 * 방송 기록은 있지만 이번 주(월요일부터)엔 아직 방송 전 — 0만 넷 늘어놓지 않는다.
 * 마지막 방송이 언제였는지 말하고, 지난주 기록을 흐리게 보여 준다(지난주도 비면 숫자 줄은 뺀다).
 */
export function QuietWeekHero({
  userName,
  lastWeek,
  lastBroadcast,
}: {
  userName: string | null;
  lastWeek: WeekStats;
  lastBroadcast: { date: string; ago: string } | null;
}) {
  const ago = lastBroadcast?.ago;
  return (
    <section className={styles.hero} aria-label="이번 주" data-tour-id={TOUR_TARGET.homeHero}>
      <div>
        <div className={styles.heroHello}>
          {userName
            ? `${userName}님, 이번 주는 아직 방송 전이에요`
            : '이번 주는 아직 방송 전이에요'}
        </div>
        <h1 className={styles.heroTitle}>
          {ago === undefined
            ? '이번 주 첫 방송을 기다리고 있어요'
            : `마지막 방송은 ${ago === '어제' ? '어제였어요' : `${ago}이었어요`}`}
        </h1>
        <p className={styles.heroSub}>
          {lastBroadcast ? `${lastBroadcast.date} 이후로 쉬고 있어요. ` : ''}
          방송을 켜면 이 칸이 이번 주 기록으로 바뀌어요.
        </p>
        {lastWeek.broadcasts > 0 ? (
          <>
            <div className={styles.pastLabel}>지난주 기록</div>
            <StatList stats={lastWeek} past />
          </>
        ) : null}
      </div>
      <RandomPoki />
    </section>
  );
}
