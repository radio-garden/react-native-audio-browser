import AVFoundation
import Testing

/// A 0.1 s silent WAV in the temporary directory, which AVFoundation loads as playable. The caller
/// removes it.
func makeSilentAudioFile() throws -> URL {
  let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).wav")
  let format = try #require(AVAudioFormat(standardFormatWithSampleRate: 8000, channels: 1))
  let buffer = try #require(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 800))
  buffer.frameLength = 800
  try AVAudioFile(forWriting: url, settings: format.settings).write(from: buffer)
  return url
}
