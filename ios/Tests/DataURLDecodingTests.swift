import Foundation
import Testing

/// What ArtworkImageFetcher's data: URL provider relies on: Foundation decodes an RFC 2397 URL.
@Suite("data: URL decoding")
struct DataURLDecodingTests {
  private static let png1x1 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

  private func decoded(_ uri: String) throws -> Data {
    try Data(contentsOf: #require(URL(string: uri)))
  }

  @Test func aBase64PayloadAfterMediaTypeParametersDecodesToItsBytes() throws {
    #expect(try decoded("data:image/png;name=a.png;base64,\(Self.png1x1)") == Data(base64Encoded: Self.png1x1))
  }

  @Test func aPlainPayloadIsPercentDecoded() throws {
    #expect(try decoded("data:image/svg+xml,%3Csvg%20xmlns%3D%22a%22%2F%3E") == Data(#"<svg xmlns="a"/>"#.utf8))
  }

  @Test func anInvalidBase64PayloadThrows() {
    #expect(throws: (any Error).self) { try decoded("data:image/png;base64,not*base64") }
  }
}
