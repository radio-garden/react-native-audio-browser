import AVFoundation
import AVKit
import Foundation
import MediaPlayer
import NitroModules
import os.log

public extension HybridAudioBrowser {
  // MARK: - Equalizer (unsupported on iOS)

  func getEqualizerSettings() throws -> EqualizerSettings? {
    nil
  }

  func setEqualizerEnabled(enabled _: Bool) throws {
    // Unsupported on iOS.
  }

  func setEqualizerPreset(preset _: String) throws {
    // Unsupported on iOS.
  }

  func setEqualizerLevels(levels _: [Double]) throws {
    // Unsupported on iOS.
  }

  // MARK: - Battery (Android-only, stub implementations)

  func getBatteryWarningPending() throws -> Bool {
    false
  }

  func getBatteryOptimizationStatus() throws -> BatteryOptimizationStatus {
    .unrestricted
  }

  func dismissBatteryWarning() throws {
    // Nothing to do on iOS.
  }

  func openBatterySettings() throws {
    // Nothing to do on iOS.
  }
}
