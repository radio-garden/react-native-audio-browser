// Sendable is sound here because @MainActor confines every conformer to the
// main actor. It lets LoadSeekCoordinator hand a delegate reference to
// AVPlayer's @Sendable seek completion closure without a data-race
// diagnostic; the delegate is still only ever touched on the main actor.
@MainActor protocol SeekCompletionHandler: AnyObject, Sendable {
  func handleSeekCompleted(to seconds: Double, didFinish: Bool)
}
