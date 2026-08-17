import type { Meta, StoryObj } from '@storybook/react-vite';
import { Box } from './Box';

const meta: Meta<typeof Box> = {
  title: 'Components/Box',
  component: Box,
  parameters: { layout: 'padded' },
};
export default meta;
type Story = StoryObj<typeof Box>;

export const Default: Story = {
  render: () => (
    <Box
      style={{
        padding: 'var(--pc-space-4)',
        background: 'var(--pc-color-bg-surface-raised)',
        borderRadius: 'var(--pc-radius-md)',
        color: 'var(--pc-color-text-secondary)',
      }}
    >
      Box는 토큰 기반 스타일을 받는 다형(polymorphic) 기본 요소입니다.
    </Box>
  ),
};

export const AsElement: Story = {
  render: () => (
    <Box as="p" style={{ color: 'var(--pc-color-text-secondary)' }}>
      as="p" 로 렌더링된 문단입니다.
    </Box>
  ),
};
