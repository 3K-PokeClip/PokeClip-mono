import { resolve } from 'node:path';
import type { StorybookConfig } from '@storybook/react-vite';

const config: StorybookConfig = {
  stories: ['../src/ui/**/*.stories.@(ts|tsx)'],
  addons: ['@storybook/addon-a11y', '@storybook/addon-mcp', '@storybook/addon-docs'],
  framework: { name: '@storybook/react-vite', options: {} },
  core: { disableTelemetry: true },
  typescript: {
    reactDocgen: 'react-docgen-typescript',
    reactDocgenTypescriptOptions: {
      tsconfigPath: resolve(import.meta.dirname, '../tsconfig.json'),
      include: ['**/src/ui/**/*.{ts,tsx}'],
      exclude: ['**/*.stories.tsx', '**/*.test.tsx', '**/test/**'],
    },
  },
  async viteFinal(config) {
    config.resolve = {
      ...config.resolve,
      // tsconfig의 "@/*" → "src/*" 매핑을 Storybook Vite에도 맞춘다
      alias: { ...config.resolve?.alias, '@': resolve(import.meta.dirname, '../src') },
    };
    return config;
  },
};

export default config;
