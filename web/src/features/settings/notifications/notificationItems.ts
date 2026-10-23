// 설정 · 알림의 항목 정의.
//
// 목록은 지금 기능에서 실제로 일어나는 일만 든다 — 서버에 그 사건이 생기는 자리가 있는 것만 남겼다.
// 사용량 한도(자동 처리 시간)·결제 실패·주간 성과 요약은 그 기능이 없어 뺐다. 편집자 초대는 끌 수 없는
// 알림이라 고를 것이 없어 이 목록에 두지 않는다. 이메일 채널은 없앴다 — 알림은 앱 안(헤더 종)으로만 온다.
//
// 화면에 그리는 순서가 곧 배열 순서다 — 처음 값이 「중요」인 둘(방송·업로드가 막히는 일)을 위에 둔다.

/** 알림 하나를 받는 방식 — 끔 / 받기 / 중요(방송 중 방해 금지를 켜도 온다) */
export type NotificationMode = 'off' | 'on' | 'important';

export interface NotificationItem {
  key: string;
  title: string;
  desc: string;
  /** 처음 값 — 우리가 정해 두고 사용자가 바꾼다 */
  defaultMode: NotificationMode;
  /** 편집을 맡은 채널이 있는 계정에만 보인다 */
  editorOnly?: boolean;
}

const ITEMS = [
  {
    // 채팅 수집이 재시도로 안 풀려 재연동이 필요할 때. 플러그인 끊김은 서버가 알 수 없어 넣지 않는다
    key: 'channel-link-broken',
    title: '방송 채널 연동 만료 · 채팅 수집 끊김',
    desc: '채팅을 모으지 못하게 됐을 때 바로 알려요',
    defaultMode: 'important',
  },
  {
    // 구글이 토큰 갱신을 거부(invalid_grant)해 연동이 끊겼을 때. 업로드 실패는 아래 「클립 결과」가 맡는다
    key: 'youtube-link-expired',
    title: '유튜브 연동 만료',
    desc: '다시 연결해야 업로드할 수 있을 때',
    defaultMode: 'important',
  },
  {
    key: 'clip-result',
    title: '클립 렌더 · 업로드 결과',
    desc: '완료됐을 때와 실패했을 때',
    defaultMode: 'on',
  },
  {
    // 편집자 업로드에는 승인 단계가 없다 — 내 채널에 무엇이 올라갔는지는 스트리머가 알아야 한다
    key: 'editor-uploaded',
    title: '편집자가 내 채널에 업로드',
    desc: '편집자가 내 유튜브 채널에 클립을 올리면',
    defaultMode: 'on',
  },
  {
    // VOD 보관은 60일(services/clip Broadcast.VOD_RETENTION)
    key: 'vod-expiring',
    title: 'VOD 삭제 임박 (D-7 · D-3 · D-1)',
    desc: '60일 보관 기간이 끝나기 전에',
    defaultMode: 'on',
  },
  {
    key: 'broadcast-ended',
    title: '방송 종료 · 다시보기 가능',
    desc: '방송이 끝나 다시보기를 볼 수 있을 때',
    defaultMode: 'on',
  },
  {
    // 방송한 본인에게는 필요 없어 편집자에게만 — 맡은 채널이 방송을 시작하면 편집 준비를 한다
    key: 'managed-broadcast-start',
    title: '편집 중인 채널 방송 시작',
    desc: '편집을 맡은 채널에서 방송이 시작되면',
    defaultMode: 'on',
    editorOnly: true,
  },
] as const satisfies readonly NotificationItem[];

/** 항목 키 — 목록에서 뽑아 쓴다(목록에 없는 키를 읽으면 타입이 잡는다) */
export type NotificationKey = (typeof ITEMS)[number]['key'];

/** 넓은 꼴로 내보낸다 — as const 튜플 그대로면 editorOnly가 없는 원소에서 그 값을 읽지 못한다 */
export const NOTIFICATION_ITEMS: readonly (NotificationItem & { key: NotificationKey })[] = ITEMS;

/** 「방송 중 방해 금지」 기본값 — 켜 두면 방송 중에는 「중요」로 고른 알림만 온다 */
export const DO_NOT_DISTURB_DEFAULT = true;
