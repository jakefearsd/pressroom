import { render } from '@testing-library/react';
import { runPool } from '../../../src/admin/api/pool';
import { ApiError } from '../../../src/admin/api/problem';
import { Markup, markupArg } from '../../../src/admin/components/Markup';
import { bannerError, failureLine, weblogAddress } from '../../../src/admin/screens/people/shared';

describe('Markup', () => {
  const html = (text: string) => render(<p><Markup text={text} /></p>).container.firstElementChild?.innerHTML;

  it('builds the four bundle tags, nests them and decodes entities', () => {
    expect(html('Manage <b>a &amp; b</b> in <span>x <i>y</i></span>')).toBe('Manage <b>a &amp; b</b> in <span>x <i>y</i></span>');
  });

  it('prints any other tag, and tags with attributes, as text', () => {
    expect(html('<script>x</script> <b onclick="x">y</b>')).toBe('&lt;script&gt;x&lt;/script&gt; &lt;b onclick="x"&gt;y');
  });

  it('closes an unclosed tag and ignores a stray closing one', () => {
    expect(html('a</b> <b>open')).toBe('a <b>open</b>');
    expect(html('<b>x<span>y</b>z')).toBe('<b>x<span>yz</span></b>');
  });

  it('keeps a user-typed argument from becoming a tag', () => {
    expect(html(`Hi ${markupArg('<b>x</b> & &lt;')}`)).toBe('Hi &lt;b&gt;x&lt;/b&gt; &amp; &amp;lt;');
  });
});

describe('runPool', () => {
  it('runs every item with bounded concurrency, reports progress and collects failures', async () => {
    let live = 0;
    let peak = 0;
    const progress: number[] = [];
    const failures = await runPool([1, 2, 3, 4, 5, 6, 7], 3, async (n) => {
      live++;
      peak = Math.max(peak, live);
      await new Promise((resolve) => setTimeout(resolve, 5));
      live--;
      if (n === 4) {
        throw new Error('no');
      }
    }, (done) => progress.push(done));
    expect(peak).toBe(3);
    expect(progress).toEqual([1, 2, 3, 4, 5, 6, 7]);
    expect(failures.map((f) => f.item)).toEqual([4]);
  });

  it('does nothing for no items', async () => {
    expect(await runPool([], 4, () => Promise.reject(new Error('never')))).toEqual([]);
  });
});

describe('screen helpers', () => {
  it('prints a field refusal by its field messages only, and leaves other errors alone', () => {
    const refused = new ApiError(400, 'x', 'A. B.', [{ field: 'a', message: 'A.' }, { field: 'b', message: 'B.' }]);
    expect((bannerError(refused) as ApiError).messages).toEqual(['A.', 'B.']);
    const plain = new ApiError(409, 'x', 'Whole.', []);
    expect(bannerError(plain)).toBe(plain);
    expect(bannerError(null)).toBeNull();
  });

  it('labels a failure with what it was about', () => {
    expect(failureLine('lee', new ApiError(409, 'x', 'No.', []))).toBe('lee: No.');
    expect(failureLine('lee', new ApiError(500, 'x', '', []))).toBe('lee: x (500)');
    expect(failureLine('lee', new Error('boom'))).toBe('lee: boom');
    expect(failureLine('lee', 'odd')).toBe('lee: odd');
  });

  it('finds the weblog address: own domain first, else under the site', () => {
    expect(weblogAddress('a b', 'b.example.com', 'https://s')).toBe('https://b.example.com/');
    expect(weblogAddress('a b', '', 'https://s')).toBe('https://s/a%20b/');
    expect(weblogAddress('a', undefined, undefined)).toBeUndefined();
  });
});
