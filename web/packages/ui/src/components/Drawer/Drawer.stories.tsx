import type { Meta, StoryObj } from '@storybook/react-vite';
import { Drawer } from './Drawer';
import { Button } from '../Button';

const meta: Meta<typeof Drawer> = {
  title: 'Components/Drawer',
  component: Drawer,
  parameters: { layout: 'centered' },
  argTypes: { side: { control: 'inline-radio', options: ['left', 'right'] } },
};
export default meta;
type Story = StoryObj<typeof Drawer>;

export const Default: Story = {
  render: () => (
    <Drawer side="right">
      <Drawer.Trigger>
        <Button variant="outline">채팅 열기</Button>
      </Drawer.Trigger>
      <Drawer.Content>
        <Drawer.Title>실시간 채팅</Drawer.Title>
        <Drawer.Description>오른쪽에서 슬라이드되는 드로어입니다.</Drawer.Description>
        <Drawer.Close>
          <Button variant="ghost">닫기</Button>
        </Drawer.Close>
      </Drawer.Content>
    </Drawer>
  ),
};
