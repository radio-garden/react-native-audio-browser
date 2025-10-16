# Android Auto Content Hierarchy Guidelines

## Root Level Constraints

### Maximum 4 Root Items
- Android Auto limits root-level tabs/items to **4 maximum**
- Check `rootHints.getInt(CONTENT_STYLE_SUPPORTED_ACTIONS)` for dynamic limits
- Our API should return a MediaList with ≤4 top-level children

### Root Items Must Be Browsable
- **Only browsable items** (MediaLink/MediaList) can be root-level tabs
- **No MediaItems** at root level - these must be nested under browsable containers
- Root items should have:
  - Monochrome icons (preferably white)
  - Short, meaningful titles
  - `playable: false` or omitted

```typescript
// ✅ Valid root response
{
  children: [
    { title: "Browse", href: "/browse", icon: "browse.svg" },    // MediaLink
    { title: "Search", href: "/search", icon: "search.svg" },   // MediaLink
    { title: "Recents", href: "/recent", icon: "recent.svg" },  // MediaLink
    { title: "Favorites", href: "/faves", icon: "faves.svg" }   // MediaLink
  ]
}

// ❌ Invalid - playable items at root
{
  children: [
    { title: "Song 1", src: "stream.mp3" }  // MediaItem not allowed at root
  ]
}
```

## MediaBrowser Service Implementation

### onGetRoot() Requirements
- **Validate package access** - check authorized apps only
- **Return quickly** to prevent timeouts
- **Minimal logic** - defer complex operations to onLoadChildren()
- Return root ID that maps to your API's root endpoint

### onLoadChildren() Implementation
- Maps to your API endpoints (MediaList responses)
- Handle **authentication** and **complex logic** here
- Transform your API response to MediaBrowserCompat.MediaItem objects
- Each item needs **unique ID** (use the `url` property)

## Content Organization Best Practices

### Hierarchical Structure
```
Root (≤4 items, browsable only)
├── Browse Stations
│   ├── By Country (MediaLink, browsable)
│   ├── By Genre (MediaLink, browsable)
│   └── Featured (MediaLink, browsable)
├── Search
├── Recent Stations
└── Favorites
```

### Using Our API Types

#### MediaList → MediaBrowserCompat.MediaItem
```typescript
// Our API response
{
  title: "Nederland",
  url: "/visit/netherlands",
  playable: true,
  children: [...]
}

// Maps to Android Auto
MediaBrowserCompat.MediaItem(
  mediaDescription = MediaDescriptionCompat.Builder()
    .setMediaId("/visit/netherlands")
    .setTitle("Nederland")
    .setExtras(playableExtras)  // From playable: true
    .build(),
  flags = FLAG_BROWSABLE | FLAG_PLAYABLE  // Both flags when playable: true
)
```

#### MediaItem → MediaBrowserCompat.MediaItem
```typescript
// Our API response
{
  title: "Radio 1",
  url: "/listen/radio1",
  src: "https://stream-url",
  subtitle: "Hilversum"
}

// Maps to Android Auto
MediaBrowserCompat.MediaItem(
  mediaDescription = MediaDescriptionCompat.Builder()
    .setMediaId("/listen/radio1")
    .setTitle("Radio 1")
    .setSubtitle("Hilversum")
    .setMediaUri(Uri.parse("https://stream-url"))
    .build(),
  flags = FLAG_PLAYABLE  // Only playable, not browsable
)
```

#### MediaItemSection → Grouped Items
```typescript
// Our API response
{
  title: "Local Stations",
  children: [
    { title: "Radio A", src: "..." },
    { title: "Radio B", src: "..." }
  ]
}

// Maps to Android Auto (flattened with groupTitle)
[
  MediaItem(groupTitle="Local Stations", title="Radio A"),
  MediaItem(groupTitle="Local Stations", title="Radio B")
]
```

## Performance Considerations

### Fast Root Access
- Root endpoint should be **extremely fast**
- Consider caching root structure
- Minimal database queries for root response

### Lazy Loading
- Load content in `onLoadChildren()` based on specific mediaId
- Each MediaLink href becomes a separate onLoadChildren() call
- Don't pre-fetch all content - load on demand

### Authentication Flow
```typescript
// In onGetRoot() - fast validation only
if (!isAuthorizedPackage(clientPackageName)) {
  return null; // Deny access
}
return new BrowserRoot(ROOT_ID, null);

// In onLoadChildren() - handle auth state
if (mediaId.equals(ROOT_ID)) {
  if (!userAuthenticated) {
    // Return sign-in prompt items
    return authPromptItems;
  }
  // Return normal root content
  return rootItems;
}
```

## Content Limits & Optimization

- **Check root hints** for dynamic limits
- **Minimize depth** - prefer broad vs deep hierarchies
- **Group related content** using MediaItemSection
- **Use clear, descriptive titles** - drivers need quick recognition
- **Optimize for voice commands** - clear, speakable titles

## Radio Garden Specific Implementation

For Radio Garden transformation:
1. **Root**: ≤4 main categories (Browse, Search, Favorites, Recent)
2. **Browse**: Countries/regions as MediaLinks
3. **Country pages**: Cities as MediaLinks + featured stations as MediaItems
4. **City pages**: Local stations as MediaItems, grouped by type
5. **Playable containers**: Countries/cities with `playable: true` for "random station"