/**
 * Client-side java.text.MessageFormat (spec A5), covering what the bundles use:
 * {n}, {n,number[,integer|percent]}, {n,choice,...}, '' for an apostrophe and
 * 'quoted' literals. Parity cases: MessageFormatRegressionTest and
 * MessagePlaceholderContractTest, mirrored in test/admin/i18n/format.test.ts.
 */
export type MessageArg = string | number | boolean | null | undefined;

function numberText(value: number, locale: string, style: string | undefined): string {
  if (style === 'integer') {
    return new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(value);
  }
  if (style === 'percent') {
    return new Intl.NumberFormat(locale, { style: 'percent' }).format(value);
  }
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 3 }).format(value);
}

/** The index just past the `}` matching the `{` before `from`; quotes and nested braces respected. */
function closingBrace(pattern: string, from: number): number {
  let depth = 1;
  let quoted = false;
  for (let i = from; i < pattern.length; i++) {
    const c = pattern.charAt(i);
    if (c === "'") {
      if (pattern.charAt(i + 1) === "'") {
        i++;
      } else {
        quoted = !quoted;
      }
    } else if (!quoted && c === '{') {
      depth++;
    } else if (!quoted && c === '}') {
      depth--;
      if (depth === 0) {
        return i;
      }
    }
  }
  throw new Error(`Unmatched braces in message pattern: ${pattern}`);
}

/** Splits `text` at top-level `sep` characters (not inside braces). */
function splitTop(text: string, sep: string, max: number): string[] {
  const parts: string[] = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < text.length && parts.length < max - 1; i++) {
    const c = text.charAt(i);
    if (c === '{') {
      depth++;
    } else if (c === '}') {
      depth--;
    } else if (c === sep && depth === 0) {
      parts.push(text.slice(start, i));
      start = i + 1;
    }
  }
  parts.push(text.slice(start));
  return parts;
}

function choose(style: string, value: number, args: readonly MessageArg[], locale: string): string {
  let result = '';
  let first = true;
  for (const piece of splitTop(style, '|', Number.MAX_SAFE_INTEGER)) {
    const match = /^([^#<≤]*)([#<≤])(.*)$/s.exec(piece);
    if (match === null) {
      throw new Error(`Bad choice pattern: ${style}`);
    }
    const limit = Number(match[1]);
    const passes = match[2] === '<' ? value > limit : value >= limit;
    if (first || passes) {
      result = match[3] ?? '';
    }
    if (!passes && !first) {
      break;
    }
    first = false;
  }
  return result.includes('{') ? formatMessage(result, args, locale) : result;
}

function element(body: string, args: readonly MessageArg[], locale: string): string {
  const [indexText = '', type, style] = splitTop(body, ',', 3);
  const index = Number(indexText.trim());
  if (!Number.isInteger(index) || index < 0) {
    throw new Error(`Bad argument index in {${body}}`);
  }
  if (index >= args.length) {
    return `{${indexText}}`;
  }
  const arg = args[index];
  const kind = type?.trim();
  if (kind === 'choice') {
    return choose(style ?? '', Number(arg), args, locale);
  }
  if (kind === 'number') {
    return numberText(Number(arg), locale, style?.trim());
  }
  return typeof arg === 'number' ? numberText(arg, locale, undefined) : String(arg);
}

/** Formats `pattern` the way the server's MessageFormat does; see the file comment. */
export function formatMessage(pattern: string, args: readonly MessageArg[], locale = 'en'): string {
  let out = '';
  let quoted = false;
  for (let i = 0; i < pattern.length; i++) {
    const c = pattern.charAt(i);
    if (c === "'") {
      if (pattern.charAt(i + 1) === "'") {
        out += "'";
        i++;
      } else {
        quoted = !quoted;
      }
    } else if (!quoted && c === '{') {
      const end = closingBrace(pattern, i + 1);
      out += element(pattern.slice(i + 1, end), args, locale);
      i = end;
    } else {
      out += c;
    }
  }
  return out;
}
