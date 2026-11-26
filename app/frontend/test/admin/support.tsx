import { readFileSync } from 'node:fs';
import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router';
import type { ReactElement } from 'react';
import { AppProviders } from '../../src/admin/context';
import type { Bootstrap } from '../../src/admin/bootstrap';
import { MessagesProvider, type Messages } from '../../src/admin/i18n/messages';
import { sample } from './fixtures';

/** Parses a Java .properties file (continuations, \uXXXX, comments): enough for ApplicationResources. */
export function parseProperties(source: string): Record<string, string> {
  const out: Record<string, string> = {};
  const lines = source.split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    let line = (lines[i] ?? '').replace(/^\s+/, '');
    if (line === '' || line.startsWith('#') || line.startsWith('!')) {
      continue;
    }
    while (/(?<!\\)(\\\\)*\\$/.test(line) && i + 1 < lines.length) {
      line = line.slice(0, -1) + (lines[++i] ?? '').replace(/^\s+/, '');
    }
    const split = /^((?:\\.|[^=:\s\\])+)\s*[=:\s]\s*(.*)$/s.exec(line);
    if (split === null) {
      continue;
    }
    const unescape = (s: string) => s.replace(/\\u([0-9a-fA-F]{4})|\\(.)/g, (_m, hex: string | undefined, ch: string | undefined) =>
      hex !== undefined ? String.fromCharCode(parseInt(hex, 16)) : ch === 'n' ? '\n' : ch === 't' ? '\t' : (ch ?? ''));
    out[unescape(split[1] ?? '')] = unescape(split[2] ?? '');
  }
  return out;
}

export const BUNDLE_PATH = '../src/main/resources/ApplicationResources.properties';

/** The real default bundle: tests render the messages people would read, and a missing key shows. */
export const realMessages: Messages = parseProperties(readFileSync(BUNDLE_PATH, 'utf8'));

export const adminBoot: Bootstrap = { ...sample, contextPath: 'http://pressroom.test/pressroom',
  user: { ...sample.user, globalPermission: 'admin' } };
export const editorBoot: Bootstrap = { ...sample, contextPath: 'http://pressroom.test/pressroom' };

/** Renders inside bootstrap + API + messages providers (no router); rerender keeps the providers. */
export function renderInApp(ui: ReactElement, options: { boot?: Bootstrap; messages?: Messages; fetchImpl?: typeof fetch } = {}) {
  const { boot = editorBoot, messages = realMessages, fetchImpl } = options;
  const wrap = (inner: ReactElement) => (
    <AppProviders boot={boot} {...(fetchImpl ? { fetchImpl } : {})}>
      <MessagesProvider messages={messages} locale="en">{inner}</MessagesProvider>
    </AppProviders>
  );
  const result = render(wrap(ui));
  return { ...result, rerender: (next: ReactElement) => { result.rerender(wrap(next)); } };
}

/** Renders routes in a memory router (no basename: paths are app-relative) inside the providers. */
export function renderRoutes(routes: RouteObject[], at: string, options: { boot?: Bootstrap; messages?: Messages } = {}) {
  const router = createMemoryRouter(routes, { initialEntries: [at] });
  return { router, ...renderInApp(<RouterProvider router={router} />, options) };
}
