import { formatMessage } from '../../../src/admin/i18n/format';
import { realMessages } from '../support';

/*
 * Mirrors MessageFormatRegressionTest and MessagePlaceholderContractTest: the cases that
 * decide whether a bundle value renders as written.
 */
describe('formatMessage', () => {
  it('substitutes {n} and drops surplus arguments', () => {
    expect(formatMessage('Category {0} created', ['News', 'unused'])).toBe('Category News created');
  });

  it('leaves a placeholder with no argument in place, as MessageFormat does', () => {
    expect(formatMessage('{0} and {1}', ['a'])).toBe('a and {1}');
  });

  it("renders '' as one apostrophe", () => {
    expect(formatMessage("Passwords don''t match {0}", ['x'])).toBe("Passwords don't match x");
  });

  it('swallows a lone apostrophe and what it quotes (why the bundles double them)', () => {
    expect(formatMessage("It's {0}", ['x'])).toBe('Its {0}');
  });

  it('keeps a quoted {0} literal', () => {
    expect(formatMessage("'{0}' is a placeholder, {0} is a value", ['v'])).toBe('{0} is a placeholder, v is a value');
  });

  it('formats a number argument with grouping (the "2,026" trap) but a string as is', () => {
    expect(formatMessage('{0}', [2026])).toBe('2,026');
    expect(formatMessage('{0}', ['2026'])).toBe('2026');
  });

  it('formats {n,number,integer}', () => {
    expect(formatMessage('{0,number,integer} posts', [1234.6])).toBe('1,235 posts');
  });

  it.each([
    [0, 'No posts'],
    [1, '1 post'],
    [2, '2 posts'],
    [1500, '1,500 posts']
  ])('chooses a plural for %s', (count, expected) => {
    expect(formatMessage('{0,choice,0#No posts|1#1 post|1<{0,number,integer} posts}', [count])).toBe(expected);
  });

  it('chooses on a later argument and keeps surrounding text', () => {
    expect(formatMessage('{1,choice,1#1 entry matches|1<{1} entries match} "{0}"', ['q', 3])).toBe('3 entries match "q"');
  });

  it('keeps a plain choice result and uses the first piece below every limit', () => {
    expect(formatMessage('{0,choice,0#none|1#one}', [-5])).toBe('none');
  });

  it('formats percent and rejects a malformed pattern', () => {
    expect(formatMessage('{0,number,percent}', [0.5])).toBe('50%');
    expect(() => formatMessage('{0', ['x'])).toThrow('Unmatched braces');
    expect(() => formatMessage('{x}', ['x'])).toThrow('Bad argument index');
    expect(() => formatMessage('{0,choice,nonsense}', [1])).toThrow('Bad choice pattern');
  });

  it('formats null and boolean arguments as Java prints them', () => {
    expect(formatMessage('{0} {1}', [null, true])).toBe('null true');
  });

  it('takes the "<" and nested-quote forms inside a choice', () => {
    expect(formatMessage("{0,choice,0#none|0<it''s {0}}", [2])).toBe("it's 2");
  });

  it('treats a lone closing brace as text', () => {
    expect(formatMessage('a } b {0}', ['x'])).toBe('a } b x');
  });
});

describe('the real bundle through formatMessage', () => {
  const dummy = (pattern: string) => [...pattern.matchAll(/\{\s*(\d+)\s*[,}]/g)]
    .reduce((n, m) => Math.max(n, Number(m[1]) + 1), 0);

  it('formats every key without a malformed pattern or a literal doubled apostrophe', () => {
    const failures: string[] = [];
    for (const [key, pattern] of Object.entries(realMessages)) {
      const args = Array.from({ length: dummy(pattern) }, (_v, i) => (i === 0 ? 2 : `arg${String(i)}`));
      try {
        // Without arguments the server returns the value verbatim (alwaysUseMessageFormat is false).
        const resolved = args.length === 0 ? pattern : formatMessage(pattern, args);
        if (args.length > 0 && resolved.includes("''")) {
          failures.push(`${key}: ${resolved}`);
        }
      } catch (error) {
        failures.push(`${key}: ${String(error)}`);
      }
    }
    expect(failures).toEqual([]);
  });

  it('actually contains apostrophes (the check above would otherwise pass vacuously)', () => {
    expect(Object.values(realMessages).some((v) => v.includes("'"))).toBe(true);
  });

  it('renders the footer and selection strings as the server would', () => {
    expect(formatMessage(realMessages['footer.version'] ?? '', ['1.2.3'])).toBe('Pressroom 1.2.3');
    expect(formatMessage(realMessages['selection.count'] ?? '', [3])).toBe('3 selected');
  });
});
