/**
 * The probe reports to the server console: only the redirect target knows
 * whether the credential came along, and logging here keeps each run
 * attributable to the client that made it.
 */

/**
 * A short name for the client behind a User-Agent. The library sets its own on
 * Android, so ExoPlayer arrives as "react-native-audio-browser" and never
 * mentions Android; iOS sets none, so `AppleCoreMedia` shows through. Match the
 * library's agent first or the platform that matters most is mislabelled.
 */
export const clientLabel = (userAgent: string | undefined): string => {
  if (!userAgent) return 'unknown'
  if (/react-native-audio-browser/i.test(userAgent))
    return 'Android (ExoPlayer)'
  if (/AppleCoreMedia|CFNetwork|Darwin/i.test(userAgent)) return 'iOS/macOS'
  if (/ExoPlayer|okhttp|Android/i.test(userAgent)) return 'Android'
  if (/curl/i.test(userAgent)) return 'curl'
  return userAgent.slice(0, 40)
}

const RULE = '━'.repeat(64)

export function logHop(
  hop: 1 | 2,
  {
    host,
    authorization,
    userAgent
  }: {
    host: string | undefined
    authorization: string | undefined
    userAgent: string | undefined
  }
): void {
  const client = clientLabel(userAgent)
  const token = authorization ?? '(none)'

  if (hop === 1) {
    console.log(`\n${RULE}`)
    console.log(`  REDIRECT PROBE — ${client}`)
    console.log(`  user-agent: ${userAgent || '(none)'}`)
    console.log(`${RULE}`)
    console.log(
      `  hop 1  ${host}  →  Authorization: ${token}  ${
        authorization ? '✓ sent' : '✗ NOT SENT — media config not applied'
      }`
    )
    return
  }

  console.log(
    `  hop 2  ${host}  →  Authorization: ${token}  ${
      authorization ? '✗ FORWARDED' : '✓ dropped'
    }`
  )
  // The User-Agent at each hop doubles as a check on header precedence: the
  // library sets a default agent on the factory but lets a per-request
  // DataSpec header override it, and that ordering differs between
  // DefaultHttpDataSource and OkHttpDataSource. Two agents here, or the wrong
  // one, means the override broke.
  console.log(`  hop 2  user-agent: ${userAgent || '(none)'}`)
  console.log(
    authorization
      ? `  VERDICT: ${client} re-sends the credential across origins — leak`
      : `  VERDICT: ${client} does not forward the credential — no leak`
  )
  console.log(`${RULE}\n`)
}

/** A tiny silent MP3, so the probe's hop count is exactly one. */
export const SILENT_MP3 = Buffer.from('fffb90c4' + '00'.repeat(100), 'hex')
