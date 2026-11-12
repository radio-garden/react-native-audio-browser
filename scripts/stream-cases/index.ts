import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { networkInterfaces } from 'node:os'
import { join } from 'node:path'
import { parseArgs } from 'node:util'
import { startStreamServer } from '../stream-server/index.ts'
import { judge, type Heard, type Platform, type StreamCase } from './judge.ts'

const APP_ID = 'com.audiobrowserexample'
const TARGET_WAIT_MS = 60_000
const SETTLE_MS = 5_000
const INSPECTOR_TIMEOUT_MS = 10_000

const usage = `Plays each case of spec/stream-cases.json in the example app and checks what it promises.

  node scripts/stream-cases/index.ts <android|ios> [--only <text>] [--device <id>] [--lan <ip>] [--out <dir>]

Needs Metro (corepack yarn example start) and a Debug build of the example app
on the device: an iOS simulator, or an Android device or emulator. --device
names it (a simulator's UDID, an adb serial) when several are running. The
script starts its own stream server. --only runs the cases whose name contains
the text. A case with network steps runs on Android only, against the stream
server over the LAN (--lan, else this Mac's first address) while the device's
network goes off and on; the device must be on the same network.`

const { values, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    only: { type: 'string' },
    device: { type: 'string' },
    lan: { type: 'string' },
    out: { type: 'string' },
    help: { type: 'boolean', short: 'h' }
  }
})
const platform = positionals[0] as Platform | undefined
if (values.help || (platform !== 'android' && platform !== 'ios')) {
  console.log(usage)
  process.exit(values.help ? 0 : 2)
}

const metroPort = Number(process.env.RCT_METRO_PORT ?? 8081)
const spec = JSON.parse(
  readFileSync(new URL('../../spec/stream-cases.json', import.meta.url), 'utf8')
) as { cases: StreamCase[] }
const cases = spec.cases.filter(
  (c) =>
    (c.platforms ?? [platform]).includes(platform) &&
    (values.only === undefined || c.name.includes(values.only))
)
if (cases.length === 0) {
  console.error('No case matches.')
  process.exit(2)
}

const adb = (...args: string[]) =>
  execFileSync(
    'adb',
    [...(values.device ? ['-s', values.device] : []), ...args],
    {
      encoding: 'utf8',
      stdio: 'pipe'
    }
  )

async function freePort(): Promise<number> {
  const server = createServer()
  await new Promise<void>((done) => server.listen(0, '127.0.0.1', done))
  const { port } = server.address() as { port: number }
  await new Promise((done) => server.close(done))
  return port
}

function lanAddress(): string {
  const address = Object.values(networkInterfaces())
    .flat()
    .find((a) => a && a.family === 'IPv4' && !a.internal)?.address
  if (!address) throw new Error('No LAN address: pass --lan')
  return address
}

type Target = { webSocketDebuggerUrl: string; title: string }

async function appTarget(): Promise<Target> {
  const until = Date.now() + TARGET_WAIT_MS
  while (Date.now() < until) {
    const targets = (await fetch(`http://localhost:${metroPort}/json/list`)
      .then((r) => r.json())
      .catch(() => [])) as Target[]
    if (targets.length === 1) return targets[0]!
    if (targets.length > 1) {
      throw new Error(
        `Several apps are connected to Metro (${targets.map((t) => t.title).join(', ')}): leave one`
      )
    }
    await new Promise((done) => setTimeout(done, 1000))
  }
  throw new Error(`The example app did not connect to Metro on ${metroPort}`)
}

/**
 * One inspector session: evaluates expressions, and hands each `TRACE <run>` console line to
 * `onTrace`. The inspector replays earlier console lines when a session starts, so a line of
 * another run is dropped.
 */
async function inspector(
  target: Target,
  run: string,
  onTrace: (text: string) => void
) {
  // Metro's inspector refuses a connection with no Origin. Node's WebSocket
  // (undici) takes headers here, which the DOM's type does not know.
  const socket = new WebSocket(target.webSocketDebuggerUrl, {
    headers: { Origin: `http://localhost:${metroPort}` }
  } as unknown as string[])
  let nextId = 1
  const pending = new Map<number, (result: unknown) => void>()
  socket.onmessage = ({ data }) => {
    const message = JSON.parse(String(data))
    if (message.id && pending.has(message.id)) {
      pending.get(message.id)!(message.result)
      pending.delete(message.id)
    } else if (message.method === 'Runtime.consoleAPICalled') {
      const text = message.params.args
        .map((a: { value?: unknown }) => a.value ?? '')
        .join(' ')
      const prefix = `TRACE ${run} `
      if (text.startsWith(prefix)) onTrace(text.slice(prefix.length))
    }
  }
  await new Promise((done, fail) => {
    socket.onopen = done
    socket.onerror = fail
  })
  const send = (method: string, params = {}) =>
    new Promise<unknown>((done, fail) => {
      const id = nextId++
      const timer = setTimeout(() => {
        pending.delete(id)
        fail(
          new Error(
            `The app did not answer ${method} within ${INSPECTOR_TIMEOUT_MS} ms`
          )
        )
      }, INSPECTOR_TIMEOUT_MS)
      pending.set(id, (result) => {
        clearTimeout(timer)
        done(result)
      })
      socket.send(JSON.stringify({ id, method, params }))
    })
  await send('Runtime.enable')
  return {
    evaluate: async (expression: string) =>
      (
        (await send('Runtime.evaluate', {
          expression,
          returnByValue: true
        })) as {
          result?: { value?: unknown }
        }
      )?.result?.value,
    close: () => socket.close()
  }
}

const play = (src: string, live: boolean, run: string) => `(() => {
  const ab = globalThis.AudioBrowser
  if (!ab) return 'no globalThis.AudioBrowser: a Debug build of the example app?'
  for (const stop of globalThis.__streamCase ?? []) stop()
  ab.stop()
  const t0 = Date.now()
  const trace = (what) => console.log('TRACE ${run} ' + JSON.stringify({ at: (Date.now() - t0) / 1000, ...what }))
  globalThis.__streamCase = [
    ab.onPlaybackChanged.addListener((p) => trace({ state: p.state })),
    ab.onOnlineChanged.addListener((online) => trace({ online }))
  ]
  ab.setQueue([{ src: ${JSON.stringify(src)}, title: ${JSON.stringify(src)}, live: ${live} }])
  ab.play()
  return 'ok'
})()`

const finish = `(() => {
  for (const stop of globalThis.__streamCase ?? []) stop()
  globalThis.__streamCase = []
  globalThis.AudioBrowser?.stop()
})()`

const networkCases = cases.some((c) => c.network)
const lan = networkCases ? (values.lan ?? lanAddress()) : undefined
const stream = await startStreamServer({
  port: 0,
  host: networkCases ? undefined : '127.0.0.1'
})
const deadPort = await freePort()
const host = platform === 'android' ? '127.0.0.1' : 'localhost'

if (platform === 'android') {
  adb('reverse', `tcp:${metroPort}`, `tcp:${metroPort}`)
  adb('reverse', `tcp:${stream.port}`, `tcp:${stream.port}`)
  adb(
    'shell',
    'monkey',
    '-p',
    APP_ID,
    '-c',
    'android.intent.category.LAUNCHER',
    '1'
  )
} else {
  execFileSync(
    'xcrun',
    ['simctl', 'launch', values.device ?? 'booted', APP_ID],
    {
      stdio: 'pipe'
    }
  )
}

const setOnline = (online: boolean) =>
  adb(
    'shell',
    'cmd',
    'connectivity',
    'airplane-mode',
    online ? 'disable' : 'enable'
  )

const results: {
  name: string
  pass: boolean
  observed: string
  failures: string[]
}[] = []
const started = Date.now()
if (networkCases && platform === 'android') {
  for (const signal of ['SIGINT', 'SIGTERM'] as const) {
    process.once(signal, () => {
      setOnline(true)
      process.exit(1)
    })
  }
}
try {
  await appTarget()
  await new Promise((done) => setTimeout(done, SETTLE_MS))
  for (const streamCase of cases) {
    const src = streamCase.url
      ? streamCase.url.replace('{dead}', String(deadPort))
      : `http://${streamCase.network ? lan : host}:${stream.port}${streamCase.path}`
    const live = new URL(src).pathname === '/live.mp3'
    const lines = new Set<string>()
    const run = randomUUID()
    const session = await inspector(await appTarget(), run, (text) =>
      lines.add(text)
    )
    stream.log.length = 0
    console.log(`▶ ${streamCase.name} (${streamCase.seconds} s): ${src}`)
    const answer = await session.evaluate(play(src, live, run))
    if (answer !== 'ok') throw new Error(String(answer))
    const timers = (streamCase.network ?? []).map((step) =>
      setTimeout(() => setOnline(step.online), step.at * 1000)
    )
    await new Promise((done) => setTimeout(done, streamCase.seconds * 1000))
    timers.forEach(clearTimeout)
    if (streamCase.network) setOnline(true)
    session.close()
    // A network step drops the app's link to Metro, and the first session with
    // it; a new one is replayed every line of the run.
    const replay = await inspector(await appTarget(), run, (text) =>
      lines.add(text)
    )
    await replay.evaluate(finish)
    replay.close()
    const heard = [...lines]
      .map((line) => JSON.parse(line) as Heard)
      .sort((a, b) => a.at - b.at)
    const verdict = judge(streamCase, platform, heard)
    const connections = stream.log.filter(
      (entry) => entry.method === 'GET'
    ).length
    results.push({
      name: streamCase.name,
      ...verdict,
      observed: `${verdict.observed}; ${connections} connections`
    })
    console.log(
      `  ${verdict.pass ? '✓' : '✗'} ${verdict.observed}; ${connections} connections`
    )
    for (const failure of verdict.failures) console.log(`    ${failure}`)
    if (values.out) {
      mkdirSync(values.out, { recursive: true })
      const file = streamCase.name.replace(/[^a-z0-9]+/gi, '-').slice(0, 60)
      writeFileSync(
        join(values.out, `${platform}-${file}.json`),
        JSON.stringify(
          { streamCase, src, heard, server: stream.log, verdict },
          null,
          2
        )
      )
    }
  }
} finally {
  if (networkCases && platform === 'android') setOnline(true)
  await stream.close()
}

console.log(`\n| Case | ${platform} | |\n| --- | --- | --- |`)
for (const r of results) {
  console.log(
    `| ${r.name} | ${r.observed} | ${r.pass ? 'pass' : `fail: ${r.failures.join('; ')}`} |`
  )
}
console.log(
  `\n${results.filter((r) => r.pass).length} of ${results.length} passed in ${Math.round((Date.now() - started) / 1000)} s`
)
process.exit(results.every((r) => r.pass) ? 0 : 1)
