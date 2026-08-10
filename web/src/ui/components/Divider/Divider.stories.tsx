import type { Meta, StoryObj } from '@storybook/react-vite';
import { Divider } from './Divider';

const meta: Meta<typeof Divider> = {
  title: 'Components/Divider',
  component: Divider,
  parameters: { layout: 'padded' },
  argTypes: { orientation: { control: 'inline-radio', options: ['horizontal', 'vertical'] } },
};
export default meta;
type Story = StoryObj<typeof Divider>;

export const Horizontal: Story = {
  render: () => (
    <div style={{ color: 'var(--pc-color-text-secondary)' }}>
      <p>위 영역</p>
      <Divider />
      <p>아래 영역</p>
    </div>
  ),
};

export const Vertical: Story = {
  render: () => (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        height: 40,
        color: 'var(--pc-color-text-secondary)',
      }}
    >
      <span>왼쪽</span>
      <Divider orientation="vertical" />
      <span>오른쪽</span>
    </div>
  ),
};
