import type { HomeLinks } from './useHomeMockState';

// 첫 클립까지 가는 길(POK-378) — 방송 기록이 없는 계정의 큰 칸이 그린다. DOM을 모르는 순수 모델.
// 방송 채널·플러그인은 순서가 없어 둘 다 버튼을 내민다. 방송은 웹에서 켤 수 없어 버튼이 없다.
// 방송 채널은 치지직·SOOP·씨미 모두 되지만, 웹이 연동 상태를 읽을 수 있는 건 아직 치지직(/api/chzzk-link)뿐이다 —
// 문구는 플랫폼을 가리지 않고, 완료 판정만 치지직으로 한다. SOOP·씨미 창구가 생기면 여기서 함께 본다.

export type SetupStepId = 'chzzk' | 'plugin' | 'broadcast' | 'publish';

export interface SetupStep {
  id: SetupStepId;
  title: string;
  /** 지금 단계일 때만 보이는 한 줄 */
  desc: string;
  /** 끝났을 때 제목 옆에 붙는 한 줄 */
  doneNote: string;
  done: boolean;
  /**
   * 연동 상태를 못 읽었다(null) — 「안 함」과 다르다. 이미 연동한 사용자에게 다시 연동하라고 하지 않게 버튼을 내밀지 않고
   * 「확인 중」으로 둔다. 연결 상태 카드와 같은 규칙(리뷰 #299)
   */
  unknown: boolean;
  action?: { label: string; href: string };
}

export function setupSteps(links: HomeLinks, hasHistory: boolean): SetupStep[] {
  return [
    {
      id: 'chzzk',
      title: '방송 채널 연동',
      desc: '방송 플랫폼을 연결하면 방송을 알아서 찾아요',
      doneNote: `${links.chzzk?.channelName ?? '채널'} 연결됨`,
      done: links.chzzk?.linked === true,
      unknown: links.chzzk === null,
      action: { label: '채널 연동', href: '/settings/channels' },
    },
    {
      id: 'plugin',
      title: 'OBS 플러그인 연결',
      desc: '연동 코드 8자리를 OBS에 넣어요',
      doneNote: '연동 코드 발급됨',
      done: links.streamKeyIssued === true,
      unknown: links.streamKeyIssued === null,
      action: { label: '코드 받기', href: '/settings/plugin' },
    },
    {
      id: 'broadcast',
      title: '첫 방송 켜기',
      desc: 'OBS에서 방송을 켜면 감지가 시작돼요',
      doneNote: '첫 방송 완료',
      done: hasHistory,
      unknown: false,
    },
    {
      id: 'publish',
      title: '첫 클립 올리기',
      desc: '고른 장면을 세로 클립으로 만들어 유튜브에 올려요',
      doneNote: '첫 클립 발행',
      // 방송 기록이 없는 계정만 이 칸을 보므로 아직 올린 클립이 있을 수 없다
      done: false,
      unknown: false,
      action:
        links.youtube?.linked === true
          ? undefined
          : { label: '유튜브 연동', href: '/settings/channels' },
    },
  ];
}

/** 방송 채널·플러그인이 끝났고 방송만 남았다 — 큰 칸이 「기다리는 중」으로 바뀐다 */
export function isWaitingForBroadcast(links: HomeLinks, hasHistory: boolean): boolean {
  return links.chzzk?.linked === true && links.streamKeyIssued === true && !hasHistory;
}
