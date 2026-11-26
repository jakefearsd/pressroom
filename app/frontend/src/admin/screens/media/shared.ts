import type { MediaView } from '../../api/media';

/** The admin thumbnail, as AdminUrls.mediaThumbnail builds it. */
export const thumbnailUrl = (file: MediaView): string => `${file.url ?? ''}?t=true`;

export const isImage = (file: Pick<MediaView, 'contentType'>): boolean => (file.contentType ?? '').startsWith('image/');

/** What the MediaFileEdit crop section needs: a JPEG or PNG the server stored dimensions for. */
export const isCroppable = (file: MediaView): boolean =>
  ['image/jpeg', 'image/png'].includes(file.contentType ?? '') && (file.width ?? 0) > 0 && (file.height ?? 0) > 0;

/** MediaFileAdd's formatSize: bytes up to GB, one decimal above bytes. */
export function formatSize(bytes: number): string {
  const units = ['B', 'KB', 'MB', 'GB'];
  let unit = 0;
  let size = bytes;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit++;
  }
  return `${unit === 0 ? String(size) : size.toFixed(1)} ${units[unit] ?? 'B'}`;
}

/** The library's route, optionally on a folder. */
export const libraryPath = (handle: string, directoryId?: string): string =>
  `/weblogs/${encodeURIComponent(handle)}/media${directoryId === undefined || directoryId === '' ? '' : `?dir=${encodeURIComponent(directoryId)}`}`;

/** Splits a tags field on spaces and commas, as the edit form's do. */
export const splitTags = (value: string): string[] => value.split(/[\s,]+/).filter((tag) => tag !== '');
