import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { Tag } from './Tag';
import { HStack } from '../Stack';

const meta: Meta<typeof Tag> = {
  title: 'Components/Tag',
  component: Tag,
  parameters: { layout: 'centered' },
  args: { children: '저스트 채팅', variant: 'soft' },
  argTypes: {
    variant: { control: 'inline-radio', options: ['soft', 'outline', 'solid'] },
    size: { control: 'inline-radio', options: ['sm', 'md'] },
  },
};
export default meta;
type Story = StoryObj<typeof Tag>;

export const Playground: Story = {};

export const Removable: Story = {
  render: function Removable() {
    const [tags, setTags] = useState(['저스트 채팅', '단독', '뉴비', '버추얼']);
    return (
      <HStack gap={2} wrap>
        {tags.map((t) => (
          <Tag key={t} onRemove={() => setTags(tags.filter((x) => x !== t))}>
            {t}
          </Tag>
        ))}
      </HStack>
    );
  },
};
