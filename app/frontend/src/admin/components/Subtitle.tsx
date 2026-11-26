import type { ReactNode } from 'react';
import { plainText } from '../i18n/messages';

const MARKUP = /<(span|b)>(.*?)<\/\1>/g;

/**
 * A message value with the two inline tags the subtitles carry (`<span>handle</span>`,
 * `<b>handle</b>`) as React elements, and its entities decoded. Nothing else is parsed:
 * any other markup in the text stays text, so a handle cannot inject anything.
 */
export function markedText(value: string): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0;
  for (const match of value.matchAll(MARKUP)) {
    if (match.index > last) {
      out.push(plainText(value.slice(last, match.index)));
    }
    const text = plainText(match[2] ?? '');
    out.push(match[1] === 'b' ? <b key={match.index}>{text}</b> : <span key={match.index}>{text}</span>);
    last = match.index + match[0].length;
  }
  if (last < value.length) {
    out.push(plainText(value.slice(last)));
  }
  return out;
}

/** `p.subtitle`: the line under a screen's title. */
export function Subtitle({ text }: { text: string }) {
  return <p className="subtitle">{markedText(text)}</p>;
}
