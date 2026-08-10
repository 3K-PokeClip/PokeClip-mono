import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { Slider } from './Slider';
import { VStack } from '../Stack';
import { Text } from '../Text';

const meta: Meta<typeof Slider> = {
  title: 'Components/Slider',
  component: Slider,
  parameters: { layout: 'padded' },
};
export default meta;
type Story = StoryObj<typeof Slider>;

export const Default: Story = {
  render: function Default() {
    const [v, setV] = useState(40);
    return (
      <VStack gap={3} style={{ width: 320 }}>
        <Text tone="secondary" size="sm">
          볼륨: {v}
        </Text>
        <Slider value={v} onValueChange={setV} label="볼륨" />
      </VStack>
    );
  },
};
