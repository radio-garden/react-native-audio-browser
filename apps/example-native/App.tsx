import React from 'react'
import { StyleSheet } from 'react-native'
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context'
import { BatteryWarning } from './src/components/BatteryWarning'
import { BrowserScreen } from './src/screens/BrowserScreen'

export default function App() {
  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.container}>
        <BatteryWarning />
        <BrowserScreen />
      </SafeAreaView>
    </SafeAreaProvider>
  )
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000000'
  }
})
