import { render } from '@testing-library/react';
import { RichText } from '../../../src/admin/components/RichText';

describe('RichText', () => {
  it('renders the bundle tags as elements and decodes entities, with no innerHTML', () => {
    const { container } = render(<p><RichText text="Found <b>3</b> &amp; <span>more</span> <i>x</i>" /></p>);
    expect(container.querySelector('b')).toHaveTextContent('3');
    expect(container.querySelector('span')).toHaveTextContent('more');
    expect(container.querySelector('i')).toBeNull();
    expect(container.textContent).toBe('Found 3 & more <i>x</i>');
  });

  it('prints plain text unchanged', () => {
    const { container } = render(<RichText text="Just words" />);
    expect(container.textContent).toBe('Just words');
  });
});
