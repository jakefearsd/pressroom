import type { HTMLAttributes, Ref } from 'react';

/** Cropper.js v2's custom elements as JSX: attributes are free-form (action, plain, initial-coverage, ...). */
type CropperElement = HTMLAttributes<HTMLElement> & { ref?: Ref<HTMLElement> } & Record<string, unknown>;

declare module 'react' {
  namespace JSX {
    interface IntrinsicElements {
      'cropper-canvas': CropperElement;
      'cropper-image': CropperElement;
      'cropper-shade': CropperElement;
      'cropper-handle': CropperElement;
      'cropper-selection': CropperElement;
      'cropper-grid': CropperElement;
      'cropper-crosshair': CropperElement;
    }
  }
}
