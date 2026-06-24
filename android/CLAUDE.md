# Android Development Notes

The Android architecture overview — high-level diagram, component responsibilities, data flows,
threading model, error handling, and testing strategy — lives in
[`ARCHITECTURE.md`](ARCHITECTURE.md). Keep it in sync when you change the structure, or regenerate the
diagram with the `/android-diagram` skill.

## Code Style Guidelines

### Imports

- **Always add proper imports** instead of using fully-qualified names inline
- **Avoid inline package references** like `com.margelo.nitro.audiobrowser.SearchMode.UNSTRUCTURED`
- Add import at the top of the file and use the short name

**Bad:**

```kotlin
val mode = com.margelo.nitro.audiobrowser.SearchMode.UNSTRUCTURED
```

**Good:**

```kotlin
import com.margelo.nitro.audiobrowser.SearchMode

val mode = SearchMode.UNSTRUCTURED
```
