import AVFoundation
import AVKit
import Foundation
import MediaPlayer
import NitroModules
import os.log

public extension HybridAudioBrowser {
  // MARK: - Equalizer (unsupported on iOS)

  func getEqualizerSettings() throws -> EqualizerSettings? {
    // No-op: equalizer unsupported on iOS
    nil
  }

  func setEqualizerEnabled(enabled _: Bool) throws {
    // No-op: equalizer unsupported on iOS
  }

  func setEqualizerPreset(preset _: String) throws {
    // No-op: equalizer unsupported on iOS
  }

  func setEqualizerLevels(levels _: [Double]) throws {
    // No-op: equalizer unsupported on iOS
  }

  // MARK: - Battery (Android-only, stub implementations)

  func getBatteryWarningPending() throws -> Bool {
    false
  }

  func getBatteryOptimizationStatus() throws -> BatteryOptimizationStatus {
    // iOS doesn't have battery optimization restrictions like Android
    .unrestricted
  }

  func dismissBatteryWarning() throws {
    // No-op on iOS
  }

  func openBatterySettings() throws {
    // No-op on iOS
  }
}
