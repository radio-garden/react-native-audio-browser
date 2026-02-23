import type { FormattedNavigationError } from 'react-native-audio-browser'
import React from 'react'
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native'

type NavigationErrorViewProps = {
  error: FormattedNavigationError
  onRetry?: () => void
}

export function NavigationErrorView({
  error,
  onRetry
}: NavigationErrorViewProps) {
  return (
    <View style={styles.container}>
      <Text style={styles.errorTitle}>{error.title}</Text>
      <Text style={styles.errorMessage}>{error.message}</Text>
      {onRetry && (
        <TouchableOpacity style={styles.retryButton} onPress={onRetry}>
          <Text style={styles.retryButtonText}>Retry</Text>
        </TouchableOpacity>
      )}
    </View>
  )
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#000000',
    padding: 24
  },
  errorTitle: {
    color: 'white',
    fontSize: 20,
    fontWeight: '600',
    textAlign: 'center',
    marginBottom: 8
  },
  errorMessage: {
    color: '#888888',
    fontSize: 16,
    textAlign: 'center',
    marginBottom: 24
  },
  retryButton: {
    backgroundColor: '#007AFF',
    paddingVertical: 12,
    paddingHorizontal: 24,
    borderRadius: 8
  },
  retryButtonText: {
    color: '#ffffff',
    fontSize: 16,
    fontWeight: '500'
  }
})
