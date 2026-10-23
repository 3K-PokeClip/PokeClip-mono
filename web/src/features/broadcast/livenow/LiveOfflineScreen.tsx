'use client';

import Image from 'next/image';
import Link from 'next/link';
import { Button } from '@/ui/components/Button';
import { LinkButton } from '@/ui/components/LinkButton';
import { useChannelRelation } from '@/features/channel/ChannelScope';
import { isOwnerOnlyPath } from '@/features/channel/ownerOnlyPaths';
import styles from './LiveOfflineScreen.module.css';

// 경로는 리터럴이다(Side·홈 팁 카드와 같다) — 앱 공용 플러그인 설정 경로 상수는 따로 없다.
const PLUGIN_SETTINGS_PATH = '/settings/plugin';

// 디자인 1b 오프라인 프레임 — 방송 중이 아닐 때 라이브 자리에 선다 (POK-227).
// 이 상태가 하는 일은 둘이다: 방송을 켤 플러그인 설정으로 보내고, 지난 방송으로 보낸다.
//
// 시안 갱신으로 티켓 본문의 「마지막 방송 요약 바」·「VOD에서 이어서 정리」는 빠졌다(2026-09-17 결정).
// 1g-2(보관함 · 클립 없음)가 같은 골격이라 그 화면을 만들 때 공용으로 올린다 — 1t(404)는 숫자
// 오버레이가 붙어 같은 골격이 아니다. EmptyState(점선 카드)와는 모양이 달라 그것을 쓰지 않는다.
//
// 문구는 시안 그대로다. 단 시안의 「연동 상태 확인」은 「플러그인 설정」으로 바꿨다 — 웹은 플러그인이
// 지금 붙었는지 모르고(ADR-077 결정 4) 플러그인 화면에도 상태가 없어 「확인」을 약속할 수 없다.
// 「연동을 다시 하면 복구」류는 쓰지 않는다 — 포기한 방송은 새 방송을 켜야
// 다시 붙어서 실연동 뒤에 거짓이 된다(위키 ADR-048).
export function LiveOfflineScreen() {
  // 남의 채널이면 주인 전용 화면으로 가는 버튼은 남기고 비활성으로(POK-390). 주인 전용인지는 홈 팁 카드·
  // 온보딩 투어와 같은 목록(isOwnerOnlyPath)으로 가른다 — 이 화면에 따로 박아 두면 목록이 바뀔 때 혼자 어긋난다
  const blocked = useChannelRelation() === 'EDITOR' && isOwnerOnlyPath(PLUGIN_SETTINGS_PATH);
  return (
    <main className={styles.screen}>
      <div className={styles.wash} aria-hidden />
      <div className={styles.content}>
        <Image
          src="/brand/poki-live-off.webp"
          alt="라이브 신호가 꺼진 포키 캐릭터"
          width={608}
          height={608}
          preload
          className={styles.poki}
        />
        <h1 className={styles.title}>지금은 방송 중이 아니에요</h1>
        <p className={styles.description}>
          방송을 켜면 채팅·시청자 반응을 분석해
          <br />
          하이라이트 카드를 자동으로 만들어 드려요.
        </p>
        <div className={styles.actions}>
          <LinkButton as={Link} href="/broadcast/vod" variant="solid" size="md">
            지난 방송 보기
          </LinkButton>
          {blocked ? (
            <Button variant="ghost" size="md" disabled>
              플러그인 설정
            </Button>
          ) : (
            <LinkButton as={Link} href={PLUGIN_SETTINGS_PATH} variant="ghost" size="md">
              플러그인 설정
            </LinkButton>
          )}
        </div>
      </div>
    </main>
  );
}
