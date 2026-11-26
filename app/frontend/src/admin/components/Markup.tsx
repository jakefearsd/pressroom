import { createElement, Fragment, type ReactNode } from 'react';
import { plainText } from '../i18n/messages';

const TAG = /<(\/?)(b|i|span|p|a)(?:\s+href\s*=\s*(?:"([^"]*)"|'([^']*)'))?>/g;

/** A link target a message may carry: an http(s) URL or a path, never a script. */
const SAFE_HREF = /^(https?:\/\/|\/)/i;

interface Open {
  tag: string | null;
  children: ReactNode[];
  /** Set for an `a`: its safe target, or undefined when the message's target was refused. */
  href?: string | undefined;
}

/**
 * The ONE renderer of a bundle message that carries its own light markup (`Manage templates of
 * <b>{0}</b>`, `<p>...</p>`, `<a href="{0}">View it</a>`): the JSP printed it as HTML. This builds
 * React elements for the tags the bundles use (b, i, span, p, and a with a safe href; no other
 * attribute) and prints everything else as text, with HTML entities decoded, so it needs no
 * dangerouslySetInnerHTML and a `<script>` in an argument stays text. A link whose target is not
 * an http(s) URL or a path loses the link and keeps its text. A tag that is never closed is
 * closed at the end.
 */
export function Markup({ text }: { text: string }) {
  const root: Open = { tag: null, children: [] };
  const stack: Open[] = [root];
  let last = 0;
  let key = 0;
  const top = (): Open => stack[stack.length - 1] ?? root;
  const close = () => {
    const done = stack.pop();
    if (done?.tag !== null && done !== undefined) {
      if (done.tag === 'a') {
        top().children.push(done.href === undefined
          ? <Fragment key={key++}>{done.children}</Fragment>
          : <a key={key++} href={done.href}>{done.children}</a>);
      } else {
        top().children.push(createElement(done.tag ?? 'span', { key: key++ }, ...done.children));
      }
    }
  };
  for (const match of text.matchAll(TAG)) {
    const at = match.index;
    if (at > last) {
      top().children.push(plainText(text.slice(last, at)));
    }
    last = at + match[0].length;
    if (match[1] === '/') {
      if (stack.length > 1 && top().tag === match[2]) {
        close();
      }
    } else {
      const target = plainText(match[3] ?? match[4] ?? '');
      stack.push({ tag: match[2] ?? 'span', children: [],
        ...(match[2] === 'a' ? { href: SAFE_HREF.test(target) ? target : undefined } : {}) });
    }
  }
  if (last < text.length) {
    top().children.push(plainText(text.slice(last)));
  }
  while (stack.length > 1) {
    close();
  }
  return <>{root.children}</>;
}

/** Escapes a user-typed message argument so Markup prints it as text, never as one of its tags. */
export function markupArg(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
