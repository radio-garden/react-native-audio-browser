import {
  createServer,
  request as httpRequest,
  type IncomingHttpHeaders
} from 'node:http'
import {
  connect,
  createServer as createTcpServer,
  type AddressInfo,
  type Socket
} from 'node:net'
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import {
  AUDIO,
  answersHere,
  AUDIO_BYTES_PER_SECOND,
  ICY_METAINT,
  startStreamServer,
  type StreamServer
} from './index'

type Reply = {
  status: number
  headers: IncomingHttpHeaders
  body: Buffer
  /** `end` when the body arrived whole, `aborted` when the server closed short of it, `held` when the client gave up waiting. */
  ending: 'end' | 'aborted' | 'held'
  firstByteMs: number
}

let stream: StreamServer

const LIVE_SPEED = 10

beforeAll(async () => {
  stream = await startStreamServer({
    port: 0,
    host: '127.0.0.1',
    liveSpeed: LIVE_SPEED
  })
})
afterAll(() => stream.close())
beforeEach(async () => {
  await get('/log', { method: 'DELETE' })
})

/** Requests `path`, and keeps reading until the response ends or `waitMs` passes. */
function get(
  path: string,
  { headers = {}, waitMs = 2000, method = 'GET' } = {}
): Promise<Reply> {
  const started = Date.now()
  return new Promise((resolve, reject) => {
    const req = httpRequest(
      { host: '127.0.0.1', port: stream.port, path, headers, method },
      (res) => {
        const firstByteMs = Date.now() - started
        const chunks: Buffer[] = []
        const finish = (ending: Reply['ending']) => {
          clearTimeout(timer)
          resolve({
            status: res.statusCode ?? 0,
            headers: res.headers,
            body: Buffer.concat(chunks),
            ending,
            firstByteMs
          })
        }
        const timer = setTimeout(() => {
          finish('held')
          req.destroy()
        }, waitMs)
        res.on('data', (chunk: Buffer) => chunks.push(chunk))
        res.on('end', () => finish(res.complete ? 'end' : 'aborted'))
        res.on('aborted', () => finish('aborted'))
        res.on('error', () => finish('aborted'))
      }
    )
    req.on('error', reject)
    req.end()
  })
}

const latestEntry = () => stream.log.at(-1)!

describe('the file', () => {
  it('serves the whole file with its length', async () => {
    const reply = await get('/file.mp3')
    expect(reply.status).toBe(200)
    expect(reply.headers['content-length']).toBe(String(AUDIO.length))
    expect(reply.headers['accept-ranges']).toBe('bytes')
    expect(reply.ending).toBe('end')
    expect(reply.body.equals(AUDIO)).toBe(true)
  })

  it('serves a range', async () => {
    const reply = await get('/file.mp3', {
      headers: { Range: 'bytes=100-199' }
    })
    expect(reply.status).toBe(206)
    expect(reply.headers['content-range']).toBe(`bytes 100-199/${AUDIO.length}`)
    expect(reply.body.equals(AUDIO.subarray(100, 200))).toBe(true)
  })

  it('serves an open range and a suffix range', async () => {
    const open = await get('/file.mp3', { headers: { Range: 'bytes=40000-' } })
    expect(open.body.equals(AUDIO.subarray(40000))).toBe(true)
    const suffix = await get('/file.mp3', { headers: { Range: 'bytes=-10' } })
    expect(suffix.body.equals(AUDIO.subarray(AUDIO.length - 10))).toBe(true)
  })

  it('ignores an inverted range', async () => {
    const reply = await get('/file.mp3', { headers: { Range: 'bytes=5-3' } })
    expect(reply.status).toBe(200)
    expect(reply.body.equals(AUDIO)).toBe(true)
  })

  it('refuses a range past the end', async () => {
    const reply = await get('/file.mp3', {
      headers: { Range: `bytes=${AUDIO.length}-` }
    })
    expect(reply.status).toBe(416)
    expect(reply.headers['content-range']).toBe(`bytes */${AUDIO.length}`)
  })

  it('answers HEAD with the length and no body', async () => {
    const reply = await get('/file.mp3', { method: 'HEAD' })
    expect(reply.headers['content-length']).toBe(String(AUDIO.length))
    expect(reply.body.length).toBe(0)
  })
})

describe('the live stream', () => {
  it('sends the tone at its pace, with no length, after a burst', async () => {
    const reply = await get('/live.mp3', { waitMs: 350 })
    expect(reply.status).toBe(200)
    expect(reply.headers['content-length']).toBeUndefined()
    expect(reply.headers['transfer-encoding']).toBeUndefined()
    expect(reply.ending).toBe('held')
    // A 2 s burst, then a tick of 1 s each 100 ms at ten times real time.
    expect(reply.body.length).toBeGreaterThanOrEqual(4 * AUDIO_BYTES_PER_SECOND)
    expect(reply.body.length).toBeLessThanOrEqual(6 * AUDIO_BYTES_PER_SECOND)
    expect(reply.body.equals(AUDIO.subarray(0, reply.body.length))).toBe(true)
  })

  it('keeps pace with the clock when its timers fire late', async () => {
    const waitMs = 2000
    // Each block holds the event loop, as a busy machine would; a fixed amount
    // per tick would fall a few ticks behind on each.
    for (const at of [200, 600, 1000, 1400]) {
      setTimeout(() => {
        const until = performance.now() + 300
        while (performance.now() < until) {
          // Block.
        }
      }, at)
    }
    const reply = await get('/live.mp3', { waitMs })
    const due = AUDIO_BYTES_PER_SECOND * (2 + (waitMs / 1000) * LIVE_SPEED)
    // The server sends in ticks of 100 ms, and its clock starts after ours.
    expect(reply.body.length).toBeGreaterThan(
      due - AUDIO_BYTES_PER_SECOND * LIVE_SPEED * 0.15
    )
    expect(reply.body.length).toBeLessThanOrEqual(due * 1.01)
  })

  it('loops the tone', async () => {
    const reply = await get(`/live.mp3?closeBytes=${AUDIO.length + 100}`, {
      waitMs: 3000
    })
    expect(
      reply.body.subarray(AUDIO.length).equals(AUDIO.subarray(0, 100))
    ).toBe(true)
  })

  it('ignores a Range', async () => {
    const reply = await get('/live.mp3?closeBytes=100', {
      headers: { Range: 'bytes=1000-' }
    })
    expect(reply.status).toBe(200)
    expect(reply.body.equals(AUDIO.subarray(0, 100))).toBe(true)
  })
})

describe('a clean close', () => {
  it('ends a live stream after S seconds of audio', async () => {
    const reply = await get('/live.mp3?close=1.5')
    expect(reply.ending).toBe('end')
    expect(reply.body.length).toBe(1.5 * AUDIO_BYTES_PER_SECOND)
    expect(latestEntry().outcome).toBe('closed')
  })

  it('ends a live stream after N bytes', async () => {
    const reply = await get('/live.mp3?closeBytes=1234')
    expect(reply.ending).toBe('end')
    expect(reply.body.length).toBe(1234)
  })

  it('closes again on every reconnect, and counts the attempts', async () => {
    await get('/live.mp3?close=0.5')
    await get('/live.mp3?close=0.5')
    expect(stream.log.map((entry) => entry.attempt)).toEqual([1, 2])
    expect(stream.log.every((entry) => entry.outcome === 'closed')).toBe(true)
  })

  it('answers before a close at 0', async () => {
    const reply = await get('/live.mp3?close=0')
    expect(reply.status).toBe(200)
    expect(reply.ending).toBe('end')
    expect(reply.body.length).toBe(0)
  })

  it('refuses both close and closeBytes', async () => {
    expect((await get('/live.mp3?close=1&closeBytes=10')).status).toBe(400)
  })
})

describe('a short end', () => {
  it('promises the whole file and closes at S seconds', async () => {
    const reply = await get('/file.mp3?close=4')
    expect(reply.headers['content-length']).toBe(String(AUDIO.length))
    expect(reply.ending).toBe('aborted')
    expect(reply.body.length).toBe(4 * AUDIO_BYTES_PER_SECOND)
  })

  it('answers a probe before the end in full', async () => {
    const reply = await get('/file.mp3?close=4', {
      headers: { Range: 'bytes=0-1' }
    })
    expect(reply.ending).toBe('end')
    expect(reply.body.equals(AUDIO.subarray(0, 2))).toBe(true)
  })

  it('closes a range that starts past the end at once', async () => {
    const reply = await get('/file.mp3?close=4', {
      headers: { Range: 'bytes=30000-' }
    })
    expect(reply.status).toBe(206)
    expect(reply.ending).toBe('aborted')
    expect(reply.body.length).toBe(0)
  })
})

describe('a silent stall', () => {
  it('stops a live stream and holds the connection open', async () => {
    const reply = await get('/live.mp3?stall=1', { waitMs: 800 })
    expect(reply.ending).toBe('held')
    expect(reply.body.length).toBe(AUDIO_BYTES_PER_SECOND)
  })

  it('stops the file and holds the connection open', async () => {
    const reply = await get('/file.mp3?stallBytes=500', { waitMs: 500 })
    expect(reply.ending).toBe('held')
    expect(reply.body.length).toBe(500)
  })

  it('answers before a stall at 0', async () => {
    const reply = await get('/live.mp3?stall=0', { waitMs: 300 })
    expect(reply.status).toBe(200)
    expect(reply.ending).toBe('held')
    expect(reply.body.length).toBe(0)
  })

  it('logs the stall while the client waits', async () => {
    const pending = get('/live.mp3?stall=0.5', { waitMs: 500 })
    await new Promise((resolve) => setTimeout(resolve, 200))
    expect(latestEntry().outcome).toBe('stalled')
    await pending
  })
})

describe('a status', () => {
  it('answers the code on every request', async () => {
    expect((await get('/file.mp3?status=404')).status).toBe(404)
    expect((await get('/file.mp3?status=404')).status).toBe(404)
  })

  it('answers the code only on the Nth request to the URL', async () => {
    const statuses = []
    for (let i = 0; i < 3; i++) {
      statuses.push((await get('/live.mp3?status=503&nth=2&close=0.1')).status)
    }
    expect(statuses).toEqual([200, 503, 200])
  })

  it('refuses a code that is not a final one', async () => {
    expect((await get('/file.mp3?status=42')).status).toBe(400)
    expect((await get('/file.mp3?status=101')).status).toBe(400)
    expect((await get('/file.mp3?status=200')).status).toBe(400)
  })

  it('refuses an nth that cannot match', async () => {
    expect((await get('/file.mp3?status=503&nth=0')).status).toBe(400)
    expect((await get('/file.mp3?status=503&nth=1.5')).status).toBe(400)
    expect((await get('/file.mp3?nth=2')).status).toBe(400)
  })
})

/** The ICY titles in a body read with `Icy-MetaData: 1`. */
function icyTitles(body: Buffer, charset: BufferEncoding): string[] {
  const titles = []
  let at = ICY_METAINT
  while (at < body.length) {
    const length = body[at]! * 16
    const text = body.subarray(at + 1, at + 1 + length).toString(charset)
    titles.push(text.match(/StreamTitle='(.*)';/)![1]!)
    at += 1 + length + ICY_METAINT
  }
  return titles
}

/** A body read with `Icy-MetaData: 1`, without its ICY blocks. */
function icyAudio(body: Buffer): Buffer {
  const parts = []
  let at = 0
  while (at < body.length) {
    parts.push(body.subarray(at, at + ICY_METAINT))
    at += ICY_METAINT
    if (at < body.length) at += 1 + body[at]! * 16
  }
  return Buffer.concat(parts)
}

describe('ICY titles', () => {
  it('interleaves titles for a client that asks', async () => {
    const reply = await get(`/live.mp3?icy=2&closeBytes=${ICY_METAINT * 5}`, {
      headers: { 'Icy-MetaData': '1' },
      waitMs: 3000
    })
    expect(reply.headers['icy-metaint']).toBe(String(ICY_METAINT))
    // A title after each second of audio, changing every 2 s, and none after
    // the last byte.
    expect(icyTitles(reply.body, 'utf8')).toEqual([
      'Tonalité 1',
      'Tonalité 2',
      'Tonalité 2',
      'Tonalité 3'
    ])
    expect(
      icyAudio(reply.body).equals(AUDIO.subarray(0, ICY_METAINT * 5))
    ).toBe(true)
  })

  it('encodes the titles in the chosen charset', async () => {
    const reply = await get(
      `/live.mp3?icy=100&charset=latin1&closeBytes=${ICY_METAINT + 1}`,
      {
        headers: { 'Icy-MetaData': '1' },
        waitMs: 3000
      }
    )
    expect(icyTitles(reply.body, 'latin1')).toEqual(['Tonalité 1'])
  })

  it('refuses titles that never change', async () => {
    expect((await get('/live.mp3?icy=0')).status).toBe(400)
  })

  it('sends plain audio to a client that does not ask', async () => {
    const reply = await get(`/live.mp3?icy=1&closeBytes=${ICY_METAINT + 100}`, {
      waitMs: 3000
    })
    expect(reply.headers['icy-metaint']).toBeUndefined()
    expect(reply.body.equals(AUDIO.subarray(0, ICY_METAINT + 100))).toBe(true)
  })
})

describe('a slow start', () => {
  it('waits before the first byte', async () => {
    const reply = await get('/file.mp3?delay=0.5')
    expect(reply.firstByteMs).toBeGreaterThanOrEqual(450)
    expect(reply.body.equals(AUDIO)).toBe(true)
  })

  it('answers nothing to a client gone during the wait', async () => {
    const req = httpRequest({
      host: '127.0.0.1',
      port: stream.port,
      path: '/file.mp3?delay=0.3'
    })
    req.on('error', () => {})
    req.end()
    await new Promise((resolve) => setTimeout(resolve, 100))
    req.destroy()
    await new Promise((resolve) => setTimeout(resolve, 400))
    expect(latestEntry()).toMatchObject({ outcome: 'client-gone', sent: 0 })
    expect(latestEntry().status).toBeUndefined()
  })

  it('refuses a wait too long for a timer', async () => {
    expect((await get('/file.mp3?delay=3000000')).status).toBe(400)
  })
})

describe('a missing Range', () => {
  it('closes early for a request with no Range', async () => {
    const reply = await get('/live.mp3?requireRange', { waitMs: 3000 })
    expect(reply.ending).toBe('end')
    expect(reply.body.length).toBe(32 * 1024)
  })

  it('streams on for a request with one', async () => {
    const reply = await get('/live.mp3?requireRange=100', {
      headers: { Range: 'bytes=0-' },
      waitMs: 300
    })
    expect(reply.ending).toBe('held')
    expect(reply.body.length).toBeGreaterThan(100)
  })
})

describe('an odd request', () => {
  it('answers a target that is no URL with a 400, and stays up', async () => {
    const answer = await new Promise<string>((resolve, reject) => {
      const socket = connect(stream.port, '127.0.0.1', () => {
        socket.end('GET http://[ HTTP/1.1\r\nHost: x\r\n\r\n')
      })
      let text = ''
      socket.on('data', (chunk) => (text += chunk))
      socket.on('close', () => resolve(text))
      socket.on('error', reject)
    })
    expect(answer).toMatch(/^HTTP\/1\.1 400/)
    expect((await get('/file.mp3')).status).toBe(200)
  })
})

describe('answersHere', () => {
  it('knows this server', async () => {
    expect(await answersHere('127.0.0.1', stream.port)).toBe(true)
  })

  it('knows another server is not this one', async () => {
    const other = createServer((_, res) => res.end('other'))
    await new Promise<void>((resolve) => other.listen(0, '127.0.0.1', resolve))
    const { port } = other.address() as AddressInfo
    expect(await answersHere('127.0.0.1', port)).toBe(false)
    await new Promise((resolve) => other.close(resolve))
  })

  it('gives up on a server that never answers', async () => {
    const sockets: Socket[] = []
    const silent = createTcpServer((socket) => sockets.push(socket))
    await new Promise<void>((resolve) => silent.listen(0, '127.0.0.1', resolve))
    const { port } = silent.address() as AddressInfo
    const started = performance.now()
    expect(await answersHere('127.0.0.1', port, 200)).toBe(false)
    expect(performance.now() - started).toBeLessThan(1000)
    for (const socket of sockets) socket.destroy()
    await new Promise((resolve) => silent.close(resolve))
  })
})

describe('the log', () => {
  it('records each request over HTTP, and clears', async () => {
    await get('/file.mp3', { headers: { Range: 'bytes=0-9' } })
    const log = JSON.parse((await get('/log')).body.toString())
    expect(log).toMatchObject([
      {
        attempt: 1,
        method: 'GET',
        url: '/file.mp3',
        range: 'bytes=0-9',
        status: 206,
        sent: 10,
        outcome: 'complete'
      }
    ])
    expect(log[0].headers.range).toBe('bytes=0-9')

    expect((await get('/log', { method: 'DELETE' })).status).toBe(204)
    expect(JSON.parse((await get('/log')).body.toString())).toEqual([])
    await get('/file.mp3')
    expect(latestEntry().attempt).toBe(1)
    expect(latestEntry().id).toBeGreaterThan(log[0].id)
  })

  it('refuses a fractional byte count', async () => {
    expect((await get('/live.mp3?closeBytes=10.5')).status).toBe(400)
  })

  it('counts no attempt for a refused request', async () => {
    await get('/file.mp3?status=503&nth=2&close=x')
    expect(latestEntry().attempt).toBe(0)
    await get('/file.mp3?status=503&nth=1')
    expect(latestEntry()).toMatchObject({ attempt: 1, status: 503 })
  })
})
