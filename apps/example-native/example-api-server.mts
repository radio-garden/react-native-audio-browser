import { createServer } from 'http'

const PORT = process.env.EXAMPLE_API_PORT
  ? parseInt(process.env.EXAMPLE_API_PORT, 10)
  : 3003

const root = {
  url: '/api',
  title: 'Example JSON Api',
  children: [
    {
      title: 'Errors',
      subtitle: 'Various example error responses',
      url: '/api/errors'
    }
  ]
}

const errors = [
  [
    503,
    {
      src: '/api/errors/broken.mp3',
      title: 'Broken Track Endpoint (503)'
    }
  ],
  [
    402,
    {
      url: '/api/errors/premium',
      title: 'Premium Music (402)',
      error: 'Payment Required'
    }
  ],
  [
    403,
    {
      url: '/api/errors/forbidden',
      title: 'Forbidden Content (403)'
    }
  ],
  [
    401,
    {
      url: '/api/errors/auth-expired',
      title: 'Auth Expired (401)'
    }
  ],
  [
    451,
    {
      url: '/api/errors/geo-blocked',
      title: 'Geo-blocked (451)'
    }
  ],
  [
    429,
    {
      url: '/api/errors/rate-limited',
      title: 'Rate Limited (429)'
    }
  ]
] as const

// Request handler
const server = createServer((req, res) => {
  const { method, url } = req

  console.log(`${method} ${url}`)

  // Enable CORS for React Native
  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type')

  if (method === 'OPTIONS') {
    res.writeHead(200)
    res.end()
    return
  }

  const path = url
    ? new URL(url, `http://localhost:${PORT}`).pathname
    : undefined

  // Route handling

  if (path === '/api') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(root))
    return
  }

  if (path === '/api/errors') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(
      JSON.stringify({
        url: '/api/errors',
        title: 'Errors',
        children: errors.map(([, track]) => track)
      })
    )
    return
  }

  const error = errors.find(
    ([, track]) =>
      ('url' in track && track.url === path) ||
      ('src' in track && track.src === path)
  )
  if (error) {
    const [code, body] = error
    res.writeHead(code, { 'Content-Type': 'application/json' })
    res.end('error' in body ? JSON.stringify({ error: body.error }) : undefined)
    return
  }

  // 404 for unknown routes
  res.writeHead(404, { 'Content-Type': 'application/json' })
  res.end(
    JSON.stringify({
      error: 'Not Found',
      message: `Route ${path} not found`
    })
  )
})

server.on('error', (error) => {
  if ('code' in error && error.code === 'EADDRINUSE') {
    console.error(`\n❌ Error: Port ${PORT} is already in use.`)
    console.error(`\nPlease either:`)
    console.error(`  1. Stop the process using port ${PORT}`)
    console.error(`  2. Find and kill it: lsof -ti:${PORT} | xargs kill -9`)
    console.error(
      `  3. Run with custom port: EXAMPLE_API_PORT=NEW_PORT yarn api\n`
    )
    process.exit(1)
  } else {
    console.error('Server error:', error)
    process.exit(1)
  }
})

server.listen(PORT, () => {
  console.log(`Audio Browser Test Server running on http://localhost:${PORT}`)

  console.log(
    `   For Android emulator support, make sure to run: ${PORT === 3003 ? '' : `EXAMPLE_API_PORT=${PORT} `}yarn android:adb-reverse\n`
  )

  console.log('\nAvailable routes:')
  console.log('  /api/errors')
  errors.forEach(([, track]) => {
    console.log(`  ${'url' in track ? track.url : track.src} (${track.title})`)
  })
  console.log('\nPress Ctrl+C to stop\n')
})
