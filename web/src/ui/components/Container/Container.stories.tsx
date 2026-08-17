import type { Meta, StoryObj } from '@storybook/react-vite';
import { Container } from './Container';

const meta: Meta<typeof Container> = {
  title: 'Components/Container',
  component: Container,
  parameters: { layout: 'fullscreen' },
  args: { size: 'sm' },
  argTypes: { size: { control: 'select', options: ['sm', 'md', 'lg', 'xl'] } },
};
export default meta;
type Story = StoryObj<typeof Container>;

export const Default: Story = {
  render: (args) => (
    <Container {...args}>
      <p style={{ color: 'var(--pc-color-text-secondary)', padding: 'var(--pc-space-4)' }}>
        Container로 중앙 정렬 + 최대 너비 제한. size prop으로 폭을 조절합니다.
      </p>
    </Container>
  ),
};
