import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { Switch } from './Switch';
import { VStack } from '../Stack';

const meta: Meta<typeof Switch> = {
  title: 'Components/Switch',
  component: Switch,
  parameters: { layout: 'padded' },
  args: { label: '라이브 알림' },
  argTypes: {
    size: { control: 'inline-radio', options: ['sm', 'md'] },
    disabled: { control: 'boolean' },
  },
};
export default meta;
type Story = StoryObj<typeof Switch>;

export const Playground: Story = {
  render: function Playground(args) {
    const [on, setOn] = useState(true);
    return <Switch {...args} checked={on} onChange={(e) => setOn(e.target.checked)} />;
  },
};

export const Controlled: Story = {
  render: function Controlled() {
    const [live, setLive] = useState(true);
    const [auto, setAuto] = useState(false);
    return (
      <VStack gap={3} align="start">
        <Switch label="라이브 알림" checked={live} onChange={(e) => setLive(e.target.checked)} />
        <Switch label="자동 재생" checked={auto} onChange={(e) => setAuto(e.target.checked)} />
      </VStack>
    );
  },
};
