import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse, type HttpHandler } from 'msw';
import { setupServer } from 'msw/node';
import type { RouteObject } from 'react-router';
import { Outlet, useLocation } from 'react-router';
import type { ReactElement } from 'react';
import { adminBoot, editorBoot, renderRoutes } from '../support';

export const API = 'http://pressroom.test/pressroom/api/v1';
export const BLOG = `${API}/weblogs/blog`;

export const server = setupServer();

/** Starts the MSW server for a describe block; unhandled requests fail the test. */
export function useMockApi() {
  beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
  afterEach(() => { server.resetHandlers(); });
  afterAll(() => { server.close(); });
}

/** /v1/me for a member of `blog` with this permission (POST, EDIT_DRAFT, ADMIN), or a site admin. */
export function meHandler(permission: 'POST' | 'EDIT_DRAFT' | 'ADMIN' = 'POST', globalAdmin = false): HttpHandler {
  return http.get(`${API}/me`, () => HttpResponse.json({
    userName: 'jake', screenName: 'Jake', kind: 'PERSON', globalAdmin, tokenScope: {},
    weblogs: [{ handle: 'blog', name: 'The Blog', permission }]
  }));
}

/** /v1/meta with analytics on or off and a trash retention. */
export function metaHandler(analytics = false, trashRetentionDays = 30): HttpHandler {
  return http.get(`${API}/meta`, () => HttpResponse.json({
    version: '1', features: { customThemes: false, groupBlogging: false, userWeblogCreation: false, uploads: true,
      readerSignIn: false, analytics },
    limits: { maxBodyBytes: 1, maxListLimit: 200, entryTextMaxChars: 1 }, trashRetentionDays
  }));
}

export function problem(status: number, code: string, detail: string, errors?: { field: string; message: string }[]) {
  return HttpResponse.json({ status, code, detail, ...(errors === undefined ? {} : { errors }) }, {
    status, headers: { 'Content-Type': 'application/problem+json' }
  });
}

/** Shows the router's current path and search, so a test can assert where a click went. */
export function Where() {
  const location = useLocation();
  return <span data-testid="where">{location.pathname + location.search}</span>;
}

/** Renders `screen` at `/weblogs/:handle/<tail>` inside a router that also shows the location. */
export function renderScreen(path: string, element: ReactElement, at: string, boot = editorBoot) {
  const routes: RouteObject[] = [{
    element: <><Where /><Outlet /></>,
    children: [
      { path, element },
      { path: '/weblogs/:handle/entries/:id', element: <p>the editor</p> }
    ]
  }];
  return renderRoutes(routes, at, { boot });
}

export const asAdmin = adminBoot;

export async function whereIs(): Promise<string> {
  await waitFor(() => { expect(screen.getByTestId('where')).toBeInTheDocument(); });
  return screen.getByTestId('where').textContent;
}
