# The browse-address field is named `path`, not `url`

**Status:** accepted

The browse-tree address on `Track` (and `ResolvedTrack`, `ImageRowItem`) is named **`path`**, renamed from `url`. The album-line companion followed: `albumUrl` → `albumPath`, `resolveAlbumUrl` → `resolveAlbumPath`, `ResolveAlbumUrlCallback` → `ResolveAlbumPathCallback`. The rename is total: the TypeScript API, the Nitro-generated native types, the JSON wire key parsed from browse endpoints, and the native resume-state persistence keys all say `path`. There is no alias reading the old name.

The field never held a URL. It holds a **Path** — the glossary's term for a position in the BrowseTree (`/albums/abbey-road`), matched against routes and passed to `navigate()`. Every adjacent API already said so: `navigate(path)`, `getPath()`, `BrowserConfiguration.path`, route patterns. The glossary explicitly listed "URL" under _Avoid_ for tree positions while the field itself was named `url` — documentation fighting the API's own vocabulary, with every new reader re-deriving the distinction.

The name also collided at the consumer boundary. Apps that follow the web convention name the playable stream URL `url` on their own track types, so at the conversion boundary the same word meant "browse address" on one side and "stream URL" on the other — observed in practice as converters swapping `url`↔`src` in both directions with apologetic comments explaining which `url` is which. Renaming the browse address ends the collision: `url` on an app type can only mean a real URL.

The timing made the rename cheap: no app using the library had shipped (npm 0.2.0), so there was no installed base parsing the old wire key and no persisted state worth migrating.

## Considered options

- **Keep `url`, sharpen the docs** — rejected. The glossary already carried the disambiguation and it demonstrably didn't stick; prose cannot fix a name that asserts the wrong type. The consumer-boundary collision is untouchable by library docs entirely.
- **Deprecation window (accept both keys)** — rejected. The wire key is parsed natively — Swift `Codable` and kotlinx `@Serializable` derive JSON keys from property names, with server JSON reaching the parsers cold, no JS in the loop. A window means dual-key parsing plus a precedence rule when both appear, in three implementations — keeping alive precisely the ambiguity the rename removes, to protect consumers that don't exist. Pre-1.0 semver plus a `BREAKING CHANGE` changelog entry is the honest mechanism.
- **A qualified name (`browsePath`)** — rejected. The unqualified `path` is what the rest of the API already uses (`navigate(path)`, `BrowserConfiguration.path`); qualifying only the Track field would diverge from the namespace it belongs to.
- **Hard rename to `path` (chosen)** — one wire format, one name, compile-time migration for TS consumers.

## Consequences

- **Servers must emit `path`/`albumPath`.** A server still emitting `url` produces tracks that are neither browsable nor playable-with-context; the failure is immediate and visible (and schema validation downstream can reject the old key outright).
- **`path` now has two senses** — `Track.path` (tree address) and `RequestConfig.path` (HTTP request path). They never co-occur on one object, and sharing the name is deliberate; the ambiguity is documented in CONTEXT.md's flagged ambiguities.
- **Static-page detection changed.** A `BrowserSource` was classified as a request config partly by the presence of a `path` key; a static `ResolvedTrack` page now also carries a top-level `path`, so pages are recognized first by their required `title` (no RequestConfig field is named `title`). Regression tests pin this.
- **Old persisted resume state degrades gracefully, once.** State written under the old keys resumes the track by `src` at the saved position; only the contextual queue expansion is skipped. The next save writes the new keys.
- **The old name may not be reintroduced as an alias** — a `url` field on Track-shaped types is reserved for consumers' own stream-URL fields.
