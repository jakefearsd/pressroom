import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { formatMessage, type MessageArg } from './format';

export type Messages = Readonly<Record<string, string>>;
export type Translate = (key: string, ...args: MessageArg[]) => string;

const warned = new Set<string>();

/**
 * The `t(key, ...args)` for a bundle. Like the server's source (alwaysUseMessageFormat
 * false), a call with no arguments returns the value verbatim; with arguments it is
 * run through MessageFormat. A missing key renders as the key and is logged once.
 */
export function createTranslate(messages: Messages, locale = 'en'): Translate {
  return (key, ...args) => {
    const pattern = messages[key];
    if (pattern === undefined) {
      if (!warned.has(key)) {
        warned.add(key);
        console.warn(`Missing message key: ${key}`);
      }
      return key;
    }
    return args.length === 0 ? pattern : formatMessage(pattern, args, locale);
  };
}

/** Test hook: forget which missing keys were already logged. */
export function resetMissingKeyLog(): void {
  warned.clear();
}

/** Fetches the resolved bundle (a flat JSON object) from the bootstrap's messagesUrl. */
export async function loadMessages(url: string, fetchImpl: typeof fetch = fetch): Promise<Messages> {
  const response = await fetchImpl(url, { credentials: 'same-origin' });
  if (!response.ok) {
    throw new Error(`Messages request failed: ${String(response.status)}`);
  }
  return (await response.json()) as Messages;
}

/** Message values written for JSP carry HTML entities (tabbedmenu.weblog is "Create &amp; edit"). */
export function plainText(value: string): string {
  return value
    .replace(/&#(\d+);/g, (_m, code: string) => String.fromCodePoint(Number(code)))
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&amp;/g, '&');
}

const MessagesContext = createContext<Translate | null>(null);

export function MessagesProvider(
  { messages, locale, children }: { messages: Messages; locale: string; children: ReactNode }
) {
  const t = useMemo(() => createTranslate(messages, locale), [messages, locale]);
  return <MessagesContext.Provider value={t}>{children}</MessagesContext.Provider>;
}

/** The translate function; throws outside a MessagesProvider. */
export function useT(): Translate {
  const t = useContext(MessagesContext);
  if (t === null) {
    throw new Error('useT needs a MessagesProvider');
  }
  return t;
}
