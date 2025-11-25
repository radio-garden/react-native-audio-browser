import React from 'react'
import {
  Modal,
  StyleSheet,
  Text,
  TouchableOpacity,
  View
} from 'react-native'
import {
  clearSleepTimer,
  setSleepTimer,
  setSleepTimerToEndOfTrack,
  useSleepTimer
} from 'react-native-audio-browser'

type Props = {
  visible: boolean
  onClose: () => void
}

export function SleepTimerModal({ visible, onClose }: Props) {
  const sleepTimer = useSleepTimer()

  return (
    <Modal
      visible={visible}
      animationType="slide"
      presentationStyle="pageSheet"
      onRequestClose={onClose}
    >
      <View style={styles.modalContainer}>
        <View style={styles.modalHeader}>
          <TouchableOpacity style={styles.modalCloseButton} onPress={onClose}>
            <Text style={styles.modalCloseButtonText}>Done</Text>
          </TouchableOpacity>
        </View>
        <View style={styles.sleepTimerContent}>
          <Text style={styles.sleepTimerTitle}>Sleep Timer</Text>

          {sleepTimer && (
            <View style={styles.sleepTimerStatus}>
              {'secondsLeft' in sleepTimer ? (
                <>
                  <Text style={styles.sleepTimerStatusText}>
                    Stops in {Math.floor(sleepTimer.secondsLeft / 60)}m{' '}
                    {sleepTimer.secondsLeft % 60}s
                  </Text>
                  <TouchableOpacity
                    style={styles.clearTimerButton}
                    onPress={() => clearSleepTimer()}
                  >
                    <Text style={styles.clearTimerButtonText}>Clear</Text>
                  </TouchableOpacity>
                </>
              ) : (
                <>
                  <Text style={styles.sleepTimerStatusText}>
                    Stops at end of track
                  </Text>
                  <TouchableOpacity
                    style={styles.clearTimerButton}
                    onPress={() => clearSleepTimer()}
                  >
                    <Text style={styles.clearTimerButtonText}>Clear</Text>
                  </TouchableOpacity>
                </>
              )}
            </View>
          )}

          <View style={styles.sleepTimerOptions}>
            <TouchableOpacity
              style={styles.sleepTimerButton}
              onPress={() => {
                setSleepTimer(5 * 60)
                onClose()
              }}
            >
              <Text style={styles.sleepTimerButtonText}>5 minutes</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.sleepTimerButton}
              onPress={() => {
                setSleepTimer(15 * 60)
                onClose()
              }}
            >
              <Text style={styles.sleepTimerButtonText}>15 minutes</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.sleepTimerButton}
              onPress={() => {
                setSleepTimer(30 * 60)
                onClose()
              }}
            >
              <Text style={styles.sleepTimerButtonText}>30 minutes</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.sleepTimerButton}
              onPress={() => {
                setSleepTimer(60 * 60)
                onClose()
              }}
            >
              <Text style={styles.sleepTimerButtonText}>1 hour</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.sleepTimerButton}
              onPress={() => {
                setSleepTimerToEndOfTrack()
                onClose()
              }}
            >
              <Text style={styles.sleepTimerButtonText}>End of track</Text>
            </TouchableOpacity>
          </View>
        </View>
      </View>
    </Modal>
  )
}

const styles = StyleSheet.create({
  modalContainer: {
    flex: 1,
    backgroundColor: '#000000'
  },
  modalHeader: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingTop: 16,
    paddingBottom: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#333333'
  },
  modalCloseButton: {
    padding: 8
  },
  modalCloseButtonText: {
    color: '#007AFF',
    fontSize: 16,
    fontWeight: '600'
  },
  sleepTimerContent: {
    flex: 1,
    padding: 20
  },
  sleepTimerTitle: {
    fontSize: 28,
    fontWeight: 'bold',
    color: '#ffffff',
    marginBottom: 24
  },
  sleepTimerStatus: {
    backgroundColor: '#1a1a1a',
    padding: 16,
    borderRadius: 12,
    marginBottom: 24,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between'
  },
  sleepTimerStatusText: {
    color: '#ffffff',
    fontSize: 16
  },
  clearTimerButton: {
    backgroundColor: '#ff3b30',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 8
  },
  clearTimerButtonText: {
    color: '#ffffff',
    fontSize: 14,
    fontWeight: '600'
  },
  sleepTimerOptions: {
    gap: 12
  },
  sleepTimerButton: {
    backgroundColor: '#1a1a1a',
    padding: 16,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#333333'
  },
  sleepTimerButtonText: {
    color: '#ffffff',
    fontSize: 16,
    textAlign: 'center'
  }
})
