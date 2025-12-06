import AVFoundation
import Foundation

enum TimeEventFrequency {
  case everySecond
  case everyHalfSecond
  case everyQuarterSecond
  case custom(time: CMTime)

  func getTime() -> CMTime {
    switch self {
    case .everySecond: return CMTime(value: 1, timescale: 1)
    case .everyHalfSecond: return CMTime(value: 1, timescale: 2)
    case .everyQuarterSecond: return CMTime(value: 1, timescale: 4)
    case let .custom(time): return time
    }
  }
}
