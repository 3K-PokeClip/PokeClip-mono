'use client';

import { useState } from 'react';
import { Download, ExternalLink, TriangleAlert } from 'lucide-react';
import { LinkButton, Tabs, VisuallyHidden } from '@/ui';
import { track } from '@/lib/analytics';
import type { DetectedOs } from '@/lib/detectOs';
import {
  PLUGIN_DOWNLOADS,
  PLUGIN_VERSION,
  RELEASES_PAGE_URL,
  type PluginOs,
} from '@/lib/pluginDownload';
import styles from './PluginSettingsScreen.module.css';

// OS별 설치 파일 받기 (POK-379) — 다운로드 창(모달)의 본문.
// 실제로 하는 순서(받기 → OBS 끄고 설치 → 독에 연동 코드)대로 단계를 세운다 — 버튼은 1단계 안에,
// 서명 경고는 막히는 2단계 바로 옆에 둔다(2026-10-22 배치 시안 B안).
// 주소는 고정 상수(lib/pluginDownload)다 — 웹은 Release를 조회하지 않는다.
// 파일 링크는 target 없이 그대로 연다: GitHub가 Content-Disposition: attachment로 주므로 페이지가 바뀌지 않는다
// (2026-10-21 실측). 새 창은 「모든 버전 보기」 같은 페이지 링크에만 쓴다.
// 「설치됨·연결됨」은 그리지 않는다 — 받았는지·깔았는지 웹은 모른다(POK-377).

const OS_LABEL: Record<PluginOs, string> = { windows: 'Windows', macos: 'macOS' };
const HINT: Record<PluginOs, string> = {
  windows: '설치 파일(.exe) · 64비트 · OBS 32 이상',
  macos: '설치 파일(.pkg) · Apple Silicon·Intel 공용',
};

export function PluginDownloadPanel({ detectedOs }: { detectedOs: DetectedOs }) {
  // 감지는 첫 탭만 정한다 — 사용자가 고른 탭을 늦게 온 감지 결과가 덮지 않는다. 모르면 Windows
  const [chosen, setChosen] = useState<PluginOs | null>(null);
  const os: PluginOs = chosen ?? (detectedOs === 'macos' ? 'macos' : 'windows');

  return (
    <>
      <Tabs value={os} onValueChange={(value) => setChosen(value as PluginOs)}>
        <Tabs.List aria-label="설치할 PC의 운영체제" className={styles.downloadTabs}>
          <Tabs.Trigger value="windows" className={styles.downloadTab}>
            Windows
          </Tabs.Trigger>
          <Tabs.Trigger value="macos" className={styles.downloadTab}>
            macOS
          </Tabs.Trigger>
        </Tabs.List>
        <Tabs.Panel value="windows" className={styles.downloadTabPanel}>
          <OsSteps os="windows" />
        </Tabs.Panel>
        <Tabs.Panel value="macos" className={styles.downloadTabPanel}>
          <OsSteps os="macos" />
        </Tabs.Panel>
      </Tabs>
      <p className={styles.downloadMeta}>
        <span>버전 {PLUGIN_VERSION}</span>
        <ExternalPageLink href={RELEASES_PAGE_URL}>모든 버전 보기</ExternalPageLink>
      </p>
    </>
  );
}

function OsSteps({ os }: { os: PluginOs }) {
  const { kind, url } = PLUGIN_DOWNLOADS[os];

  // role="list"는 겹쳐 보여도 지우지 않는다 — list-style: none인 목록은 Safari가 목록 의미를 벗겨,
  // 번호 원까지 aria-hidden인 VoiceOver 사용자에게 받기 → 설치 → 연동 순서가 전달되지 않는다
  return (
    <ol role="list" className={styles.downloadSteps}>
      <li className={styles.downloadStep}>
        <span className={styles.downloadStepNum} aria-hidden>
          1
        </span>
        <div className={styles.downloadStepBody}>
          <p className={styles.downloadStepTitle}>설치 파일을 받습니다</p>
          <LinkButton
            href={url}
            variant="solid"
            size="md"
            fullWidth
            iconStart={<Download size={16} aria-hidden />}
            onClick={() => track('plugin_download_clicked', { os, kind })}
          >
            다운로드
            <VisuallyHidden>
              {' '}
              — {OS_LABEL[os]} 설치 파일, 버전 {PLUGIN_VERSION}
            </VisuallyHidden>
          </LinkButton>
          <p className={styles.downloadStepHint}>{HINT[os]}</p>
        </div>
      </li>

      <li className={styles.downloadStep}>
        <span className={styles.downloadStepNum} aria-hidden>
          2
        </span>
        <div className={styles.downloadStepBody}>
          <p className={styles.downloadStepTitle}>
            OBS를 끄고 받은 파일을 {os === 'windows' ? '실행합니다' : '엽니다'}
          </p>
          {/* flex 자식은 아이콘·span 둘뿐 — 텍스트 노드를 직접 두면 줄이 갈라진다(.issueWarning 규칙).
              단계 안은 들여쓴 만큼 좁아 두 줄 안에 들게 줄인 문구다 */}
          <div className={styles.issueWarning}>
            <TriangleAlert aria-hidden className={styles.issueWarningIcon} />
            {os === 'windows' ? (
              <span>
                서명 전이라 <b>경고가 뜰 수 있어요</b> — Chrome은 「위험한 파일 다운로드」,
                Windows는 「추가 정보 › 실행」
              </span>
            ) : (
              <span>
                Apple 서명 전이라 처음 열 때 <b>막힐 수 있어요</b> — 시스템 설정 › 개인정보 보호 및
                보안 › 「그래도 열기」
              </span>
            )}
          </div>
        </div>
      </li>

      <li className={styles.downloadStep}>
        <span className={styles.downloadStepNum} aria-hidden>
          3
        </span>
        <div className={styles.downloadStepBody}>
          <p className={styles.downloadStepTitle}>OBS 「독 › PokeClip」에 연동 코드를 넣습니다</p>
          <p className={styles.downloadStepHint}>
            연동 코드는 이 화면 위 「연동 코드」에서 발급해요
          </p>
        </div>
      </li>
    </ol>
  );
}

function ExternalPageLink({ href, children }: { href: string; children: string }) {
  return (
    <a href={href} target="_blank" rel="noopener noreferrer">
      {children}
      <ExternalLink size={11} aria-hidden className={styles.downloadLinkIcon} />
      <VisuallyHidden> (새 창)</VisuallyHidden>
    </a>
  );
}
