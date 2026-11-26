/** The crop rectangle in pixels of the displayed image: what POST .../crop takes. */
export interface CropRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

interface Box {
  left: number;
  top: number;
  width: number;
  height: number;
}

/** The part of a Cropper.js v2 <cropper-selection> the maths reads (offsets within the canvas). */
export interface SelectionBox {
  x: number;
  y: number;
  width: number;
  height: number;
}

/**
 * The selection in natural pixels (MediaFileEdit.jsp's cropRectInNaturalPixels). The stored
 * dimensions already describe the orientation-corrected image the browser shows, so the scale is
 * natural size over rendered size. Null when nothing is laid out yet or the size is unknown.
 */
export function cropRectInNaturalPixels(
  selection: SelectionBox, canvas: Box, image: Box, natural: { width: number; height: number }
): CropRect | null {
  if (!(image.width > 0) || !(image.height > 0) || !(natural.width > 0) || !(natural.height > 0)) {
    return null;
  }
  const scaleX = natural.width / image.width;
  const scaleY = natural.height / image.height;
  return {
    x: Math.round((selection.x + canvas.left - image.left) * scaleX),
    y: Math.round((selection.y + canvas.top - image.top) * scaleY),
    width: Math.round(selection.width * scaleX),
    height: Math.round(selection.height * scaleY)
  };
}

/** The slice of a <cropper-selection> element seedSelection drives. */
export interface SelectionElement extends SelectionBox {
  initialCoverage?: number;
  $change?: (x: number, y: number, width: number, height: number) => void;
}

/**
 * Cropper v2 sizes its initial selection from the canvas at connect time and never again, so a
 * canvas laid out later (a modal still fading in) gets an invisible 0x0 selection. Seeds it
 * once the canvas has a size, and only while the selection is still empty.
 */
export function seedSelection(canvas: { offsetWidth: number; offsetHeight: number }, selection: SelectionElement): void {
  if (typeof selection.$change !== 'function' || (selection.width > 0 && selection.height > 0)) {
    return;
  }
  let coverage = selection.initialCoverage ?? 0.9;
  if (!(coverage > 0) || coverage > 1) {
    coverage = 0.9;
  }
  const width = canvas.offsetWidth * coverage;
  const height = canvas.offsetHeight * coverage;
  if (!(width > 0) || !(height > 0)) {
    return;
  }
  selection.$change((canvas.offsetWidth - width) / 2, (canvas.offsetHeight - height) / 2, width, height);
}

let loading: Promise<void> | undefined;

/**
 * Loads Cropper.js v2 (the webjar the JSP loaded) once and resolves when its custom elements
 * are defined. A failed load is forgotten so the next attempt retries.
 */
export function loadCropper(contextPath: string): Promise<void> {
  loading ??= new Promise<void>((resolve, reject) => {
    if (customElements.get('cropper-canvas') !== undefined) {
      resolve();
      return;
    }
    const script = document.createElement('script');
    script.src = `${contextPath}/webjars/cropperjs/2.2.0/dist/cropper.min.js`;
    script.onload = () => { void customElements.whenDefined('cropper-canvas').then(() => { resolve(); }); };
    script.onerror = () => { loading = undefined; reject(new Error('cropper')); };
    document.head.append(script);
  });
  return loading;
}
