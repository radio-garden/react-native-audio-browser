import Slider from '@react-native-community/slider'
import React, { useState } from 'react'
import {
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View
} from 'react-native'
import {
  setEqualizerEnabled,
  setEqualizerLevels,
  setEqualizerPreset,
  useEqualizerSettings
} from 'react-native-audio-browser'

export function EqualizerView() {
  const settings = useEqualizerSettings()
  const [localLevels, setLocalLevels] = useState<number[]>([])

  if (!settings) {
    return (
      <View style={styles.container}>
        <Text style={styles.unavailableText}>
          Equalizer not available on this device
        </Text>
      </View>
    )
  }

  // Use local levels while dragging, otherwise use settings
  const displayLevels =
    localLevels.length > 0 ? localLevels : settings.bandLevels

  const handleBandChange = (bandIndex: number, value: number) => {
    // Update local state for smooth dragging
    const newLevels = [...settings.bandLevels]
    newLevels[bandIndex] = value
    setLocalLevels(newLevels)
  }

  const handleBandChangeComplete = (bandIndex: number, value: number) => {
    // Apply to native when done dragging
    const newLevels = [...settings.bandLevels]
    newLevels[bandIndex] = value
    setEqualizerLevels(newLevels)
  }

  const handlePresetPress = (preset: string) => {
    setEqualizerPreset(preset)
    setEqualizerEnabled(true)
  }

  const handleToggle = () => {
    setEqualizerEnabled(!settings.enabled)
  }

  // Format frequency for display
  const formatFrequency = (freq: number): string => {
    if (freq >= 1000) {
      return `${(freq / 1000).toFixed(1)}k`
    }
    return `${Math.round(freq)}`
  }

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.title}>Equalizer</Text>
        <TouchableOpacity
          style={[
            styles.toggleButton,
            settings.enabled && styles.toggleButtonActive
          ]}
          onPress={handleToggle}
        >
          <Text
            style={[styles.toggleButtonText, styles.toggleButtonTextActive]}
          >
            {settings.enabled ? 'DISABLE' : 'ENABLE'}
          </Text>
        </TouchableOpacity>
      </View>

      {/* Presets */}
      <View style={styles.section}>
        <Text style={styles.sectionTitle}>Presets</Text>
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          style={styles.presetsScroll}
        >
          {settings.presets.map((preset) => (
            <TouchableOpacity
              key={preset}
              style={[
                styles.presetButton,
                settings.enabled &&
                  settings.activePreset === preset &&
                  styles.presetButtonActive
              ]}
              onPress={() => handlePresetPress(preset)}
            >
              <Text
                style={[
                  styles.presetButtonText,
                  settings.enabled &&
                    settings.activePreset === preset &&
                    styles.presetButtonTextActive
                ]}
              >
                {preset}
              </Text>
            </TouchableOpacity>
          ))}
        </ScrollView>
      </View>

      {/* Band Sliders */}
      <View style={styles.section}>
        <Text style={styles.sectionTitle}>
          {settings.activePreset ? settings.activePreset : 'Custom'}
        </Text>
        <View style={styles.bandsContainer}>
          {displayLevels.map((level, index) => (
            <View key={index} style={styles.bandSlider}>
              <Slider
                style={styles.slider}
                minimumValue={settings.lowerBandLevelLimit}
                maximumValue={settings.upperBandLevelLimit}
                value={level}
                onValueChange={(value) => handleBandChange(index, value)}
                onSlidingComplete={(value) =>
                  handleBandChangeComplete(index, value)
                }
                minimumTrackTintColor="#007AFF"
                maximumTrackTintColor="#333333"
                thumbTintColor="#007AFF"
                disabled={!settings.enabled}
              />
              <Text style={styles.bandLevel}>{Math.round(level)}</Text>
              <Text style={styles.bandFrequency}>
                {formatFrequency(settings.centerBandFrequencies[index])}
              </Text>
            </View>
          ))}
        </View>
        <View style={styles.bandsLegend}>
          <Text style={styles.legendText}>
            {Math.round(settings.lowerBandLevelLimit)} dB
          </Text>
          <Text style={styles.legendText}>
            {Math.round(settings.upperBandLevelLimit)} dB
          </Text>
        </View>
      </View>

      {/* Info */}
      <View style={styles.info}>
        <Text style={styles.infoText}>
          Bands: {Math.round(settings.bandCount)}
        </Text>
        <Text style={styles.infoText}>
          Range: {Math.round(settings.lowerBandLevelLimit)} to{' '}
          {Math.round(settings.upperBandLevelLimit)} dB
        </Text>
      </View>
    </ScrollView>
  )
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000000'
  },
  content: {
    padding: 16
  },
  unavailableText: {
    color: '#888888',
    fontSize: 16,
    textAlign: 'center',
    marginTop: 40
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 24
  },
  title: {
    fontSize: 28,
    fontWeight: 'bold',
    color: '#ffffff'
  },
  toggleButton: {
    paddingHorizontal: 20,
    paddingVertical: 8,
    borderRadius: 20,
    backgroundColor: '#333333',
    minWidth: 70,
    alignItems: 'center'
  },
  toggleButtonActive: {
    backgroundColor: '#007AFF'
  },
  toggleButtonText: {
    color: '#888888',
    fontSize: 16,
    fontWeight: '600'
  },
  toggleButtonTextActive: {
    color: '#ffffff'
  },
  section: {
    marginBottom: 32
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#ffffff',
    marginBottom: 12
  },
  presetsScroll: {
    flexGrow: 0
  },
  presetButton: {
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: 8,
    backgroundColor: '#1a1a1a',
    marginRight: 8,
    borderWidth: 1,
    borderColor: '#333333'
  },
  presetButtonActive: {
    backgroundColor: '#007AFF',
    borderColor: '#007AFF'
  },
  presetButtonText: {
    color: '#888888',
    fontSize: 14,
    fontWeight: '500'
  },
  presetButtonTextActive: {
    color: '#ffffff',
    fontWeight: '600'
  },
  bandsContainer: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    alignItems: 'flex-end',
    height: 200,
    paddingHorizontal: 8
  },
  bandSlider: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'space-between',
    marginHorizontal: 2
  },
  slider: {
    width: 140,
    height: 40,
    transform: [{ rotate: '-90deg' }],
    marginBottom: 60
  },
  bandLevel: {
    color: '#ffffff',
    fontSize: 11,
    marginTop: 8,
    fontWeight: '500'
  },
  bandFrequency: {
    color: '#666666',
    fontSize: 10,
    marginTop: 2
  },
  bandsLegend: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 8,
    paddingHorizontal: 8
  },
  legendText: {
    color: '#666666',
    fontSize: 12
  },
  info: {
    marginTop: 16,
    padding: 16,
    backgroundColor: '#1a1a1a',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#333333'
  },
  infoText: {
    color: '#888888',
    fontSize: 14,
    marginBottom: 4
  }
})
