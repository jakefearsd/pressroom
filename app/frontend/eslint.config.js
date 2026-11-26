import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import tseslint from 'typescript-eslint';

/**
 * Files allowed to use dangerouslySetInnerHTML: server-sanitised HTML only (previews,
 * spec A5). Each addition names its file here and says where the HTML is sanitised:
 *  - PagePreview.tsx: the server's page preview (HTMLSanitizer, the only content boundary).
 */
export const DANGEROUS_HTML_ALLOWLIST = ['src/admin/screens/pages/PagePreview.tsx'];

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
      // the allowlist above names the files that may.
      'no-restricted-syntax': ['error', {
        selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']",
        message: 'dangerouslySetInnerHTML is allowed only for server-sanitised HTML (previews).'
      }]
    }
  },
  ...(DANGEROUS_HTML_ALLOWLIST.length === 0 ? [] : [{
    files: DANGEROUS_HTML_ALLOWLIST,
    rules: { 'no-restricted-syntax': 'off' }
  }])
);
