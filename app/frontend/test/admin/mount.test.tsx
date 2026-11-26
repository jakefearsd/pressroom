import { act, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { mount } from '../../src/admin/mount';
import { sample, shellDocument } from './fixtures';

const server = setupServer(
  http.get('http://localhost:3000/pressroom/pressroom-ui/app-messages.rol', () => HttpResponse.json({ 'yourWebsites.title': 'Your weblogs' }))
);
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterAll(() => { server.close(); });

describe('mount', () => {
  it('renders the app frame into #pressroom-root at the app basename', async () => {
    window.history.replaceState({}, '', '/pressroom/pressroom-ui/app/');
    const doc = shellDocument(JSON.stringify({
      ...sample, contextPath: '/pressroom',
      messagesUrl: 'http://localhost:3000/pressroom/pressroom-ui/app-messages.rol?locale=en&v=1.2.3'
    }));
    const root = doc.createElement('div');
    root.id = 'pressroom-root';
    doc.body.append(root);

    await act(async () => mount(doc));

    await waitFor(() => { expect(root.querySelector('h1')?.textContent).toBe('Your weblogs'); });
    expect(root.querySelector('#adminRail')).not.toBeNull();
  });

  it('fails loudly when the shell has no root element', () => {
    expect(() => mount(shellDocument(JSON.stringify(sample)))).toThrow('Missing #pressroom-root');
  });
});
