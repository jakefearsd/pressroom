import { attentionOrder } from '../../../src/admin/screens/homeOrder';

const row = (name: string, extra: Record<string, unknown> = {}) => ({
  handle: name.toLowerCase(), name, pendingComments: 0, newInquiries: 0, ...extra
}) as Parameters<typeof attentionOrder>[0];

const sorted = (rows: Parameters<typeof attentionOrder>[0][]) => [...rows].sort(attentionOrder).map((r) => r.name);

describe('attentionOrder', () => {
  it('puts a blog with waiting comments or inquiries above one without', () => {
    expect(sorted([row('Quiet', { lastPublished: '2026-11-20T00:00:00Z' }), row('Busy', { pendingComments: 1 })]))
      .toEqual(['Busy', 'Quiet']);
  });

  it('orders by comments plus inquiries, descending', () => {
    expect(sorted([row('A', { pendingComments: 1, newInquiries: 1 }), row('B', { pendingComments: 3 }),
      row('C', { newInquiries: 1 })])).toEqual(['B', 'A', 'C']);
  });

  it('breaks a tie by last published, newest first', () => {
    expect(sorted([row('Old', { lastPublished: '2026-01-01T00:00:00Z' }), row('New', { lastPublished: '2026-11-01T00:00:00Z' })]))
      .toEqual(['New', 'Old']);
  });

  it('puts a blog that never published after one that did, on a tie', () => {
    expect(sorted([row('Never'), row('Did', { lastPublished: '2020-01-01T00:00:00Z' })])).toEqual(['Did', 'Never']);
  });

  it('falls back to the name when everything ties', () => {
    expect(sorted([row('Zed'), row('Abe')])).toEqual(['Abe', 'Zed']);
    expect(sorted([row('Zed', { lastPublished: '2026-01-01T00:00:00Z' }), row('Abe', { lastPublished: '2026-01-01T00:00:00Z' })]))
      .toEqual(['Abe', 'Zed']);
  });
});
