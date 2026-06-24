# Docs

## Vocabulary

The domain glossary in [`../CONTEXT.md`](../CONTEXT.md) is the source of truth for terms. No need to be rigid — readability comes first — but when a glossary term fits naturally, prefer it over a friendlier-sounding synonym, and it's worth a glance when you're unsure.

## Building

```bash
yarn build
```

Runs TypeDoc → transforms sidebar → cleans markdown → builds VitePress.

## Sidebar & Page Ordering

Shared logic in `scripts/base-name.ts` controls both sidebar links and page content order.

**Priority:** `(no prefix, lowercase) > use > get > set > update > toggle > handle > on > has > (uppercase types)`

Example: `search`, `hasSearch`, `Search` → sidebar links to `search`.
