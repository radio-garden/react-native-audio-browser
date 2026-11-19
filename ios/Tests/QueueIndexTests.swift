import Testing

@testable import AudioBrowserTestable

@Suite("Int(queueIndex:)")
struct QueueIndexTests {
  @Test(arguments: [
    Double.nan,
    .infinity,
    -Double.infinity,
    1e19,
    -1e19,
    Double(Int.max),
  ])
  func numberNoIntHolds_throwsInvalidIndex(number: Double) {
    #expect {
      _ = try Int(queueIndex: number)
    } throws: { error in
      guard case .invalidIndex(nil, _)? = error as? TrackPlayerError.QueueError else { return false }
      return true
    }
  }

  @Test(arguments: [
    (0.0, 0),
    (2.0, 2),
    (1.7, 1),
    (-0.5, 0),
    (-1.0, -1),
    (Double(Int.min), Int.min),
  ])
  func representableNumber_dropsTheFraction(number: Double, index: Int) throws {
    #expect(try Int(queueIndex: number) == index)
  }

  @Test func unrepresentableNumber_isNamedInTheMessage() {
    #expect {
      _ = try Int(queueIndex: .nan)
    } throws: { error in
      error.localizedDescription == "Invalid track index: index must be a finite number within the range of Int, got nan"
    }
  }
}
