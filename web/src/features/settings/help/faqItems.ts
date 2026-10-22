// 디자인 1q 도움말 · 문의의 「자주 묻는 질문」 항목.
//
// 화면에 그리는 순서가 곧 배열 순서다. key는 답변 평가 이벤트(faq_feedback_sent)의 속성으로 나가므로
// 문구를 고쳐도 바꾸지 않는다 — 바꾸면 같은 질문의 평가가 두 갈래로 쪼개진다.
//
// 문항은 2026-10-22 후보 45개에서 사용자가 고른 20개다. 시안 1q의 목업 5문항(자동 처리 시간·SOOP 대기 목록·
// 편집자 승인 등)은 실제 제품과 달라 쓰지 않는다. 답은 「준비 중」인 기능에 날짜를 약속하지 않는다.

/**
 * 질문 주제 — 화면 위 탭의 순서가 곧 배열 순서다. 20문항을 한 줄로 늘어놓으면 찾기 어렵다는 지적(2026-10-23)으로
 * 주제 탭을 두고 한 번에 한 주제만 보인다(시안 A안).
 */
export const FAQ_TOPICS = [
  { key: 'start', label: '시작하기' },
  { key: 'plugin', label: '플러그인' },
  { key: 'channel', label: '채널 · 방송' },
  { key: 'clip', label: '클립 · 영상' },
  { key: 'editor', label: '편집자' },
  { key: 'account', label: '계정' },
] as const;

export type FaqTopic = (typeof FAQ_TOPICS)[number]['key'];

export interface FaqItem {
  key: string;
  topic: FaqTopic;
  q: string;
  a: string;
  /** 따라 하는 순서가 있는 답만 갖는다 — 번호 원으로 그린다 */
  steps?: readonly string[];
  /** 앱 안의 화면만 가리킨다. 가리킬 곳이 없으면 비워 둔다 — 시안의 「○○ 문서」 링크는 문서가 아직 없다 */
  link?: { label: string; href: string };
}

const CHANNELS_LINK = { label: '채널 연동 열기', href: '/settings/channels' };
const PLUGIN_LINK = { label: '플러그인 설정 열기', href: '/settings/plugin' };
const EDITORS_LINK = { label: '편집자 관리 열기', href: '/settings/editors' };

export const FAQ_ITEMS = [
  {
    key: 'requirements',
    topic: 'start',
    q: 'PokeClip을 쓰려면 무엇이 필요한가요?',
    a: 'OBS Studio, 치지직 또는 SOOP 방송 채널, 클립을 올릴 YouTube 채널, 로그인할 Google 계정이 있으면 돼요. 지금은 무료 베타예요.',
  },
  {
    key: 'getting-started',
    topic: 'start',
    q: '처음에는 무엇부터 하면 되나요?',
    a: '아래 순서대로 연결해 두면 다음 방송부터 하이라이트가 쌓여요.',
    steps: [
      '설정 › 채널 연동에서 방송 채널과 YouTube 채널을 연결해요.',
      '설정 › 플러그인에서 OBS 플러그인을 받아 설치해요.',
      '같은 화면에서 연동 코드를 받아 OBS의 PokeClip 독에 입력해요.',
      '방송을 켜면 채팅이 몰린 순간이 하이라이트 카드로 쌓여요.',
    ],
    link: CHANNELS_LINK,
  },
  {
    key: 'replay-tour',
    topic: 'start',
    q: '처음 사용 안내를 다시 볼 수 있나요?',
    a: '위의 「시작 가이드」나 홈 오른쪽 아래 「둘러보기」를 누르면 처음부터 다시 보여요. 본 기록은 브라우저에 남아서, 다른 브라우저나 기기에서는 처음 안내가 다시 떠요.',
  },
  {
    key: 'plugin-download',
    topic: 'plugin',
    q: 'OBS 플러그인은 어디서 받나요?',
    a: '설정 › 플러그인의 「플러그인 다운로드」에서 받아요. Windows(64비트, OBS 32 이상)와 macOS(Apple Silicon·Intel)용이 있어요.',
    link: PLUGIN_LINK,
  },
  {
    key: 'install-warning',
    topic: 'plugin',
    q: '플러그인을 설치할 때 보안 경고가 떠요.',
    a: '아직 공인 코드 서명이 없어서 브라우저와 운영체제가 경고를 한 번 띄워요. PokeClip 설정 화면에서 받은 파일이라면 경고를 넘기고 설치하면 돼요. 설치하는 동안에는 OBS를 꺼 두세요.',
  },
  {
    key: 'pairing-code',
    topic: 'plugin',
    q: '연동 코드가 만료됐다거나 이미 사용했다고 나와요.',
    a: '연동 코드는 발급하고 10분 동안, 한 번만 쓸 수 있어요. 설정 › 플러그인에서 새로 받아 다시 입력해 주세요. 코드는 1분에 3번까지 받을 수 있고, 발급 창을 닫으면 다시 볼 수 없어요.',
    steps: [
      '설정 › 플러그인에서 「연동 코드」를 받아요.',
      'OBS의 PokeClip 독에 8자리 코드(XXXX-XXXX)를 입력해요.',
      '독 위쪽 표시가 「준비됨」으로 바뀌면 연결된 거예요.',
    ],
    link: PLUGIN_LINK,
  },
  {
    key: 'pc-performance',
    topic: 'plugin',
    q: 'PokeClip을 켜면 컴퓨터가 느려지나요?',
    a: '아니요, 컴퓨터는 느려지지 않아요. 영향을 받는 건 인터넷 업로드뿐이에요. PokeClip으로 같은 방송을 한 줄 더 보내기 때문에 업로드 대역폭이 송출 한 줄과 오디오 약 640kbps만큼 더 필요해요. 방송 화질도 그대로예요.',
  },
  {
    key: 'relay-disconnected',
    topic: 'plugin',
    q: 'PokeClip 쪽 송출이 끊기면 방송도 끊기나요?',
    a: '아니요, 방송 플랫폼으로 나가는 방송은 그대로예요. 플러그인이 5초·30초·60초 간격으로 다시 연결을 시도하고, 65분 동안 붙지 못하면 멈춰요.',
  },
  {
    key: 'unlink-channel',
    topic: 'channel',
    q: '방송 채널 연동을 해제하면 지난 방송도 지워지나요?',
    a: '아니요. 해제하면 새 방송의 하이라이트 감지만 멈추고, 지난 방송과 클립은 그대로 남아요.',
  },
  {
    key: 'channel-already-linked',
    topic: 'channel',
    q: '「이미 다른 계정에 연동된 채널이에요」라고 나와요.',
    a: '방송 채널 하나는 PokeClip 계정 하나에만 연동할 수 있어요. 그 계정에서 연동을 해제한 뒤 다시 연동해 주세요.',
    link: CHANNELS_LINK,
  },
  {
    key: 'no-highlight-cards',
    topic: 'channel',
    q: '방송을 켰는데 하이라이트 카드가 안 생겨요.',
    a: '방송 시작 후 약 2분은 평소 채팅량을 재느라 판정하지 않아요. 또 채팅이 잦아들고 20초가 지나야 카드가 만들어져서, 몰린 순간과 카드 사이에 조금 틈이 있어요. 그래도 안 생기면 방송 채널 연동과 OBS 독의 「준비됨」 표시를 확인해 주세요.',
  },
  {
    key: 'retention',
    topic: 'clip',
    q: '다시보기와 하이라이트 카드는 언제까지 남나요?',
    a: '다시보기는 방송 후 60일 동안 볼 수 있어요. 방송 기록과 하이라이트 카드는 기간 없이 계속 남아요. 다만 60일이 지나면 그 방송의 장면으로 새 영상을 만들 수 없으니, 남기고 싶은 장면은 그 안에 영상으로 만들어 두세요.',
  },
  {
    key: 'full-vod-download',
    topic: 'clip',
    q: '방송 전체 영상을 내려받을 수 있나요?',
    a: '방송 전체 다시보기 내려받기는 준비 중이에요. 영상으로 만든 클립은 보관함에서 내려받을 수 있어요.',
    link: { label: '보관함 열기', href: '/clips/library' },
  },
  {
    key: 'landscape-video',
    topic: 'clip',
    q: '가로 영상으로도 만들 수 있나요?',
    a: '지금은 쇼츠용 세로 영상만 만들 수 있어요. 가로 출력은 준비 중이에요.',
  },
  {
    key: 'privacy-after-upload',
    topic: 'clip',
    q: '공개 범위는 올린 뒤에 바꿀 수 있나요?',
    a: '업로드 정보에서 비공개·일부 공개·공개 중에 고른 그대로 올라가요. 올린 뒤에는 PokeClip에서 바꿀 수 없고, YouTube 스튜디오에서 바꿔 주세요. 처음이라면 비공개로 올려 확인한 뒤 공개로 바꾸는 걸 추천해요.',
  },
  {
    key: 'invitation-not-received',
    topic: 'editor',
    q: '편집자가 초대를 못 받았대요.',
    a: '초대 메일은 따로 가지 않아요. 편집자가 PokeClip에 로그인한 뒤 프로필 사진을 눌러 초대를 수락해야 해요. 초대한 이메일이 편집자가 로그인한 주소와 정확히 같아야 하고, 7일이 지나면 다시 초대해야 해요.',
    steps: [
      '편집자가 초대받은 Google 계정으로 PokeClip에 로그인해요.',
      '프로필 사진을 눌러 받은 초대를 확인해요.',
      '「수락」을 누르면 편집자로 합류해요.',
    ],
    link: EDITORS_LINK,
  },
  {
    key: 'editor-permissions',
    topic: 'editor',
    q: '편집자는 무엇을 할 수 있나요?',
    a: '지금은 편집자도 스트리머와 같은 권한으로 클립을 편집하고 스트리머의 YouTube 채널에 올릴 수 있어요. 별도 승인 없이 공개로도 바로 올릴 수 있으니, 믿을 수 있는 사람만 초대해 주세요. 편집자마다 권한을 나누는 기능은 앞으로 만들 예정이에요.',
  },
  {
    key: 'remove-editor',
    topic: 'editor',
    q: '편집자를 내보내려면 어떻게 하나요?',
    a: '설정 › 편집자 관리에서 내보내면 바로 권한이 사라져요. 편집자도 스스로 나갈 수 있어요.',
    link: EDITORS_LINK,
  },
  {
    key: 'profile',
    topic: 'account',
    q: '이름이나 프로필 사진을 바꾸려면 어떻게 하나요?',
    a: '설정 › 계정에서 바꿀 수 있어요. 사진은 JPG·PNG·WebP 5MB 이하로 올리거나 기본 아바타에서 고를 수 있어요. 로그인 이메일은 바꿀 수 없어요.',
    link: { label: '계정 설정 열기', href: '/settings/account' },
  },
  {
    key: 'chat-retention',
    topic: 'account',
    q: '채팅 기록은 얼마나 보관하나요?',
    a: '하이라이트를 찾으려고 받은 채팅·후원 기록은 받은 지 60일이 지나면 지워요.',
  },
] as const satisfies readonly FaqItem[];

/** 평가 이벤트에 싣는 질문 식별자 — 자유 문자열이 아니라 이 목록의 key만 받는다 */
export type FaqKey = (typeof FAQ_ITEMS)[number]['key'];
