import type { HomeLinks } from './useHomeMockState';

// 첫 클립까지 가는 길(POK-378) — 방송 기록이 없는 계정의 큰 칸이 그린다. DOM을 모르는 순수 모델.
// 방송 채널·플러그인은 순서가 없어 둘 다 버튼을 내민다. 방송은 웹에서 켤 수 없어 버튼이 없다.
// 플러그인 단계는 서버의 연결 시각(코드가 OBS에 들어간 때)이 있어야 끝난다(POK-608). 코드 발급은 끝이 아니다.
// 방송 채널 단계는 치지직·SOOP 둘 중 하나라도 연동이면 끝난다(POK-396). 씨미 창구가 생기면 여기서 함께 본다.
// 하나를 못 읽어도 다른 하나가 연동이면 끝났다고 한다. 못 읽은 것이 있고 연동은 없으면 「확인 중」이다:
// 못 읽은 쪽이 연동돼 있을 수 있어 「안 함」으로 단정하지 않는다.

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

export function setupSteps(links: HomeLinks): SetupStep[] {
  /** 코드는 받았는데 연결 시각이 없다 — 플러그인 없이 코드만 받았거나, 받고도 OBS에 넣지 않았다 */
  const codeOnly = links.streamKeyIssued === true && links.pluginPaired === false;
  return [
    {
      id: 'chzzk',
      title: '방송 채널 연동',
      desc: '방송 플랫폼을 연결하면 방송을 알아서 찾아요',
      doneNote: channelDoneNote(links),
      done: isChannelLinked(links),
      unknown: !isChannelLinked(links) && (links.chzzk === null || links.soop === null),
      action: { label: '채널 연동', href: '/settings/channels' },
    },
    {
      id: 'plugin',
      title: 'OBS 플러그인 연결',
      // 설치가 먼저다 — 「코드 받기」였을 때 새 회원이 플러그인 없이 10분짜리 코드부터 받고 떠났다(POK-609).
      // 코드를 받은 적이 있으면 남은 일(OBS에 넣기)만 말한다
      desc: codeOnly
        ? '코드를 OBS의 PokeClip 창에 넣으면 연결돼요'
        : '플러그인을 설치하고 연동 코드로 연결해요',
      doneNote: '연결 완료',
      // 완료는 서버의 연결 시각으로만 센다(POK-608). 코드 발급은 연결이 아니다: 키는 첫 발급 때 생긴다
      done: links.pluginPaired === true,
      unknown: links.pluginPaired === null,
      // 버튼 폭이 104u로 고정이라(.trackAction) 줄 제목(OBS 플러그인 연결)에 기대 짧게 쓴다 — 연결 상태 카드와 같은 말
      action: { label: codeOnly ? '연결하기' : '설치하기', href: '/settings/plugin' },
    },
    {
      id: 'broadcast',
      title: '첫 방송 켜기',
      desc: 'OBS에서 방송을 켜면 감지가 시작돼요',
      doneNote: '첫 방송 완료',
      // 방송 기록이 없는 계정만 이 길을 보므로 방송·클립 단계는 아직 끝났을 수 없다
      done: false,
      unknown: false,
    },
    {
      id: 'publish',
      title: '첫 클립 올리기',
      desc: '고른 장면을 세로 클립으로 만들어 유튜브에 올려요',
      doneNote: '첫 클립 발행',
      done: false,
      unknown: false,
      // 버튼이 없다 — 이 줄은 지금 단계가 되지 못한다(앞의 방송 단계가 늘 남는다). 유튜브 권유는 방송만 남았을 때
      // 큰 칸 아래 한 줄이 맡는다(리뷰 #299 3차)
    },
  ];
}

/**
 * 방송 채널·플러그인이 끝났고 방송만 남았다 — 큰 칸이 「기다리는 중」으로 바뀐다.
 * 플러그인은 연결돼야 끝이다(POK-608): 코드 발급으로 셌을 때는 플러그인 없는 회원에게 「이제 방송만 켜면 돼요」라고 했다
 */
export function isWaitingForBroadcast(links: HomeLinks): boolean {
  return isChannelLinked(links) && links.pluginPaired === true;
}

function isChannelLinked(links: HomeLinks): boolean {
  return links.chzzk?.linked === true || links.soop?.linked === true;
}

/** 치지직만이면 지금처럼 채널 이름, SOOP이 끼면 플랫폼 이름으로 적는다 */
function channelDoneNote(links: HomeLinks): string {
  const chzzk = links.chzzk?.linked === true;
  const soop = links.soop?.linked === true;
  if (chzzk && soop) return '치지직 · SOOP 연결됨';
  if (soop) return 'SOOP 연결됨';
  return `${links.chzzk?.channelName ?? '채널'} 연결됨`;
}
