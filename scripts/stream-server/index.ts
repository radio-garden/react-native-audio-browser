import type { AddressInfo } from 'node:net'
import { readFileSync } from 'node:fs'
import {
  createServer,
  type IncomingMessage,
  type Server,
  type ServerResponse
} from 'node:http'
import { networkInterfaces } from 'node:os'
import { fileURLToPath } from 'node:url'

/**
 * The served audio: a 10 s MP3 tone, CBR at {@link AUDIO_BYTES_PER_SECOND}
 * (32 kbit/s), with no ID3 or Xing header so it loops byte for byte. Every
 * offset in seconds and the live pace are worked out from that rate.
 */
export const AUDIO = readFileSync(new URL('./tone.mp3', import.meta.url))
export const AUDIO_BYTES_PER_SECOND = 4000
export const ICY_METAINT = AUDIO_BYTES_PER_SECOND
export const DEFAULT_PORT = 7385

/** Sent with every response, carrying this process's id, so a client can tell this server from another on the same port. */
export const SERVER_HEADER = 'x-stream-server'
const SERVER_ID = String(process.pid)
const LIVE_BURST_SECONDS = 2
const LIVE_TICK_MS = 100
const MISSING_RANGE_CLOSE_BYTES = 32 * 1024
const MAX_DELAY_SECONDS = 3600
const MAX_RATE = 100
const HLS_SEGMENT_SECONDS = 2
const HLS_WINDOW = 4
const HLS_FIRST_AVAILABLE = 3
const HLS_DEFAULT_DROPS = 3
const HLS_TIMESTAMP_OWNER = 'com.apple.streaming.transportStreamTimestamp'

/** Where each MP3 frame of {@link AUDIO} starts, and how long one plays. */
function mp3Frames(audio: Buffer): { starts: number[]; seconds: number } {
  const MPEG1 = [
    0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320
  ]
  const MPEG2 = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160]
  const starts: number[] = []
  let seconds = 0
  for (let at = 0; at < audio.length; ) {
    if (audio[at] !== 0xff || (audio[at + 1]! & 0xe6) !== 0xe2) {
      throw new Error(`tone.mp3: no Layer III frame at byte ${at}`)
    }
    const mpeg1 = (audio[at + 1]! & 0x18) === 0x18
    const rates = mpeg1 ? [44100, 48000, 32000] : [22050, 24000, 16000]
    const sampleRate = rates[(audio[at + 2]! >> 2) & 3]!
    const kbps = (mpeg1 ? MPEG1 : MPEG2)[audio[at + 2]! >> 4]!
    const padding = (audio[at + 2]! >> 1) & 1
    starts.push(at)
    seconds = (mpeg1 ? 1152 : 576) / sampleRate
    at += Math.floor(((mpeg1 ? 144 : 72) * kbps * 1000) / sampleRate) + padding
  }
  return { starts, seconds }
}

const FRAMES = mp3Frames(AUDIO)
const FRAMES_PER_SEGMENT = Math.round(HLS_SEGMENT_SECONDS / FRAMES.seconds)
/** How long each HLS segment plays: whole frames, so it is not exactly {@link HLS_SEGMENT_SECONDS}. */
export const HLS_SEGMENT_DURATION = FRAMES_PER_SEGMENT * FRAMES.seconds

/** Live HLS segment `sequence`: the tone's frames from where the previous one ended, looping, after the ID3 timestamp packed audio carries. */
export function hlsSegment(sequence: number): Buffer {
  const parts: Buffer[] = []
  for (let i = 0; i < FRAMES_PER_SEGMENT; i++) {
    const frame = (sequence * FRAMES_PER_SEGMENT + i) % FRAMES.starts.length
    const end = FRAMES.starts[frame + 1] ?? AUDIO.length
    parts.push(AUDIO.subarray(FRAMES.starts[frame], end))
  }
  const timestamp = Buffer.alloc(8)
  timestamp.writeBigUInt64BE(
    BigInt(Math.round(sequence * HLS_SEGMENT_DURATION * 90_000)) &
      0x1_ffff_ffffn
  )
  const priv = Buffer.concat([
    Buffer.from(`${HLS_TIMESTAMP_OWNER}\0`, 'latin1'),
    timestamp
  ])
  // Every size here is under 128, where a synchsafe integer is the plain one.
  const frame = Buffer.concat([
    Buffer.from('PRIV', 'latin1'),
    Buffer.from([0, 0, 0, priv.length, 0, 0]),
    priv
  ])
  const header = Buffer.from([0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 0, frame.length])
  return Buffer.concat([header, frame, ...parts])
}

export type LogEntry = {
  id: number
  /** How many valid requests this exact URL has had, this one included; 0 for a refused one. */
  attempt: number
  time: string
  method: string
  url: string
  range: string | null
  headers: IncomingMessage['headers']
  status?: number
  /** Audio bytes handed to the socket, ICY metadata not counted; a client that stops reading leaves some of them queued. */
  sent: number
  outcome:
    | 'sending'
    | 'stalled'
    | 'complete'
    | 'closed'
    | 'status'
    | 'client-gone'
  outcomeTime?: string
}

type Behaviour = {
  /** Where the response closes: `close`, or `requireRange` for a request without a Range. */
  closeAt?: number
  stallAt?: number
  status?: number
  nth?: number
  icySeconds?: number
  charset: 'utf-8' | 'latin1'
  delaySeconds?: number
  rate: number
  /** The live HLS segment cut off mid-transfer, and on how many of its first requests. */
  dropSegment?: number
  drops: number
}

class BadRequest extends Error {}

function number(
  params: URLSearchParams,
  name: string,
  { integer = false, min = 0, max = Infinity } = {}
): number | undefined {
  const value = params.get(name)
  if (value === null) return undefined
  const parsed = Number(value)
  if (
    value === '' ||
    !Number.isFinite(parsed) ||
    parsed < min ||
    parsed > max ||
    (integer && !Number.isInteger(parsed))
  ) {
    throw new BadRequest(
      `${name} must be ${integer ? 'an integer' : 'a number'} from ${min}` +
        (max === Infinity ? '' : ` to ${max}`) +
        `, got "${value}"`
    )
  }
  return parsed
}

function offset(params: URLSearchParams, name: string): number | undefined {
  const seconds = number(params, name)
  const bytes = number(params, `${name}Bytes`, { integer: true })
  if (seconds !== undefined && bytes !== undefined) {
    throw new BadRequest(`give ${name} or ${name}Bytes, not both`)
  }
  return (
    bytes ??
    (seconds === undefined
      ? undefined
      : Math.round(seconds * AUDIO_BYTES_PER_SECOND))
  )
}

function behaviour(params: URLSearchParams, hasRange: boolean): Behaviour {
  const charset = params.get('charset') ?? 'utf-8'
  if (charset !== 'utf-8' && charset !== 'latin1') {
    throw new BadRequest(`charset must be utf-8 or latin1, got "${charset}"`)
  }
  const status = number(params, 'status', { integer: true, min: 300, max: 599 })
  const nth = number(params, 'nth', { integer: true, min: 1 })
  if (nth !== undefined && status === undefined) {
    throw new BadRequest('nth needs a status')
  }
  const close = offset(params, 'close')
  const requireRange =
    params.get('requireRange') === ''
      ? MISSING_RANGE_CLOSE_BYTES
      : number(params, 'requireRange', { integer: true })
  const closeAt = Math.min(
    close ?? Infinity,
    !hasRange && requireRange !== undefined ? requireRange : Infinity
  )
  return {
    closeAt: closeAt === Infinity ? undefined : closeAt,
    stallAt: offset(params, 'stall'),
    status,
    nth,
    icySeconds: number(params, 'icy', { min: Number.MIN_VALUE }),
    charset,
    delaySeconds: number(params, 'delay', { max: MAX_DELAY_SECONDS }),
    rate: number(params, 'rate', { min: Number.MIN_VALUE, max: MAX_RATE }) ?? 1,
    dropSegment: (() => {
      const at = number(params, 'drop')
      return at === undefined
        ? undefined
        : Math.floor(at / HLS_SEGMENT_DURATION)
    })(),
    drops:
      number(params, 'drops', { integer: true, min: 1 }) ?? HLS_DEFAULT_DROPS
  }
}

type ByteRange = { start: number; end: number }

/** The first range of a `Range` header, inclusive; undefined to serve the whole file, null when it starts past the end. */
function parseRange(
  header: string | undefined,
  size: number
): ByteRange | null | undefined {
  const match = header?.match(/^bytes=(\d*)-(\d*)/)
  if (!match) return undefined
  const [, from = '', to = ''] = match
  if (from === '' && to === '') return undefined
  if (from === '') {
    const suffix = Number(to)
    return suffix === 0
      ? null
      : { start: Math.max(0, size - suffix), end: size - 1 }
  }
  const start = Number(from)
  // An invalid range is ignored, as RFC 9110 asks, not refused.
  if (to !== '' && Number(to) < start) return undefined
  if (start >= size) return null
  return { start, end: to === '' ? size - 1 : Math.min(Number(to), size - 1) }
}

function icyBlock(title: string, charset: Behaviour['charset']): Buffer {
  const text = Buffer.from(`StreamTitle='${title}';`, charset)
  const blocks = Math.ceil(text.length / 16)
  const block = Buffer.alloc(1 + blocks * 16)
  block[0] = blocks
  text.copy(block, 1)
  return block
}

export type StreamServer = {
  server: Server
  port: number
  log: LogEntry[]
  close(): Promise<void>
}

/**
 * Starts the server on `port` (0 for any free one). `print` receives one line
 * per request and per outcome; `liveSpeed` sends live audio that many times
 * faster than real time.
 */
export function startStreamServer({
  port = DEFAULT_PORT,
  host,
  print = () => {},
  liveSpeed = 1
}: {
  port?: number
  host?: string
  print?: (line: string) => void
  liveSpeed?: number
} = {}): Promise<StreamServer> {
  const log: LogEntry[] = []
  let nextId = 1
  const attempts = new Map<string, number>()
  /** When each live HLS URL's playlist was first asked for: its stream starts then. */
  const hlsStarts = new Map<string, number>()
  const timers = new Set<NodeJS.Timeout>()

  const later = (ms: number, run: () => void) => {
    const timer = setTimeout(() => {
      timers.delete(timer)
      run()
    }, ms)
    timers.add(timer)
    return timer
  }

  const settle = (entry: LogEntry, outcome: LogEntry['outcome']) => {
    if (entry.outcome !== 'sending' && entry.outcome !== 'stalled') return
    if (entry.outcome === outcome) return
    entry.outcome = outcome
    entry.outcomeTime = new Date().toISOString()
    print(`#${entry.id} ${outcome} after ${entry.sent} bytes`)
  }

  /** Ends the response with a FIN, whatever its headers promised: `res.end()` would leave a short file's client waiting for the rest. */
  const closeCleanly = (res: ServerResponse, entry: LogEntry) => {
    settle(entry, 'closed')
    res.socket?.end()
  }

  function serveFile(
    req: IncomingMessage,
    res: ServerResponse,
    entry: LogEntry,
    b: Behaviour
  ) {
    const range = parseRange(req.headers.range, AUDIO.length)
    if (range === null) {
      entry.status = 416
      res.writeHead(416, { 'Content-Range': `bytes */${AUDIO.length}` })
      res.end()
      settle(entry, 'complete')
      return
    }
    const { start, end } = range ?? { start: 0, end: AUDIO.length - 1 }
    entry.status = range ? 206 : 200
    res.writeHead(entry.status, {
      'Content-Type': 'audio/mpeg',
      'Accept-Ranges': 'bytes',
      'Content-Length': end - start + 1,
      ...(range && { 'Content-Range': `bytes ${start}-${end}/${AUDIO.length}` })
    })
    if (req.method === 'HEAD') {
      res.end()
      settle(entry, 'complete')
      return
    }
    const closeAt = b.closeAt ?? Infinity
    const stopAt = Math.min(closeAt, b.stallAt ?? Infinity, end + 1)
    const chunk = AUDIO.subarray(start, Math.max(start, stopAt))
    res.write(chunk)
    entry.sent = chunk.length
    if (stopAt > end) {
      res.end()
      settle(entry, 'complete')
    } else if (stopAt === closeAt) {
      closeCleanly(res, entry)
    } else {
      settle(entry, 'stalled')
    }
  }

  function serveLive(
    req: IncomingMessage,
    res: ServerResponse,
    entry: LogEntry,
    b: Behaviour
  ) {
    const icySeconds =
      req.headers['icy-metadata'] === '1' ? b.icySeconds : undefined
    entry.status = 200
    // A live stream ends at the connection's close, as an Icecast server's
    // does, not at a terminating chunk.
    res.useChunkedEncodingByDefault = false
    res.writeHead(200, {
      'Content-Type': 'audio/mpeg',
      'Connection': 'close',
      'icy-name': 'Tone',
      'icy-br': '32',
      ...(icySeconds !== undefined && { 'icy-metaint': String(ICY_METAINT) })
    })
    // Now, so a close or a stall at 0 still answers first.
    res.flushHeaders()
    if (req.method === 'HEAD') {
      res.end()
      settle(entry, 'complete')
      return
    }

    const closeAt = b.closeAt ?? Infinity
    const stallAt = b.stallAt ?? Infinity
    let untilMeta = ICY_METAINT

    const send = (bytes: number) => {
      let left = Math.min(bytes, closeAt - entry.sent, stallAt - entry.sent)
      const parts: Buffer[] = []
      while (left > 0) {
        if (icySeconds !== undefined && untilMeta === 0) {
          const seconds = entry.sent / AUDIO_BYTES_PER_SECOND
          const title = `Tonalité ${Math.floor(seconds / icySeconds) + 1}`
          parts.push(icyBlock(title, b.charset))
          untilMeta = ICY_METAINT
        }
        const at = entry.sent % AUDIO.length
        const take = Math.min(
          left,
          AUDIO.length - at,
          icySeconds === undefined ? Infinity : untilMeta
        )
        parts.push(AUDIO.subarray(at, at + take))
        entry.sent += take
        left -= take
        if (icySeconds !== undefined) untilMeta -= take
      }
      if (parts.length) res.write(Buffer.concat(parts))
      if (entry.sent >= closeAt) {
        closeCleanly(res, entry)
        return false
      }
      if (entry.sent >= stallAt) {
        settle(entry, 'stalled')
        return false
      }
      return true
    }

    // Paced from the clock: timers fire late, and a fixed amount per tick
    // falls behind real time until the player runs dry.
    const started = performance.now()
    const tick = () => {
      if (entry.outcome !== 'sending') return
      const due =
        AUDIO_BYTES_PER_SECOND *
        (LIVE_BURST_SECONDS +
          ((performance.now() - started) / 1000) * liveSpeed * b.rate)
      if (send(Math.floor(due) - entry.sent)) later(LIVE_TICK_MS, tick)
    }
    tick()
  }

  function serveHlsPlaylist(
    res: ServerResponse,
    entry: LogEntry,
    search: string
  ) {
    const started = hlsStarts.get(search) ?? performance.now()
    hlsStarts.set(search, started)
    const elapsed = ((performance.now() - started) / 1000) * liveSpeed
    const newest =
      HLS_FIRST_AVAILABLE - 1 + Math.floor(elapsed / HLS_SEGMENT_DURATION)
    const first = Math.max(0, newest - HLS_WINDOW + 1)
    const lines = [
      '#EXTM3U',
      '#EXT-X-VERSION:3',
      `#EXT-X-TARGETDURATION:${Math.round(HLS_SEGMENT_DURATION)}`,
      `#EXT-X-MEDIA-SEQUENCE:${first}`
    ]
    for (let sequence = first; sequence <= newest; sequence++) {
      lines.push(
        `#EXTINF:${HLS_SEGMENT_DURATION.toFixed(5)},`,
        `live/${sequence}.mp3${search}`
      )
    }
    const body = `${lines.join('\n')}\n`
    entry.status = 200
    res.writeHead(200, { 'Content-Type': 'application/vnd.apple.mpegurl' })
    res.end(body)
    entry.sent = body.length
    settle(entry, 'complete')
  }

  function serveHlsSegment(
    req: IncomingMessage,
    res: ServerResponse,
    entry: LogEntry,
    b: Behaviour,
    sequence: number
  ) {
    const segment = hlsSegment(sequence)
    const range = parseRange(req.headers.range, segment.length)
    if (range === null) {
      entry.status = 416
      res.writeHead(416, { 'Content-Range': `bytes */${segment.length}` })
      res.end()
      settle(entry, 'complete')
      return
    }
    const { start, end } = range ?? { start: 0, end: segment.length - 1 }
    entry.status = range ? 206 : 200
    res.writeHead(entry.status, {
      'Content-Type': 'audio/mpeg',
      'Accept-Ranges': 'bytes',
      'Content-Length': end - start + 1,
      ...(range && {
        'Content-Range': `bytes ${start}-${end}/${segment.length}`
      })
    })
    if (sequence === b.dropSegment && entry.attempt <= b.drops) {
      // A dropped connection: a reset halfway through the segment, as a lost
      // network leaves it, not a clean end the client would wait out.
      const sent = segment.subarray(start, Math.max(start, segment.length >> 1))
      res.write(sent)
      entry.sent = sent.length
      settle(entry, 'closed')
      later(50, () => res.socket?.destroy())
      return
    }
    res.end(segment.subarray(start, end + 1))
    entry.sent = end - start + 1
    settle(entry, 'complete')
  }

  function serveLog(req: IncomingMessage, res: ServerResponse) {
    if (req.method === 'DELETE') {
      log.length = 0
      attempts.clear()
      hlsStarts.clear()
      res.writeHead(204).end()
      return
    }
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(log, null, 2))
  }

  const server = createServer((req, res) => {
    res.setHeader(SERVER_HEADER, SERVER_ID)
    if (!URL.canParse(req.url ?? '/', 'http://localhost')) {
      res.writeHead(400).end()
      return
    }
    const url = new URL(req.url ?? '/', 'http://localhost')
    if (url.pathname === '/log') return serveLog(req, res)
    if (url.pathname === '/') {
      res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' })
      res.end(usage(`http://${req.headers.host ?? 'localhost'}`))
      return
    }
    const hlsSequence = url.pathname.match(/^\/live\/(\d+)\.mp3$/)?.[1]
    if (
      url.pathname !== '/file.mp3' &&
      url.pathname !== '/live.mp3' &&
      url.pathname !== '/live.m3u8' &&
      hlsSequence === undefined
    ) {
      res.writeHead(404).end()
      return
    }

    const key = url.pathname + url.search
    let b: Behaviour | undefined
    let invalid: BadRequest | undefined
    try {
      b = behaviour(url.searchParams, req.headers.range !== undefined)
    } catch (error) {
      if (!(error instanceof BadRequest)) throw error
      invalid = error
    }
    const attempt = b ? (attempts.get(key) ?? 0) + 1 : 0
    if (b) attempts.set(key, attempt)
    const entry: LogEntry = {
      id: nextId++,
      attempt,
      time: new Date().toISOString(),
      method: req.method ?? 'GET',
      url: key,
      range: req.headers.range ?? null,
      headers: req.headers,
      sent: 0,
      outcome: 'sending'
    }
    log.push(entry)
    print(
      `#${entry.id} ${entry.method} ${key} attempt ${attempt}` +
        (entry.range ? ` ${entry.range}` : '')
    )
    res.on('close', () => settle(entry, 'client-gone'))

    if (!b) {
      entry.status = 400
      res.writeHead(400, { 'Content-Type': 'text/plain' }).end(invalid!.message)
      settle(entry, 'status')
      return
    }

    const respond = () => {
      if (
        b.status !== undefined &&
        (b.nth === undefined || b.nth === attempt)
      ) {
        entry.status = b.status
        res.writeHead(b.status, { 'Content-Type': 'text/plain' })
        res.end(`${b.status}\n`)
        settle(entry, 'status')
      } else if (url.pathname === '/file.mp3') {
        serveFile(req, res, entry, b)
      } else if (url.pathname === '/live.m3u8') {
        serveHlsPlaylist(res, entry, url.search)
      } else if (hlsSequence !== undefined) {
        serveHlsSegment(req, res, entry, b, Number(hlsSequence))
      } else {
        serveLive(req, res, entry, b)
      }
    }
    if (b.delaySeconds) {
      later(b.delaySeconds * 1000, () => {
        if (entry.outcome === 'sending') respond()
      })
    } else {
      respond()
    }
  })

  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(port, host, () => {
      server.off('error', reject)
      resolve({
        server,
        port: (server.address() as AddressInfo).port,
        log,
        close: () =>
          new Promise<void>((done) => {
            for (const timer of timers) clearTimeout(timer)
            timers.clear()
            server.close(() => done())
            server.closeAllConnections()
          })
      })
    })
  })
}

/** The server's help: what it serves and each behaviour, with every URL on `base`. */
export function usage(base: string): string {
  return `Audio: a ${Math.round(AUDIO.length / AUDIO_BYTES_PER_SECOND)} s tone, MP3 at ${(AUDIO_BYTES_PER_SECOND * 8) / 1000} kbit/s.

  ${base}/file.mp3      the file, with Content-Length and Range support
  ${base}/live.mp3      the tone looped without end, sent in real time, no Content-Length
  ${base}/live.m3u8     the tone as live HLS: ${HLS_SEGMENT_DURATION.toFixed(2)} s MP3 segments, ${HLS_FIRST_AVAILABLE} available at the
                        first request, one more each segment, ${HLS_WINDOW} in the playlist

Behaviours, as query parameters on either URL. S is seconds of audio (from
the start of the file, or of the live response); N is bytes.

  close=S | closeBytes=N     a clean close: the server ends the connection there
                             (on the file, a short end: Content-Length promised more)
  stall=S | stallBytes=N     a silent stall: stops sending and keeps the connection open
  status=CODE [&nth=K]       answers CODE (300-599), on every request or only on the Kth
                             to this URL
  icy=S [&charset=latin1]    live only: an ICY title once a second that changes every
                             S seconds, for a client that sends Icy-MetaData: 1 (charset
                             utf-8 by default); N and S count audio, not the titles
  delay=S                    waits S seconds (at most ${MAX_DELAY_SECONDS}) before answering
  rate=R                     live only: after the burst, sends R times as fast as the audio
                             plays (at most ${MAX_RATE}); below 1 the player runs dry
  drop=S [&drops=K]          HLS only: the segment that plays at S seconds drops its
                             connection halfway through on its first K requests (default ${HLS_DEFAULT_DROPS})
  requireRange[=N]           closes after N bytes (default ${MISSING_RANGE_CLOSE_BYTES}) when the request
                             has no Range header

  ${base}/live.mp3?icy=5&stall=20     ICY titles, then a stall
  ${base}/live.mp3?close=10           a live stream that closes cleanly every 10 s
  ${base}/file.mp3?close=4            a file that ends at 4 s of its 10
  ${base}/live.mp3?rate=0.8           a stream that arrives slower than it plays
  ${base}/live.m3u8?drop=20&drops=5   a live HLS segment that fails 5 times

  GET ${base}/log       every request: attempt, headers, Range, bytes sent, outcome
  DELETE ${base}/log    clears the log and the attempt counts
`
}

/** Whether this process is what answers on `host` at `port`; false for another server, or none within `timeoutMs`. */
export async function answersHere(
  host: string,
  port: number,
  timeoutMs = 2000
): Promise<boolean> {
  const answer = await fetch(`http://${host}:${port}/`, {
    signal: AbortSignal.timeout(timeoutMs)
  }).catch(() => undefined)
  await answer?.body?.cancel()
  return answer?.headers.get(SERVER_HEADER) === SERVER_ID
}

function lanAddresses(): string[] {
  return Object.values(networkInterfaces())
    .flat()
    .filter(
      (address) => address && address.family === 'IPv4' && !address.internal
    )
    .map((address) => address!.address)
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT ?? DEFAULT_PORT)
  const { port: bound } = await startStreamServer({
    port,
    print: (line) => console.log(`${new Date().toISOString()} ${line}`)
  })
  // macOS lets another process listen on one loopback address at the same
  // port, and a simulator or emulator reaching the host through it would
  // never see this server.
  for (const loopback of ['127.0.0.1', '[::1]']) {
    if (!(await answersHere(loopback, bound))) {
      console.error(
        `Another server answers on ${loopback}:${bound}; start this one with PORT=<another port>.`
      )
      process.exit(1)
    }
  }
  console.log(usage(`http://localhost:${bound}`))
  console.log('The same server from elsewhere:')
  console.log(`  iOS simulator     http://localhost:${bound}`)
  console.log(`  Android emulator  http://10.0.2.2:${bound}`)
  for (const address of lanAddresses()) {
    console.log(`  same network      http://${address}:${bound}`)
  }
  console.log('')
}
