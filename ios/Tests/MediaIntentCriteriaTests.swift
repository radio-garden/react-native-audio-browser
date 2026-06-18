import Testing

@testable import AudioBrowserTestable

@Suite("MediaIntentCriteria")
struct MediaIntentCriteriaTests {
  @Test func emptyEverything_isResume() {
    let c = MediaIntentCriteria(query: "", hasReference: false, hasGenres: false, hasMediaType: false)
    #expect(c.isResume)
  }

  @Test func whitespaceQuery_isResume() {
    let c = MediaIntentCriteria(query: "   ", hasReference: false, hasGenres: false, hasMediaType: false)
    #expect(c.isResume)
  }

  @Test func anyCriteria_isNotResume() {
    #expect(!MediaIntentCriteria(query: "kcrw", hasReference: false, hasGenres: false, hasMediaType: false).isResume)
    #expect(!MediaIntentCriteria(query: "", hasReference: true, hasGenres: false, hasMediaType: false).isResume)
    #expect(!MediaIntentCriteria(query: "", hasReference: false, hasGenres: true, hasMediaType: false).isResume)
    #expect(!MediaIntentCriteria(query: "", hasReference: false, hasGenres: false, hasMediaType: true).isResume)
  }
}
