import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("ArtworkFreshness")
struct ArtworkFreshnessTests {
  private func response(status: Int = 200, _ headers: [String: String] = [:]) -> HTTPURLResponse {
    HTTPURLResponse(url: URL(string: "https://example.com/a")!, statusCode: status, httpVersion: nil, headerFields: headers)!
  }

  @Test func aResponseLivesForItsMaxAge() {
    #expect(ArtworkFreshness.lifetime(of: [response(["Cache-Control": "public, max-age=60"])]) == 60)
  }

  @Test func theShortestHopOfARedirectChainWins() {
    let redirect = response(status: 302, ["Cache-Control": "max-age=86400, stale-while-revalidate=604800"])
    let image = response(["Cache-Control": "public, max-age=31536000, immutable"])
    #expect(ArtworkFreshness.lifetime(of: [redirect, image]) == 86400)
  }

  @Test func aHopThatStatesNothingIsSkipped() {
    let redirect = response(status: 302)
    let image = response(["Cache-Control": "max-age=300"])
    #expect(ArtworkFreshness.lifetime(of: [redirect, image]) == 300)
  }

  @Test func withoutAnyStatedLifetimeTheDefaultHolds() {
    #expect(ArtworkFreshness.lifetime(of: [response(), response(["Cache-Control": "public"])]) == ArtworkFreshness.defaultLifetime)
    #expect(ArtworkFreshness.lifetime(of: []) == ArtworkFreshness.defaultLifetime)
  }

  @Test(arguments: ["no-cache", "no-store", "max-age=600, no-cache", "No-Store"])
  func noCacheOrNoStoreIsAlreadyStale(_ cacheControl: String) {
    #expect(ArtworkFreshness.lifetime(of: [response(["Cache-Control": cacheControl])]) == 0)
  }

  @Test func ageIsSubtractedFromMaxAge() {
    #expect(ArtworkFreshness.lifetime(of: [response(["Cache-Control": "max-age=600", "Age": "100"])]) == 500)
    #expect(ArtworkFreshness.lifetime(of: [response(["Cache-Control": "max-age=60", "Age": "100"])]) == 0)
  }

  @Test func directivesAreReadLooselyAsServersSendThem() {
    #expect(ArtworkFreshness.lifetime(of: [response(["cache-control": " MAX-AGE = \"120\" "])]) == 120)
  }

  @Test func anUnreadableMaxAgeStatesNothing() {
    #expect(ArtworkFreshness.lifetime(of: [response(["Cache-Control": "max-age=soon"])]) == ArtworkFreshness.defaultLifetime)
  }

  @Test(arguments: ["max-age=inf", "max-age=1e999", "max-age=-5", "max-age="])
  func onlyDigitsStateAMaxAge(_ cacheControl: String) {
    #expect(ArtworkFreshness.lifetime(of: [response(["Cache-Control": cacheControl])]) == ArtworkFreshness.defaultLifetime)
  }

  @Test func aMaxAgeBeyond2To31IsCapped() {
    #expect(ArtworkFreshness.lifetime(of: [response(["Cache-Control": "max-age=" + String(repeating: "9", count: 400)])]) == 2_147_483_648)
  }

  @Test func aBareEqualsSignIsSkipped() {
    #expect(ArtworkFreshness.lifetime(of: [response(["Cache-Control": "max-age=60,="])]) == 60)
  }

  @Test func noCacheNamingAFieldStillLetsTheResponseBeReused() {
    #expect(ArtworkFreshness.lifetime(of: [response(["Cache-Control": "max-age=600, no-cache=\"Set-Cookie\""])]) == 600)
  }
}
