import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { RadioGroup } from './RadioGroup';

const meta: Meta<typeof RadioGroup> = {
  title: 'Components/RadioGroup',
  component: RadioGroup,
  parameters: { layout: 'padded' },
};
export default meta;
type Story = StoryObj<typeof RadioGroup>;

export const Default: Story = {
  render: function Default() {
    const [value, setValue] = useState('follow');
    return (
      <RadioGroup value={value} onValueChange={setValue} aria-label="구독 유형">
        <RadioGroup.Item value="follow" label="팔로우 (무료)" />
        <RadioGroup.Item value="tier1" label="구독 티어 1" />
        <RadioGroup.Item value="tier2" label="구독 티어 2" />
        <RadioGroup.Item value="tier3" label="구독 티어 3 (준비 중)" disabled />
      </RadioGroup>
    );
  },
};
