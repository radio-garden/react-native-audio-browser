@MainActor protocol SeekCompletionHandler: AnyObject {
  func handleSeekCompleted(to seconds: Double, didFinish: Bool)
}
