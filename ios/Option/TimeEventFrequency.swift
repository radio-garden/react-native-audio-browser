import AVFoundation
import Foundation

enum TimeEventFrequency {
  case everySecond
  case everyHalfSecond
  case everyQuarterSecond
  case custom(time: CMTime)

  func getTime() -> CMTime {
    switch self {
    case .everySecond: CMTime(value: 1, timescale: 1)
    case .everyHalfSecond: CMTime(value: 1, timescale: 2)
    case .everyQuarterSecond: CMTime(value: 1, timescale: 4)
    case let .custom(time): time
    }
  }
}
