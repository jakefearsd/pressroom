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

  it('passes a file that does not use it, and the allowlist starts empty', async () => {
    const [result] = await lint('export const A = () => <p>x</p>;\n', 'src/admin/Example.tsx');
    expect(result?.messages).toEqual([]);
    expect(readFileSync('eslint.config.js', 'utf8')).toMatch(/DANGEROUS_HTML_ALLOWLIST = \[\];/);
  });
});
