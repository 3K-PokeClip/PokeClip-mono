'use client';

import { useState, type Ref } from 'react';
import { ArrowRight, Check, Download } from 'lucide-react';
import { Button } from '@/ui';
import styles from './PluginSettingsScreen.module.css';

// 플러그인을 연결한 적 없는 회원이 보는 연결 순서 (2026-11-19 시안 C 「순서대로만 열리기」, POK-609).
// 설치 → OBS 다시 켜기 → 코드 넣기를 한 카드의 세 단계로 세우고, 2번을 눌러야 3번 「코드 받기」가 열린다.
//
// 잠그는 까닭: 2026-11-18~19 새 회원 넷이 화면에 들어온 지 3~10초 만에, 설치 전에 코드부터 받았다. 코드는 10분짜리라
// 설치(OBS 끄기 · 서명 경고 · 다시 켜기)하는 사이에 죽는다 — 둘은 받고 그대로 떠났고, 둘은 5분 뒤에야 설치 파일을 눌렀다.
// 순서를 보여 주기만 하는 안(설치 안내를 맨 위에 둔 시안 C · 2026-11-18)은 「코드 발급」이 바로 아래에서 똑같이 진한
// 버튼이라 막지 못했다. 그래서 10분이 「OBS에 PokeClip 창이 떠 있는」 때부터 흐르게 한다.
//
// 「다시 켰어요」는 회원의 말일 뿐이다 — 웹은 설치도 OBS 실행도 모른다(web/README 「플러그인 화면 계약」).
// 거짓으로 눌러도 코드는 나온다. 막으려는 것은 속임이 아니라 읽지 않고 누르는 것이다.
//
// 진한 버튼은 지금 할 단계 하나에만 두고, 그 단계를 따라 옮긴다(2026-11-20 시안 A 「진한 버튼이 따라온다」, POK-613):
// 처음엔 1번 「다운로드」, 다운로드를 누르면 2번 「다시 켰어요」, 다시 켰다고 하면 3번 「코드 받기」. 나머지는 테두리
// 버튼이다. 처음 판은 다운로드를 눌러도 진한 버튼이 「다운로드」에 남고 2번은 연한(soft) 버튼이라, 새 회원 둘이 다운로드만
// 4~5번 누르고 2번을 버튼으로 알아보지 못한 채 떠났다(2026-11-20 17·18번 — 코드 발급 0).
export function ConnectStepsCard({
  codeIssued,
  codeWaiting,
  busy,
  titleRef,
  onDownload,
  onIssue,
}: {
  /** 코드를 받은 적이 있다(연결은 안 됐다) — 그 코드는 다시 볼 수 없으니 새로 받으라고 말한다. */
  codeIssued: boolean;
  /**
   * 방금 받은 코드가 아직 살아 있다(창을 닫았어도) — 새로 받으라고 하지 않고, 넣으면 여기서 알려 준다고 말한다.
   * 화면은 그 코드가 쓰이기를 지켜보고 있다(useStreamKeyState). 코드를 복사하고 창부터 닫은 사람에게 「새로 받아
   * 주세요」는 실패처럼 읽혔다(PR #539 리뷰).
   */
  codeWaiting: boolean;
  /** 창을 닫았는데 초점이 갈 곳이 없을 때 화면이 이 제목으로 초점을 옮긴다(PluginSettingsScreen) */
  titleRef?: Ref<HTMLHeadingElement>;
  /** 발급 진행 중 — 이중 클릭 방지. */
  busy?: boolean;
  onDownload: () => void;
  onIssue: () => void;
}) {
  // 둘 다 이 화면을 떠나면 잊는다 — 다시 들어온 회원은 OBS가 지금도 켜져 있는지부터 다시 본다.
  // 「다운로드를 눌렀다」도 회원이 다운로드 창을 연 것일 뿐이다(받았는지·설치했는지는 웹이 모른다). 진한 버튼을
  // 다음 단계로 넘기는 표시로만 쓴다 — 창을 닫고 돌아왔을 때 다음 할 일이 진하게 서 있게.
  const [downloaded, setDownloaded] = useState(false);
  const [restarted, setRestarted] = useState(false);
  // 지금 할 단계. 다시 켰다고 하면 설치는 끝난 것으로 본다(이미 설치하고 온 회원은 다운로드를 건너뛴다)
  const current = restarted ? 3 : downloaded ? 2 : 1;
  const stepVariant = (step: 1 | 2 | 3) => (current === step ? 'solid' : 'outline');

  // role="list"는 겹쳐 보여도 지우지 않는다 — list-style: none인 목록은 Safari가 목록 의미를 벗긴다(PluginDownloadPanel과 같다)
  return (
    <section className={styles.card} aria-labelledby="connect-steps-title">
      <h2 className={styles.cardTitle} id="connect-steps-title" ref={titleRef} tabIndex={-1}>
        OBS 플러그인 연결
      </h2>
      <p className={styles.connectLead}>
        {codeWaiting
          ? '받은 코드를 OBS의 PokeClip 창에 넣으면 여기서 바로 알려 드려요'
          : codeIssued
            ? '전에 받은 코드는 다시 볼 수 없어요. 아래 순서대로 새로 받아 주세요'
            : '아래 순서대로 하면 연결돼요'}
      </p>
      <ol role="list" className={styles.connectSteps}>
        <li
          className={styles.connectStep}
          data-state={current > 1 ? 'done' : undefined}
          aria-current={current === 1 ? 'step' : undefined}
        >
          <span className={styles.connectStepNum} aria-hidden>
            {current > 1 ? <Check /> : 1}
          </span>
          <div className={styles.connectStepBody}>
            <p className={styles.connectStepTitle}>플러그인을 설치해요</p>
            <p className={styles.connectStepHint}>OBS를 끄고, 받은 파일을 실행해요</p>
          </div>
          <Button
            variant={stepVariant(1)}
            size="sm"
            className={styles.connectStepAction}
            iconStart={<Download size={14} aria-hidden />}
            aria-haspopup="dialog"
            onClick={() => {
              setDownloaded(true);
              onDownload();
            }}
          >
            {downloaded ? '다시 받기' : '다운로드'}
          </Button>
        </li>

        <li
          className={styles.connectStep}
          data-state={restarted ? 'done' : undefined}
          aria-current={current === 2 ? 'step' : undefined}
        >
          <span className={styles.connectStepNum} aria-hidden>
            {restarted ? <Check /> : 2}
          </span>
          <div className={styles.connectStepBody}>
            <p className={styles.connectStepTitle}>OBS를 다시 켜요</p>
            {/* 플러그인은 처음 실행될 때 한 번 독을 펼친다(obs-plugin plugin-main.cpp의 dockIntroShown) — 닫았으면 메뉴에서 연다 */}
            <p className={styles.connectStepHint}>
              PokeClip 창이 저절로 떠요. 안 보이면 OBS 메뉴 「독 › PokeClip」
            </p>
          </div>
          {/* 상태는 라벨이 말한다(「다시 켰어요」 → 누르면 「아직이에요」로 되돌리기) — 다시 누르면 3번이 도로 잠긴다.
              aria-pressed를 같이 달면 「아직이에요, 눌림」처럼 상태를 두 번, 엇갈리게 읽는다(PR #539 리뷰).
              차례가 아닐 때도 연한(soft) 버튼으로 두지 않는다 — 카드 바탕과 거의 같은 색이라 버튼으로 안 보였다(POK-613) */}
          <Button
            variant={stepVariant(2)}
            size="sm"
            className={styles.connectStepAction}
            iconEnd={restarted ? undefined : <ArrowRight size={14} aria-hidden />}
            onClick={() => setRestarted((v) => !v)}
          >
            {restarted ? '아직이에요' : '다시 켰어요'}
          </Button>
        </li>

        <li
          className={styles.connectStep}
          data-state={restarted ? undefined : 'locked'}
          aria-current={current === 3 ? 'step' : undefined}
        >
          <span className={styles.connectStepNum} aria-hidden>
            3
          </span>
          <div className={styles.connectStepBody}>
            <p className={styles.connectStepTitle}>PokeClip 창에 연동 코드를 넣어요</p>
            <p className={styles.connectStepHint}>
              {restarted
                ? '코드는 받은 뒤 10분 동안만 쓸 수 있어요'
                : '2번을 마치면 받을 수 있어요 · 코드는 10분 동안만 쓸 수 있어요'}
            </p>
          </div>
          {/* 요청 중엔 스피너만 — 라벨은 자리만 지켜 폭이 튀지 않는다(.busyButton). 접근 이름은 aria-label이 대신한다 */}
          <Button
            variant={stepVariant(3)}
            size="sm"
            className={`${styles.connectStepAction} ${styles.busyButton}`}
            disabled={!restarted}
            loading={busy}
            aria-label={busy ? '발급 요청 중' : undefined}
            aria-haspopup="dialog"
            onClick={onIssue}
          >
            코드 받기
          </Button>
        </li>
      </ol>
    </section>
  );
}
