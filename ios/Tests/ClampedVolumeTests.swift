import Testing

@testable import AudioBrowserTestable

@Suite("clampedVolume")
struct ClampedVolumeTests {
  @Test func keepsALevelInRange() {
    #expect(clampedVolume(0) == 0)
    #expect(clampedVolume(0.25) == 0.25)
    #expect(clampedVolume(1) == 1)
  }

  @Test func raisesALevelBelowZeroToSilence() {
    #expect(clampedVolume(-0.5) == 0)
    #expect(clampedVolume(-.infinity) == 0)
  }

  @Test func lowersALevelAboveOneToFull() {
    #expect(clampedVolume(1.5) == 1)
    #expect(clampedVolume(.infinity) == 1)
  }

  @Test func hasNoVolumeForNaN() {
    #expect(clampedVolume(.nan) == nil)
  }
}
