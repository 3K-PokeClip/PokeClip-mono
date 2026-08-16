import { useEffect, type ReactNode } from 'react';
import type { Decorator, Preview } from '@storybook/react-vite';
import '../src/styles/global.css';

function ThemeApplier({ theme, children }: { theme: 'light' | 'dark'; children: ReactNode }) {
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    document.body.style.background = 'var(--pc-color-bg-canvas)';
  }, [theme]);
  return <>{children}</>;
}

const withTheme: Decorator = (Story, context) => {
  const theme = (context.globals.theme as 'light' | 'dark') ?? 'dark';
  return (
    <ThemeApplier theme={theme}>
      <Story />
    </ThemeApplier>
  );
};

const preview: Preview = {
  initialGlobals: { theme: 'dark' },
  globalTypes: {
    theme: {
      description: 'Global theme',
      toolbar: {
        title: 'Theme',
        icon: 'paintbrush',
        items: [
          { value: 'dark', title: 'Dark', icon: 'moon' },
          { value: 'light', title: 'Light', icon: 'sun' },
        ],
        dynamicTitle: true,
      },
    },
  },
  parameters: {
    layout: 'centered',
    backgrounds: { disabled: true },
    controls: { matchers: { color: /(background|color)$/i, date: /Date$/i } },
  },
  decorators: [withTheme],
};

export default preview;
