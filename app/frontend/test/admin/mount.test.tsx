import { act } from '@testing-library/react';
import { mount } from '../../src/admin/mount';
import { sample, shellDocument } from './fixtures';

describe('mount', () => {
  it('renders the app into #pressroom-root', async () => {
    const doc = shellDocument(JSON.stringify(sample));
    const root = doc.createElement('div');
    root.id = 'pressroom-root';
    doc.body.append(root);

    await act(async () => mount(doc));

    expect(root.querySelector('#pressroom-app')?.textContent).toBe('Pressroom 1.2.3');
  });

  it('fails loudly when the shell has no root element', () => {
    expect(() => mount(shellDocument(JSON.stringify(sample)))).toThrow('Missing #pressroom-root');
  });
});
