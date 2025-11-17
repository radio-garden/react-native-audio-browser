import React from 'react'
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native'
import type { NavigationError } from 'react-native-audio-browser'

type NavigationErrorViewProps = {
  error: NavigationError
  onRetry?: () => void
}

export function NavigationErrorView({
  error,
  onRetry
}: NavigationErrorViewProps) {
  return (
    <View style={styles.container}>
      <Text style={styles.errorText}>⚠️ {error.message}</Text>
      <Text style={styles.errorCode}>Error: {error.code}</Text>
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
    backgroundColor: '#000000'
  },
  errorText: {
    color: '#ff4444',
    fontSize: 18,
    fontWeight: '600',
    textAlign: 'center',
    marginBottom: 8
  },
  errorCode: {
    color: '#888888',
    fontSize: 14,
    textAlign: 'center',
    marginBottom: 16
  },
  retryButton: {
    backgroundColor: '#007AFF',
    padding: 12,
    borderRadius: 8
  },
  retryButtonText: {
    color: '#ffffff',
    fontSize: 16
  }
})
