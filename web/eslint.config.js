// For more info, see https://github.com/storybookjs/eslint-plugin-storybook#configuration-flat-config-format
import storybook from 'eslint-plugin-storybook';

import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import nextPlugin from '@next/eslint-plugin-next';
import prettierConfig from 'eslint-config-prettier';

export default tseslint.config(
  {
    ignores: [
      '**/dist/',
      '**/.next/',
      '**/storybook-static/',
      '**/coverage/',
      '**/node_modules/',
      'next-env.d.ts',
      '.design-sync/',
      'ds-bundle/',
      '.ds-sync/',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    plugins: { 'react-hooks': reactHooks },
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
    },
  },
  {
    // Node tooling scripts + config files run in Node, not the browser.
    files: ['**/scripts/**/*', '**/*.mjs', '**/*.config.{js,ts,mjs}'],
    languageOptions: {
      globals: {
        console: 'readonly',
        process: 'readonly',
        URL: 'readonly',
        Buffer: 'readonly',
      },
    },
  },
  storybook.configs['flat/recommended'],
  {
    files: ['src/**/*.{ts,tsx}'],
    // 디자인 시스템 소스는 Next 앱 코드가 아니므로 Next 전용 룰에서 제외 (예: Avatar의 <img>)
    ignores: ['src/ui/**'],
    plugins: { '@next/next': nextPlugin },
    rules: {
      ...nextPlugin.configs.recommended.rules,
      ...nextPlugin.configs['core-web-vitals'].rules,
    },
  },
  {
    // 뮤테이션은 세션 세대를 확인하는 useSessionMutation으로만 만든다 (POK-424). react-query의 useMutation을 직접 쓰면
    // 다른 탭이 계정을 바꾼 뒤 온 앞 계정의 응답이 캐시 · 토스트 · 화면(페어링 코드)에 그려진다.
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/lib/useSessionMutation.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          paths: [
            {
              name: '@tanstack/react-query',
              importNames: ['useMutation'],
              message:
                '세션 세대를 확인하는 useSessionMutation(@/lib/useSessionMutation)을 쓴다 (POK-424).',
            },
          ],
        },
      ],
    },
  },
  prettierConfig,
);
