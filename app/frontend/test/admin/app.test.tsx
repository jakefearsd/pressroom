import { render, screen } from '@testing-library/react';
import { createMemoryRouter } from 'react-router';
import { App } from '../../src/admin/App';
import { appRoutes } from '../../src/admin/router';
import { editorBoot } from './support';

describe('App', () => {
  it('degrades to the keys, logged, when the bundle cannot load, and still draws the frame', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const fetchImpl = vi.fn(() => Promise.resolve(new Response('no', { status: 503 })));
    const router = createMemoryRouter(appRoutes, { initialEntries: ['/'] });

    render(<App boot={editorBoot} router={router} fetchImpl={fetchImpl as unknown as typeof fetch} />);

    expect(await screen.findByRole('heading', { name: 'yourWebsites.title' })).toBeInTheDocument();
    expect(error).toHaveBeenCalledWith('Could not load the message bundle', expect.any(Error));
    expect(warn).toHaveBeenCalledWith('Missing message key: yourWebsites.title');
    error.mockRestore();
    warn.mockRestore();
  });

  it('renders nothing until the bundle is in', () => {
    const fetchImpl = vi.fn(() => new Promise<Response>(() => undefined));
    const { container } = render(<App boot={editorBoot} fetchImpl={fetchImpl as unknown as typeof fetch} />);
    expect(container).toBeEmptyDOMElement();
  });
});
