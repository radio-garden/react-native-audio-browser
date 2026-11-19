/** Runs an adb command, handing it `input` on stdin, and answers its stdout. */
export type Adb = (args: string[], input?: string) => string

const KEY = 'debug_http_host'

/**
 * Stops a Debug build of a React Native Android app and points it at the Metro on `host`, through
 * the address saved in its preferences, which outranks its build's default. Before it changes
 * anything, pushes onto `undo` the step that stops the app and puts its preferences back as they were.
 */
export function pointAppAtMetro(
  adb: Adb,
  appId: string,
  host: string,
  undo: (() => void)[]
) {
  const file = `shared_prefs/${appId}_preferences.xml`
  // A running app keeps its preferences in memory, and writes them back on its next change.
  const stop = () => adb(['shell', 'am', 'force-stop', appId])
  // Android reads a leftover backup in place of the file.
  const write = (prefs: string) =>
    adb(
      [
        'shell',
        `run-as ${appId} sh -c 'mkdir -p shared_prefs && cat > ${file}.tmp && rm -f ${file}.bak && mv ${file}.tmp ${file}'`
      ],
      prefs
    )
  stop()
  const before = adb([
    'shell',
    `run-as ${appId} sh -c '[ ! -f ${file} ] || cat ${file}'`
  ])
  undo.push(() => {
    stop()
    if (before) write(before)
    else adb(['shell', `run-as ${appId} rm -f ${file}`])
  })
  write(withMetroHost(before, host))
}

/** `prefs`, an Android shared preferences file or '' for none, with its Metro address set to `host`. */
export function withMetroHost(prefs: string, host: string): string {
  const entry = `<string name="${KEY}">${host}</string>`
  const kept = prefs.replace(
    new RegExp(`\\n[ \\t]*<string name="${KEY}">[^<]*</string>`),
    ''
  )
  return kept.includes('</map>')
    ? kept.replace('</map>', `    ${entry}\n</map>`)
    : `<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    ${entry}\n</map>\n`
}
