import { useEffect, useRef, useState } from 'react'
import AudioBrowser, {
  navigate,
  seekTo,
  setPlayWhenReady,
  setQueue,
  skipToNext,
  skipToPrevious,
  useActiveTrack,
  useContent,
  usePath,
  usePlayingState,
  usePolledProgress,
  useTabs,
  type Section,
  type Track
} from 'react-native-audio-browser'
import { archiveRoutes, fetchItem, searchArchive } from './archive'

const QUEUE_ITEM_KEY = 'demo.queue.item'
const QUEUE_TRACK_KEY = 'demo.queue.track'
// An archive item id lives in a track's download URL: …/download/<id>/<file>.
const itemIdFromSrc = (src?: string): string | null =>
  src?.match(/\/download\/([^/]+)\//)?.[1] ?? null

// Configure the real library, once. The whole browse tree and playback below is
// driven by react-native-audio-browser running on the web — no server.
let started: Promise<void> | undefined
function setup() {
  if (!started) {
    started = (async () => {
      await AudioBrowser.setupPlayer({})
      // Selecting a track should start it immediately.
      setPlayWhenReady(true)
      AudioBrowser.configureBrowser({
        tabs: [
          { title: 'Home', path: '/archive/home' },
          { title: 'LibriVox', path: '/archive/collection/librivoxaudio' },
          { title: 'Folksoundomy', path: '/archive/folksoundomy' }
        ],
        routes: { ...archiveRoutes },
        async search({ query }) {
          return searchArchive(query)
        }
      })
      navigate('/archive/home')
      // Open with a queue loaded — restore the last-played item if we stored one,
      // else default to a nice album. The last-played (or first) track is active.
      let savedItem: string | null = null
      let savedTrack: string | null = null
      try {
        savedItem = localStorage.getItem(QUEUE_ITEM_KEY)
        savedTrack = localStorage.getItem(QUEUE_TRACK_KEY)
      } catch {
        // localStorage unavailable — fall through to the default
      }
      try {
        const item = await fetchItem(
          savedItem ?? 'super-mario-galaxy-original-soundtrack'
        )
        const tracks = item.children ?? []
        if (tracks.length) {
          const idx = savedTrack
            ? tracks.findIndex((t) => t.src === savedTrack)
            : 0
          setQueue(tracks, idx < 0 ? 0 : idx)
        }
      } catch {
        // ignore — the browser still works without a preloaded queue
      }
    })()
  }
  return started
}

type Panel = {
  path: string
  title: string
  sections: Section[]
  loading: boolean
}
type Layer = { key: number; panel: Panel; cls: string }

const PlayIcon = ({ className = 'glyph' }: { className?: string }) => (
  <svg
    className={className}
    viewBox="0 0 24 24"
    fill="currentColor"
    aria-hidden="true"
  >
    <path d="M8 5v14l11-7z" />
  </svg>
)
const PauseIcon = () => (
  <svg
    className="glyph"
    viewBox="0 0 24 24"
    fill="currentColor"
    aria-hidden="true"
  >
    <rect x="7" y="5" width="3.5" height="14" rx="1.2" />
    <rect x="13.5" y="5" width="3.5" height="14" rx="1.2" />
  </svg>
)
const SkipIcon = ({ dir }: { dir: 'prev' | 'next' }) => (
  <svg
    className="ctrl-svg"
    viewBox="0 0 24 24"
    fill="currentColor"
    aria-hidden="true"
  >
    {dir === 'next' ? (
      <path d="M7 6v12l8.5-6zM16 6h2v12h-2z" />
    ) : (
      <path d="M17 6v12l-8.5-6zM6 6h2v12H6z" />
    )}
  </svg>
)

export default function App() {
  const path = usePath()
  const content = useContent()
  const tabs = useTabs()
  const active = useActiveTrack()
  const playing = usePlayingState()
  const progress = usePolledProgress(250)
  const [hoverFrac, setHoverFrac] = useState<number | null>(null)

  const [history, setHistory] = useState<string[]>([])
  const dir = useRef<'fwd' | 'back'>('fwd')

  // Optimistic now-playing: reflect the tapped track immediately, before the
  // library's real active-track state catches up.
  const [optimistic, setOptimistic] = useState<Track | null>(null)
  const catchingUp = optimistic != null && optimistic.src !== active?.src
  const nowPlaying = catchingUp ? optimistic : active

  // A small two-panel stack so we can cross-slide between routes.
  const [stack, setStack] = useState<Layer[]>([])
  const keyRef = useRef(0)

  useEffect(() => {
    void setup()
  }, [])

  // Persist the currently-playing item + track so the queue can be restored.
  useEffect(() => {
    const id = itemIdFromSrc(active?.src)
    if (id && active?.src) {
      try {
        localStorage.setItem(QUEUE_ITEM_KEY, id)
        localStorage.setItem(QUEUE_TRACK_KEY, active.src)
      } catch {
        // ignore storage failures
      }
    }
  }, [active?.src])

  // Drive the slide off path/content changes.
  useEffect(() => {
    const panel: Panel = {
      path: path ?? '',
      title: content?.title ?? '',
      sections: content?.sections ?? [],
      loading: !content
    }
    setStack((prev) => {
      if (prev.length === 0) return [{ key: keyRef.current++, panel, cls: '' }]
      const top = prev[prev.length - 1]
      // Same route: content resolved/updated — swap in place, no animation.
      if (top.panel.path === panel.path) {
        return [...prev.slice(0, -1), { ...top, panel }]
      }
      // New route: keep the old panel (frozen) and slide the new one in.
      const enter = dir.current === 'fwd' ? 'enter-right' : 'enter-left'
      const leave = dir.current === 'fwd' ? 'leave-left' : 'leave-right'
      return [
        { ...top, cls: leave },
        { key: keyRef.current++, panel, cls: enter }
      ]
    })
  }, [path, content])

  // Once the slide finishes, drop the outgoing panel.
  const settle = () =>
    setStack((prev) => (prev.length > 1 ? [prev[prev.length - 1]] : prev))

  const open = (item: Track) => {
    if (item.path && !item.src && path) setHistory((h) => [...h, path])
    dir.current = 'fwd'
    if (item.src) setOptimistic(item)
    navigate(item)
  }

  // Drill into a path-only target (section headers).
  const openPath = (to: string) => {
    if (path) setHistory((h) => [...h, path])
    dir.current = 'fwd'
    navigate(to)
  }

  const back = () => {
    setHistory((h) => {
      const prev = h[h.length - 1]
      if (prev) {
        dir.current = 'back'
        navigate(prev)
      }
      return h.slice(0, -1)
    })
  }

  return (
    <div className="app">
      <header className="bar">
        <button className="icon" onClick={back} disabled={!history.length}>
          ‹
        </button>
        <span className="title">{content?.title ?? ''}</span>
      </header>

      <div className="pager">
        {stack.map((layer) => (
          <ul
            key={layer.key}
            className={`list ${layer.cls}`}
            onAnimationEnd={settle}
          >
            {layer.panel.sections.map((section, si) => {
              if (section.style?.display === 'grid') {
                return (
                  <li key={`${section.title}-${si}`} className="section">
                    <div
                      className="section-head"
                      onClick={() => section.path && openPath(section.path)}
                    >
                      <span className="section-title">{section.title}</span>
                      {section.path && <span className="ic">›</span>}
                    </div>
                    <div className="img-row">
                      {section.children.map((tile, j) => (
                        <button
                          key={`${tile.title}-${j}`}
                          className="tile"
                          onClick={() => open(tile)}
                        >
                          {typeof tile.artwork === 'string' && (
                            <img
                              className="tile-art"
                              src={tile.artwork}
                              alt=""
                              loading="lazy"
                            />
                          )}
                          <span className="tile-title">{tile.title}</span>
                        </button>
                      ))}
                    </div>
                  </li>
                )
              }
              return section.children.map((item, i) => {
                const isActive =
                  item.src != null && nowPlaying?.src === item.src
                return (
                  <li
                    key={`${item.title}-${si}-${i}`}
                    className={`row${isActive ? ' active' : ''}`}
                    onClick={() => open(item)}
                  >
                    {typeof item.artwork === 'string' ? (
                      <img
                        className="art"
                        src={item.artwork}
                        alt=""
                        loading="lazy"
                      />
                    ) : (
                      <span className="art ph" />
                    )}
                    <span className="rt">
                      <span className="rt-title">{item.title}</span>
                      {item.artist && (
                        <span className="rt-sub">{item.artist}</span>
                      )}
                    </span>
                    <span className="ic">
                      {item.src ? <PlayIcon className="ic-svg" /> : '›'}
                    </span>
                  </li>
                )
              })
            })}
            {layer.panel.loading && (
              <li className="loading">
                <span className="spinner" />
              </li>
            )}
          </ul>
        ))}
      </div>

      {nowPlaying && (
        <footer className="player">
          <div
            className="seek"
            onMouseMove={(e) => {
              const r = e.currentTarget.getBoundingClientRect()
              setHoverFrac(
                Math.min(1, Math.max(0, (e.clientX - r.left) / r.width))
              )
            }}
            onMouseLeave={() => setHoverFrac(null)}
            onClick={(e) => {
              const r = e.currentTarget.getBoundingClientRect()
              const frac = (e.clientX - r.left) / r.width
              if (progress.duration > 0) seekTo(frac * progress.duration)
            }}
          >
            <div
              className="seek-fill"
              style={{
                width:
                  hoverFrac != null
                    ? `${hoverFrac * 100}%`
                    : progress.duration
                      ? `${(progress.position / progress.duration) * 100}%`
                      : '0%'
              }}
            />
          </div>
          <div className="player-row">
            {typeof nowPlaying.artwork === 'string' && (
              <img className="part" src={nowPlaying.artwork} alt="" />
            )}
            <span className="pinfo">
              <span className="ptitle">{nowPlaying.title}</span>
              {nowPlaying.artist && (
                <span className="psub">{nowPlaying.artist}</span>
              )}
            </span>
            <div className="controls">
              <button
                className="ctrl"
                aria-label="Previous"
                onClick={() => skipToPrevious()}
              >
                <SkipIcon dir="prev" />
              </button>
              <button
                className="play"
                aria-label={playing?.playing ? 'Pause' : 'Play'}
                onClick={() => AudioBrowser.togglePlayback()}
              >
                {catchingUp || playing?.buffering ? (
                  <span className="spinner btn-spin" />
                ) : playing?.playing ? (
                  <PauseIcon />
                ) : (
                  <PlayIcon />
                )}
              </button>
              <button
                className="ctrl"
                aria-label="Next"
                onClick={() => skipToNext()}
              >
                <SkipIcon dir="next" />
              </button>
            </div>
          </div>
        </footer>
      )}

      {tabs && tabs.length > 1 && (
        <nav className="tabs">
          {tabs.map((tab, i) => (
            <button
              key={i}
              className={tab.path === (history[0] ?? path) ? 'tab on' : 'tab'}
              onClick={() => {
                setHistory([])
                dir.current = 'fwd'
                navigate(tab)
              }}
            >
              {tab.title}
            </button>
          ))}
        </nav>
      )}
    </div>
  )
}
