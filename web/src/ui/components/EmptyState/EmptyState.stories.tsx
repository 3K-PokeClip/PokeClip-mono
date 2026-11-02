import type { Meta, StoryObj } from '@storybook/react-vite';
import { EmptyState } from './EmptyState';

// DS는 lucide를 모른다 — 시안 1f의 비디오 아이콘과 1l ④의 사람+ 아이콘을 인라인 svg로 그린다
// (Button.stories의 Heart 선례). 실제 화면은 lucide <Video size={21} />·<UserPlus size={21} />를 넘긴다.
// a11y 속성은 주지 않는다 — 래퍼가 숨긴다.
const iconProps = {
  width: 21,
  height: 21,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.8,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
} as const;

const VideoIcon = () => (
  <svg {...iconProps}>
    <rect x="2" y="4" width="20" height="16" rx="3" />
    <path d="m10 8.5 6 3.5-6 3.5v-7z" />
  </svg>
);

const UserPlusIcon = () => (
  <svg {...iconProps}>
    <path d="M16 20v-1.6A3.4 3.4 0 0 0 12.6 15H6.4A3.4 3.4 0 0 0 3 18.4V20" />
    <circle cx="9.5" cy="8" r="3.4" />
    <path d="M19 11h4M21 9v4" />
  </svg>
);

const meta: Meta<typeof EmptyState> = {
  title: 'Components/EmptyState',
  component: EmptyState,
  // 카드는 폭을 갖지 않는다 — centered면 설명 max-width(300u)로 오그라드니 본문처럼 전폭을 준다
  parameters: { layout: 'padded' },
  args: {
    title: '아직 지난 방송이 없어요',
    description:
      '방송을 켜면 종료 후 VOD가 여기에 쌓여요. VOD는 60일 동안 보관되고, 만료 전에 풀 영상을 내려받을 수 있어요.',
  },
  argTypes: {
    // ReactNode는 컨트롤로 편집할 수 없다 — 자동 추론되는 object 컨트롤을 끈다
    icon: { control: false },
  },
};
export default meta;
type Story = StoryObj<typeof EmptyState>;

/** 시안 1f — 지난 방송 목록의 빈 상태. */
export const Playground: Story = {
  render: (args) => <EmptyState {...args} icon={<VideoIcon />} />,
};

/** 시안 1l ④ — 편집자 관리의 빈 상태. 카드 안 초대 버튼은 없다 — 진입점은 헤더 버튼 하나다. */
export const Editors: Story = {
  render: (args) => <EmptyState {...args} icon={<UserPlusIcon />} />,
  args: {
    title: '아직 편집자가 없어요',
    description:
      '편집자를 초대하면 하이라이트 검토와 클립 편집을 맡길 수 있어요. 업로드는 기본적으로 내 승인을 거칩니다.',
  },
};
