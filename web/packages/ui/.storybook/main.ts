import { resolve } from 'node:path';
import type { StorybookConfig } from '@storybook/react-vite';
import type { PluginOption } from 'vite';

const libraryBuildPlugins = new Set(['vite:dts', 'pokeclip:build-css']);

function withoutLibraryBuildPlugins(
  plugins: PluginOption[] | undefined,
): Promise<PluginOption[] | undefined> {
  if (!plugins) return Promise.resolve(undefined);

  return Promise.all(plugins).then(async (resolvedPlugins) => {
    const filteredPlugins: PluginOption[] = [];
    for (const plugin of resolvedPlugins) {
      if (Array.isArray(plugin)) {
        filteredPlugins.push(...((await withoutLibraryBuildPlugins(plugin)) ?? []));
      } else if (plugin && !libraryBuildPlugins.has(plugin.name)) {
        filteredPlugins.push(plugin);
      }
    }
    return filteredPlugins;
  });
}

const config: StorybookConfig = {
  stories: ['../src/**/*.stories.@(ts|tsx)'],
  addons: ['@storybook/addon-a11y', '@storybook/addon-mcp', '@storybook/addon-docs'],
  framework: { name: '@storybook/react-vite', options: {} },
  core: { disableTelemetry: true },
  typescript: {
    reactDocgen: 'react-docgen-typescript',
    reactDocgenTypescriptOptions: {
      tsconfigPath: resolve(import.meta.dirname, '../tsconfig.json'),
      include: ['**/src/**/*.{ts,tsx}'],
      exclude: ['**/*.stories.tsx', '**/*.test.tsx', '**/test/**'],
    },
  },
  async viteFinal(config) {
    return {
      ...config,
      // Storybook reuses the package Vite config, but declaration/CSS package emitters
      // must only run for the library build.
      plugins: await withoutLibraryBuildPlugins(config.plugins),
    };
  },
};

export default config;
