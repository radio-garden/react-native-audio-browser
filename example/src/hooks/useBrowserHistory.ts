import { useEffect, useRef, useState } from 'react'
import { navigate, usePath, useTabs } from 'react-native-audio-browser'

/**
 * Manages navigation history for the audio browser.
 *
 * This is a basic implementation that tracks the user's navigation path and provides
 * a back handler when applicable. Automatically resets history when navigating to tab roots.
 *
 * @returns A back navigation handler function, or `undefined` if back navigation
 *          is not available (e.g., at a tab root or no history exists).
 *
 * @example
 * ```tsx
 * function BrowserScreen() {
 *   const handleBackPress = useBrowserHistory();
 *
 *   return (
 *     <View>
 *       {handleBackPress && (
 *         <Button onPress={handleBackPress}>Back</Button>
 *       )}
 *     </View>
 *   );
 * }
 * ```
 */
export function useBrowserHistory(): (() => void) | undefined {
  const path = usePath()
  const tabs = useTabs()
  // Track navigation history - initialize with current path
  const [history, setHistory] = useState<string[]>(() => (path ? [path] : []))
  const isNavigatingBackRef = useRef(false)

  // Update history when path changes
  useEffect(() => {
    if (!path) return

    if (isNavigatingBackRef.current) {
      // We're navigating back, don't add to history
      isNavigatingBackRef.current = false
      return
    }

    // Check if this path is a tab root
    const isTabRoot = tabs?.some((tab) => tab.url === path)

    setHistory((prev) => {
      const currentTop = prev[prev.length - 1]

      // If history is empty, this is the first path - always add it
      if (prev.length === 0) {
        return [path]
      }

      // If navigating to a tab root, reset history to just that tab
      if (isTabRoot) {
        return [path]
      }

      // Check if this is a new path (not already at the top of history)
      if (path !== currentTop) {
        return [...prev, path]
      }
      return prev
    })
  }, [path, tabs])

  // Don't show back button if we're on a tab root
  const isOnTabRoot = tabs?.some((tab) => tab.url === path)
  const canGoBack = history.length > 1 && !isOnTabRoot

  if (!canGoBack) {
    return undefined
  }

  return () => {
    if (history.length > 1) {
      setHistory((history) => history.slice(0, -1))
      isNavigatingBackRef.current = true
      void navigate(history[history.length - 2])
    }
  }
}
