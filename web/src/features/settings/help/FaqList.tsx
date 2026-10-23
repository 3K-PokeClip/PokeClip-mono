'use client';

import { useEffect, useId, useRef, useState } from 'react';
import Link from 'next/link';
import { Check, ChevronDown, ThumbsDown, ThumbsUp } from 'lucide-react';
import { track } from '@/lib/analytics';
import type { FaqItem, FaqKey } from './faqItems';
import styles from './HelpSettingsScreen.module.css';

// 디자인 1q 「자주 묻는 질문」 아코디언 — ① 전부 닫힘 ② 한 번에 하나만 열림 ③ 평가 → 「보냈어요」.
//
// 평가는 질문마다 한 번이다. 보낸 질문은 다시 열어도 버튼 대신 완료 문구를 보이고, 화면을 떠나면 잊는다 —
// 같은 사람이 같은 질문에 두 번 답했다는 건 이벤트 쪽에서 가려낼 수 없으니 화면이 막는다.

export function FaqList({ items }: { items: readonly FaqItem[] }) {
  const [openKey, setOpenKey] = useState<FaqKey | null>(null);
  const [voted, setVoted] = useState<ReadonlySet<FaqKey>>(() => new Set());

  // 주제 탭을 바꿔도 이 목록은 언마운트되지 않는다(평가 기록을 지키려고 — HelpSettingsScreen의 패널 주석).
  // 펼침만은 주제가 바뀌면 닫는다: 다른 주제를 다녀왔는데 전에 펼친 질문이 열려 있으면 시안 ①(전부 닫힘)과 어긋난다.
  // 렌더 중에 맞추는 이유는 effect로 하면 열린 채 한 번 그려진 뒤 닫히기 때문이다(React 「prop이 바뀔 때 state 맞추기」)
  const itemsKey = items.map((item) => item.key).join('|');
  const [shownItemsKey, setShownItemsKey] = useState(itemsKey);
  if (shownItemsKey !== itemsKey) {
    setShownItemsKey(itemsKey);
    setOpenKey(null);
  }

  const vote = (key: FaqKey, helpful: boolean) => {
    track('faq_feedback_sent', { faq: key, helpful });
    setVoted((prev) => new Set(prev).add(key));
  };

  return (
    <div className={styles.faqCard}>
      {items.map((item) => (
        <FaqRow
          key={item.key}
          item={item}
          open={openKey === item.key}
          voted={voted.has(item.key)}
          onToggle={() => setOpenKey((cur) => (cur === item.key ? null : item.key))}
          onVote={(helpful) => vote(item.key, helpful)}
        />
      ))}
    </div>
  );
}

function FaqRow({
  item,
  open,
  voted,
  onToggle,
  onVote,
}: {
  item: FaqItem;
  open: boolean;
  voted: boolean;
  onToggle: () => void;
  onVote: (helpful: boolean) => void;
}) {
  const buttonId = useId();
  const panelId = useId();
  const promptId = useId();
  const thanksRef = useRef<HTMLParagraphElement>(null);
  // 누른 평가 버튼이 사라지면 포커스가 body로 떨어진다 — 바로 그 자리의 완료 문구로 옮긴다.
  // 방금 보낸 때만 옮긴다: 보낸 질문을 다시 열 때마다 포커스를 뺏으면 펼침 버튼에서 튕겨 나간다.
  const [justVoted, setJustVoted] = useState(false);
  useEffect(() => {
    if (justVoted) thanksRef.current?.focus();
  }, [justVoted]);

  const answer = (helpful: boolean) => {
    onVote(helpful);
    setJustVoted(true);
  };

  return (
    <div className={styles.faqRow}>
      <h3 className={styles.faqHeading}>
        <button
          type="button"
          id={buttonId}
          className={styles.faqToggle}
          aria-expanded={open}
          // 닫힌 패널은 DOM에 없다 — 없는 id를 가리키지 않게 열렸을 때만 잇는다
          aria-controls={open ? panelId : undefined}
          onClick={onToggle}
        >
          <span className={styles.faqQuestion}>{item.q}</span>
          <ChevronDown aria-hidden className={styles.faqChevron} />
        </button>
      </h3>
      {open && (
        <div id={panelId} role="region" aria-labelledby={buttonId} className={styles.faqPanel}>
          <p className={styles.faqAnswer}>{item.a}</p>
          {item.steps && (
            // role="list"는 겹쳐 보여도 지우지 않는다 — list-style: none인 목록은 Safari가 목록 의미를 벗겨,
            // 번호 원까지 aria-hidden인 이 목록은 VoiceOver에서 순서가 전혀 읽히지 않는다(PluginDownloadPanel과 같다)
            <ol role="list" className={styles.faqSteps}>
              {item.steps.map((step, i) => (
                <li key={step} className={styles.faqStep}>
                  {/* 번호는 ol이 이미 읽는다 — 원은 보이기만 한다 */}
                  <span className={styles.faqStepNum} aria-hidden>
                    {i + 1}
                  </span>
                  {step}
                </li>
              ))}
            </ol>
          )}
          <div className={styles.faqFooter}>
            {voted ? (
              <p ref={thanksRef} tabIndex={-1} className={styles.faqThanks}>
                <Check aria-hidden />
                피드백을 보냈어요 · 더 나은 답변에 반영할게요
              </p>
            ) : (
              <div role="group" aria-labelledby={promptId} className={styles.faqVote}>
                <span id={promptId}>이 답변이 도움이 되었나요?</span>
                <button
                  type="button"
                  className={styles.voteButton}
                  aria-label="도움이 되었어요"
                  onClick={() => answer(true)}
                >
                  <ThumbsUp aria-hidden />
                </button>
                <button
                  type="button"
                  className={styles.voteButton}
                  data-tone="down"
                  aria-label="도움이 되지 않았어요"
                  onClick={() => answer(false)}
                >
                  <ThumbsDown aria-hidden />
                </button>
              </div>
            )}
            {item.link && (
              <Link href={item.link.href} className={styles.faqLink}>
                {item.link.label}
              </Link>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
