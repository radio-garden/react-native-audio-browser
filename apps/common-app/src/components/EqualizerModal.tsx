import React from 'react'
import { Modal, StyleSheet, Text, TouchableOpacity, View } from 'react-native'
import { EqualizerView } from './EqualizerView'

type Props = {
  visible: boolean
  onClose: () => void
}

export function EqualizerModal({ visible, onClose }: Props) {
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
        <EqualizerView />
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
  }
})
