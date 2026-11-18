@testable import AudioBrowserTestable
import Testing

/// Without a non-blank id the `{id}` token stays literal, never blanked or skipped, so the
/// request fails with a self-describing URL. Android's `substituteTrackId` must match.
struct TrackIdTemplateTests {
  // MARK: substitution with an id

  @Test func substitutesTokenInString() {
    #expect(TrackIdTemplate.substitute("/artwork/{id}", id: "abc") == "/artwork/abc")
  }

  @Test func substitutesEveryOccurrence() {
    #expect(TrackIdTemplate.substitute("/{id}/art/{id}.png", id: "x") == "/x/art/x.png")
  }

  @Test func substitutesTokenInDictionaryValues() {
    let subbed = TrackIdTemplate.substitute(["x-track": "{id}", "plain": "v"], id: "abc")
    #expect(subbed == ["x-track": "abc", "plain": "v"])
  }

  @Test func valueWithoutTokenIsUnchanged() {
    #expect(TrackIdTemplate.substitute("/artwork/static.png", id: "abc") == "/artwork/static.png")
  }

  // MARK: token left literal without an id

  @Test func nilIdLeavesTokenLiteral() {
    #expect(TrackIdTemplate.substitute("/artwork/{id}", id: nil) == "/artwork/{id}")
  }

  @Test func blankIdLeavesTokenLiteral() {
    #expect(TrackIdTemplate.substitute("/artwork/{id}", id: "") == "/artwork/{id}")
  }

  @Test func nilIdLeavesDictionaryValuesLiteral() {
    #expect(TrackIdTemplate.substitute(["x-track": "{id}"], id: nil) == ["x-track": "{id}"])
  }

  @Test func nilValuePassesThrough() {
    #expect(TrackIdTemplate.substitute(String?.none, id: "abc") == nil)
    #expect(TrackIdTemplate.substitute([String: String]?.none, id: nil) == nil)
  }

  // MARK: unfilled-token detection (drives the single warning)

  @Test func detectsUnfilledTokenInString() {
    #expect(TrackIdTemplate.containsToken("/artwork/{id}"))
    #expect(!TrackIdTemplate.containsToken("/artwork/abc"))
    #expect(!TrackIdTemplate.containsToken(String?.none))
  }

  @Test func detectsUnfilledTokenInDictionaryValues() {
    #expect(TrackIdTemplate.containsToken(["x-track": "{id}"]))
    #expect(!TrackIdTemplate.containsToken(["x-track": "abc"]))
    #expect(!TrackIdTemplate.containsToken([String: String]?.none))
  }

  @Test func substitutionThenDetectionNeverBothMatch() {
    let subbed = TrackIdTemplate.substitute("/artwork/{id}", id: "abc")
    #expect(!TrackIdTemplate.containsToken(subbed))
  }
}
