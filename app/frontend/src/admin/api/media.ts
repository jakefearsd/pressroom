import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../context';
import { call } from './client';
import type { components, paths } from './schema';

export type MediaView = components['schemas']['MediaView'];
export type MediaPatch = components['schemas']['MediaPatch'];
export type MediaCrop = components['schemas']['MediaCrop'];
export type DirectoryView = components['schemas']['DirectoryView'];
export type UploadResponse = components['schemas']['UploadResponse'];

/** What GET .../media can be asked: a folder, name prefix, type (image, video, audio, other), sort. */
export interface MediaFilter {
  directoryId?: string | undefined;
  q?: string | undefined;
  type?: string | undefined;
  sort?: string | undefined;
  /** Only files strictly larger than this many bytes. */
  largerThan?: number | undefined;
  /** Only files carrying any of these tags. */
  tags?: readonly string[] | undefined;
}

type MediaQuery = NonNullable<paths['/v1/weblogs/{handle}/media']['get']['parameters']['query']>;

const mediaKey = (handle: string) => ['media', handle] as const;

/** The weblog's folders, with file counts and the private flag. */
export function useDirectories(handle: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...mediaKey(handle), 'directories'],
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/media/directories', { params: { path: { handle } } }))
  });
}

/** The files a filter keeps (every file, unpaged, as the Media Files screen lists them). */
export function useMediaList(handle: string, filter: MediaFilter, enabled = true) {
  const api = useApi();
  const query = Object.fromEntries(Object.entries(filter).filter(([, v]) =>
    v !== undefined && v !== '' && !(Array.isArray(v) && v.length === 0)));
  return useQuery({
    queryKey: [...mediaKey(handle), 'list', query],
    enabled,
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/media', { params: { path: { handle }, query: query as MediaQuery } }))
  });
}

/** One file, for the edit screen. */
export function useMediaFile(handle: string, id: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...mediaKey(handle), 'file', id],
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/media/{id}', { params: { path: { handle, id } } }))
  });
}

/**
 * Every media write as a plain async function (callers own pending and error state, which a
 * bulk run needs per item); `refresh` drops cached lists, files and folders once a run ends.
 */
export function useMediaActions(handle: string) {
  const api = useApi();
  const queries = useQueryClient();
  return {
    refresh: () => queries.invalidateQueries({ queryKey: mediaKey(handle) }),
    update: (id: string, body: MediaPatch) =>
      call(api.PATCH('/v1/weblogs/{handle}/media/{id}', { params: { path: { handle, id } }, body })),
    remove: (id: string) =>
      call(api.DELETE('/v1/weblogs/{handle}/media/{id}', { params: { path: { handle, id } } })),
    crop: (id: string, body: MediaCrop) =>
      call(api.POST('/v1/weblogs/{handle}/media/{id}/crop', { params: { path: { handle, id } }, body })),
    createDirectory: (name: string) =>
      call(api.POST('/v1/weblogs/{handle}/media/directories', { params: { path: { handle } }, body: { name } })),
    deleteDirectory: (id: string) =>
      call(api.DELETE('/v1/weblogs/{handle}/media/directories/{id}', { params: { path: { handle, id } } })),
    setPrivate: (id: string, isPrivate: boolean) =>
      call(api.PATCH('/v1/weblogs/{handle}/media/directories/{id}', {
        params: { path: { handle, id } }, body: { isPrivate }
      }))
  };
}
