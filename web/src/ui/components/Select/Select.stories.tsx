import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { Select } from './Select';

const SORT = [
  { value: 'popular', label: '인기순' },
  { value: 'recent', label: '최신순' },
  { value: 'viewers', label: '시청자순' },
  { value: 'soon', label: '준비 중', disabled: true },
];

const meta: Meta<typeof Select> = {
  title: 'Components/Select',
  component: Select,
  parameters: { layout: 'padded' },
};
export default meta;
type Story = StoryObj<typeof Select>;

export const Default: Story = {
  render: function Default() {
    const [v, setV] = useState<string | undefined>(undefined);
    return (
      <div style={{ width: 240 }}>
        <Select
          options={SORT}
          value={v}
          onValueChange={setV}
          placeholder="정렬 기준"
          aria-label="정렬 기준"
        />
      </div>
    );
  },
};
