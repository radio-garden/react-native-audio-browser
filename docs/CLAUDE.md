# Docs

## Building

```bash
yarn build
```

Runs TypeDoc → transforms sidebar → cleans markdown → builds VitePress.

## Sidebar & Page Ordering

Shared logic in `scripts/base-name.ts` controls both sidebar links and page content order.

**Priority:** `(no prefix, lowercase) > use > get > set > update > toggle > handle > on > has > (uppercase types)`

Example: `search`, `hasSearch`, `Search` → sidebar links to `search`.
