import { fixupPluginRules } from '@eslint/compat';
import js from '@eslint/js';
import eslintReactNative from 'eslint-plugin-react-native';
import typescriptEslint from 'typescript-eslint';

export default typescriptEslint.config(
  {
    ignores: [
      'node_modules/**',
      'lib/**',
      'research/**',
      '**/*.config.js',
      '**/.eslintrc.js',
      '**/babel.config.js',
      '**/metro.config.js',
      '**/jest.config.js',
      '**/.prettierrc.js',
      '**/react-native.config.js',
      '**/release.config.cjs'
    ],
  },
  js.configs.recommended,
  {
    files: ['**/*.{ts,tsx,d.ts}'],
    extends: [...typescriptEslint.configs.recommendedTypeChecked],
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      '@typescript-eslint/require-await': 'off',
    },
  },
  // react-native
  {
    name: 'eslint-plugin-react-native',
    files: ['**/*.{js,jsx,ts,tsx,d.ts}'],
    plugins: {
      'react-native': fixupPluginRules({
        rules: eslintReactNative.rules,
      }),
    },
    rules: {
      ...eslintReactNative.configs.all.rules,
      'react-native/sort-styles': 'off',
      'react-native/no-inline-styles': 'warn',
      'react-native/no-color-literals': 'off',
      'react-native/no-unused-styles': 'off',
    },
  }
);
