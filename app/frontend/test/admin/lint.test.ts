import { readFileSync } from 'node:fs';
import { ESLint } from 'eslint';

/** The lint gate is itself a rule worth pinning: it must refuse dangerouslySetInnerHTML outside the allowlist. */
describe('eslint config', () => {
  const lint = async (code: string, filePath: string) =>
    new ESLint({ cwd: process.cwd() }).lintText(code, { filePath });

  it('forbids dangerouslySetInnerHTML in an ordinary file', async () => {
    const [result] = await lint('export const A = () => <p dangerouslySetInnerHTML={{ __html: "x" }} />;\n', 'src/admin/Example.tsx');
    expect(result?.messages.map((m) => m.ruleId)).toEqual(['no-restricted-syntax']);
    expect(result?.messages[0]?.message).toContain('server-sanitised');
  });

  it('allows it in a file on the allowlist (the page preview), and only there', async () => {
    const code = 'export const A = ({ html }: { html: string }) => <div dangerouslySetInnerHTML={{ __html: html }} />;\n';
    const [allowed] = await lint(code, 'src/admin/screens/pages/PagePreview.tsx');
    expect(allowed?.messages).toEqual([]);
    const [elsewhere] = await lint(code, 'src/admin/screens/pages/PageEditor.tsx');
    expect(elsewhere?.messages.map((m) => m.ruleId)).toEqual(['no-restricted-syntax']);
  });

  it('passes a file that does not use it, and the allowlist names only its files', async () => {
    const [result] = await lint('export const A = () => <p>x</p>;\n', 'src/admin/Example.tsx');
    expect(result?.messages).toEqual([]);
    expect(readFileSync('eslint.config.js', 'utf8')).toMatch(/DANGEROUS_HTML_ALLOWLIST = \['src\/admin\/screens\/pages\/PagePreview\.tsx'\];/);
  });
});
