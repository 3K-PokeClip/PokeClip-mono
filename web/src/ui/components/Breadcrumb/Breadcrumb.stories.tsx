import type { Meta, StoryObj } from '@storybook/react-vite';
import { Breadcrumb } from './Breadcrumb';

const meta: Meta<typeof Breadcrumb> = {
  title: 'Components/Breadcrumb',
  component: Breadcrumb,
  parameters: { layout: 'padded' },
};
export default meta;
type Story = StoryObj<typeof Breadcrumb>;

export const Default: Story = {
  render: () => (
    <Breadcrumb>
      <Breadcrumb.Item href="#">홈</Breadcrumb.Item>
      <Breadcrumb.Item href="#">클립</Breadcrumb.Item>
      <Breadcrumb.Item href="#">버추얼</Breadcrumb.Item>
      <Breadcrumb.Item current>여기는 비키니 시티</Breadcrumb.Item>
    </Breadcrumb>
  ),
};
