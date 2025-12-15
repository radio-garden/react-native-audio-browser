import Clipboard from '@react-native-clipboard/clipboard'
import React, { useRef, useState } from 'react'
import {
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View
} from 'react-native'
import { useDebug } from 'react-native-audio-browser'

export function DebugPanel() {
  const [expanded, setExpanded] = useState(false)
  const [copied, setCopied] = useState(false)
  const [tab, setTab] = useState<'state' | 'logs'>('state')
  const scrollViewRef = useRef<ScrollView>(null)

  const debug = useDebug({ metadata: true })
  const stateJson = JSON.stringify(debug.state, null, 2)

  const handleCopy = () => {
    const content =
      tab === 'state'
        ? stateJson
        : debug.logs
            .map((l) => `[+${l.elapsed ?? 0}ms] ${l.message}`)
            .join('\n\n')
    Clipboard.setString(content)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  if (!expanded) {
    return (
      <TouchableOpacity
        style={styles.collapsedButton}
        onPress={() => setExpanded(true)}
      >
        <Text style={styles.collapsedText}>Debug</Text>
      </TouchableOpacity>
    )
  }

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => setExpanded(false)}>
          <Text style={styles.headerButton}>Close</Text>
        </TouchableOpacity>
        <View style={styles.tabs}>
          <TouchableOpacity onPress={() => setTab('state')}>
            <Text style={[styles.tabText, tab === 'state' && styles.activeTab]}>
              State
            </Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={() => setTab('logs')}>
            <Text style={[styles.tabText, tab === 'logs' && styles.activeTab]}>
              Logs ({debug.logs.length})
            </Text>
          </TouchableOpacity>
        </View>
        <View style={styles.headerActions}>
          {tab === 'logs' && (
            <TouchableOpacity onPress={debug.clear}>
              <Text style={styles.headerButton}>Clear</Text>
            </TouchableOpacity>
          )}
          <TouchableOpacity onPress={handleCopy}>
            <Text style={styles.headerButton}>
              {copied ? 'Copied!' : 'Copy'}
            </Text>
          </TouchableOpacity>
        </View>
      </View>
      <ScrollView
        ref={scrollViewRef}
        style={styles.scrollView}
        onContentSizeChange={() => {
          if (tab === 'logs') {
            scrollViewRef.current?.scrollToEnd({ animated: false })
          }
        }}
      >
        {tab === 'state' ? (
          <Text style={styles.stateText}>{stateJson}</Text>
        ) : debug.logs.length === 0 ? (
          <Text style={styles.emptyText}>No logs yet</Text>
        ) : (
          debug.logs.map((log, index) => (
            <View key={index} style={styles.logEntry}>
              <Text style={styles.logTime}>
                {log.elapsed != null ? `+${log.elapsed}ms` : 'init'}
              </Text>
              <Text style={styles.logMessage}>{log.message}</Text>
            </View>
          ))
        )}
      </ScrollView>
    </View>
  )
}

const styles = StyleSheet.create({
  collapsedButton: {
    position: 'absolute',
    bottom: 140,
    right: 16,
    backgroundColor: '#333',
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 16
  },
  collapsedText: {
    color: '#888',
    fontSize: 12
  },
  container: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    height: '60%',
    backgroundColor: '#1a1a1a',
    borderTopLeftRadius: 16,
    borderTopRightRadius: 16
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 16,
    borderBottomWidth: 1,
    borderBottomColor: '#333'
  },
  tabs: {
    flexDirection: 'row',
    gap: 16
  },
  tabText: {
    color: '#666',
    fontSize: 14,
    fontWeight: '600'
  },
  activeTab: {
    color: '#fff'
  },
  headerActions: {
    flexDirection: 'row',
    gap: 16
  },
  headerButton: {
    color: '#007AFF',
    fontSize: 14
  },
  scrollView: {
    flex: 1,
    padding: 16
  },
  stateText: {
    color: '#aaa',
    fontSize: 11,
    fontFamily: 'monospace'
  },
  emptyText: {
    color: '#666',
    fontSize: 12,
    textAlign: 'center',
    marginTop: 32
  },
  logEntry: {
    marginBottom: 12,
    borderLeftWidth: 2,
    borderLeftColor: '#444',
    paddingLeft: 8
  },
  logTime: {
    color: '#007AFF',
    fontSize: 10,
    fontFamily: 'monospace',
    marginBottom: 2
  },
  logMessage: {
    color: '#aaa',
    fontSize: 11,
    fontFamily: 'monospace'
  }
})
