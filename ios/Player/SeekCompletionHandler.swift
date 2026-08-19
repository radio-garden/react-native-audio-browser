// Sendable so LoadSeekCoordinator can carry a delegate into AVPlayer's
// @Sendable seek completion closure; @MainActor is what keeps that sound.
@MainActor protocol SeekCompletionHandler: AnyObject, Sendable {
  func handleSeekCompleted(to seconds: Double, didFinish: Bool)
}
