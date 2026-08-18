// Font/media files are handled by the `asset/resource` webpack rules in
// next.config.js, which hand back a URL string at import time.
declare module '*.ttf' {
  const src: string
  export default src
}
