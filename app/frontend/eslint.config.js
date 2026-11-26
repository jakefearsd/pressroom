import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import tseslint from 'typescript-eslint';

// Lints the React admin only (src/admin, test/admin): the editor and reader
// bundles are plain JS covered by node:test and are not part of this gate.
export default tseslint.config(
  { ignores: ['**/node_modules/**', 'node/**', 'coverage/**', 'src/admin/api/schema.d.ts'] },
  {
    files: ['src/admin/**/*.{ts,tsx}', 'test/admin/**/*.{ts,tsx}', 'vitest.config.ts'],
    extends: [js.configs.recommended, ...tseslint.configs.strict],
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    plugins: { 'react-hooks': reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // dangerouslySetInnerHTML is for server-sanitised HTML only (spec A5);
      // the allowlist lands with the first preview screen.
      'no-restricted-syntax': ['error', {
        selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']",
        message: 'dangerouslySetInnerHTML is allowed only for server-sanitised HTML (previews).'
      }]
    }
  }
);
