import { AudioBrowser as TrackPlayer } from '../NativeAudioBrowser';
import { resolveTrackAssets, type Track } from './queue';
;

// https://developer.android.com/training/cars/media/create-media-browser/content-styles

export type AndroidAutoGetItemEvent = {
  /** Unique request identifier */
  requestId: string;
  /** The media ID to fetch */
  id: string;
};

export type AndroidAutoGetChildrenEvent = {
  /** Unique request identifier */
  requestId: string;
  /** The ID to get children for */
  id: string;
  /** Page number for pagination */
  page: number;
  /** Maximum items per page */
  pageSize: number;
};

export type AndroidAutoGetSearchResultsEvent = {
  /** Unique request identifier */
  requestId: string;
  /** The search query */
  query: string;
  /** Optional search parameters */
  extras?: Record<string, any>;
  /** Page number for pagination */
  page: number;
  /** Maximum items per page */
  pageSize: number;
};

const debug = false ? console.log : undefined;

function onGetItem(
  callback: (event: { id: string }) => Promise<Track | undefined>,
): () => void {
  return TrackPlayer.onGetItemRequest(
    async ({ requestId, ...data }: AndroidAutoGetItemEvent) => {
      const track = await callback(data);
      debug?.('onGetItem callback resolved', { requestId, track });
      if (track) {
        TrackPlayer.resolveGetItemRequest(requestId, track);
      } else {
        // Return minimal track indicating "not found"
        TrackPlayer.resolveGetItemRequest(requestId, {
          mediaId: data.id,
          title: 'Item not found',
        });
      }
    },
  );
}

function onGetChildren(
  callback: (event: { id: string; page: number; pageSize: number }) => Promise<{
    children: Track[];
    total?: number;
  }>,
): () => void {
  return TrackPlayer.onGetChildrenRequest(
    async ({ requestId, ...data }: AndroidAutoGetChildrenEvent) => {
      const result = await callback(data);
      const children = result.children.map(resolveTrackAssets);
      debug?.('onGetChildren callback resolved', {
        id: data.id,
        tracks: children,
      });
      TrackPlayer.resolveGetChildrenRequest(
        requestId,
        children,
        result.total ?? children.length,
      );
    },
  );
}

function onGetSearchResults(
  callback: (event: {
    query: string;
    extras?: Record<string, any>;
    page: number;
    pageSize: number;
  }) => Promise<{
    results: Track[];
    total?: number;
  }>,
): () => void {
  return TrackPlayer.onGetSearchResultRequest(
    async ({ requestId, ...data }: AndroidAutoGetSearchResultsEvent) => {
      const { results, total } = await callback(data);
      TrackPlayer.resolveSearchResultRequest(
        requestId,
        results,
        total ?? results.length,
      );
    },
  );
}

export interface MediaProvider {
  get?: (event: { id: string }) => Promise<Track | undefined>;
  list?: (event: {
    id: string;
    page: number;
    pageSize: number;
  }) => Promise<{ children: Track[]; total: number }>;
  search?: (event: {
    query: string;
    extras?: Record<string, any>;
    page: number;
    pageSize: number;
  }) => Promise<{ results: Track[]; total: number }>;
}

export function registerMediaBrowser({
  get: getItem,
  list: getChildren,
  search: searchItems,
}: MediaProvider): () => void {
  const removes = [
    getItem ? onGetItem(getItem) : undefined,
    getChildren ? onGetChildren(getChildren) : undefined,
    searchItems ? onGetSearchResults(searchItems) : undefined,
  ];

  // Signal to native side that JS is ready to receive media browser events
  // This ensures any buffered events are flushed after listeners are set up
  TrackPlayer.setMediaBrowserReady();

  return () => removes.forEach((remove) => remove?.());
}
