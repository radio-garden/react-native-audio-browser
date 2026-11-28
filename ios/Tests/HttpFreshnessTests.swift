import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("HttpFreshness")
struct HttpFreshnessTests {
  private func response(status: Int = 200, _ headers: [String: String] = [:]) -> HTTPURLResponse {
    HTTPURLResponse(url: URL(string: "https://example.com/a")!, statusCode: status, httpVersion: nil, headerFields: headers)!
  }

  @Test func aResponseLivesForItsMaxAge() {
    #expect(HttpFreshness.lifetime(of: [response(["Cache-Control": "public, max-age=60"])]) == 60)
  }

  @Test func theShortestHopOfARedirectChainWins() {
    let redirect = response(status: 302, ["Cache-Control": "max-age=86400, stale-while-revalidate=604800"])
    let image = response(["Cache-Control": "public, max-age=31536000, immutable"])
    #expect(HttpFreshness.lifetime(of: [redirect, image]) == 86400)
  }

  @Test func aHopThatStatesNothingIsSkipped() {
    let redirect = response(status: 302)
    let image = response(["Cache-Control": "max-age=300"])
    #expect(HttpFreshness.lifetime(of: [redirect, image]) == 300)
  }

  @Test func withoutAnyStatedLifetimeTheDefaultHolds() {
    #expect(HttpFreshness.lifetime(of: [response(), response(["Cache-Control": "public"])]) == HttpFreshness.defaultLifetime)
    #expect(HttpFreshness.lifetime(of: []) == HttpFreshness.defaultLifetime)
  }

  @Test(arguments: ["no-cache", "no-store", "max-age=600, no-cache", "No-Store"])
  func noCacheOrNoStoreIsAlreadyStale(_ cacheControl: String) {
    #expect(HttpFreshness.lifetime(of: [response(["Cache-Control": cacheControl])]) == 0)
  }

  @Test func ageIsSubtractedFromMaxAge() {
    #expect(HttpFreshness.lifetime(of: [response(["Cache-Control": "max-age=600", "Age": "100"])]) == 500)
    #expect(HttpFreshness.lifetime(of: [response(["Cache-Control": "max-age=60", "Age": "100"])]) == 0)
  }

  @Test func directivesAreReadLooselyAsServersSendThem() {
    #expect(HttpFreshness.lifetime(of: [response(["cache-control": " MAX-AGE = \"120\" "])]) == 120)
  }

  @Test(arguments: ["max-age=soon", "max-age=inf", "max-age=1e999", "max-age=-5", "max-age="])
  func anInvalidMaxAgeStatesNothing(_ cacheControl: String) {
    #expect(HttpFreshness(response(["Cache-Control": cacheControl])) == .unstated)
    #expect(HttpFreshness.lifetime(of: [response(["Cache-Control": cacheControl])]) == HttpFreshness.defaultLifetime)
  }

  @Test(arguments: ["max-age=soon, max-age=60", "max-age=60, max-age=soon", "max-age=60, max-age=600"])
  func theFirstValidOfDuplicateMaxAgesWins(_ cacheControl: String) {
    #expect(HttpFreshness(response(["Cache-Control": cacheControl])) == .maxAge(60, age: 0))
  }

  @Test func aMaxAgeBeyond2To31IsCapped() {
    #expect(HttpFreshness.lifetime(of: [response(["Cache-Control": "max-age=" + String(repeating: "9", count: 400)])]) == 2_147_483_648)
  }

  @Test func ofRepeatedAgeLinesTheFirstCounts() {
    #expect(HttpFreshness(response(["Cache-Control": "max-age=600", "Age": "10, 20"])) == .maxAge(600, age: 10))
  }

  @Test func eachFreshnessIsNamed() {
    #expect(HttpFreshness(response()) == .unstated)
    #expect(HttpFreshness(response(["Cache-Control": "public"])) == .unstated)
    #expect(HttpFreshness(response(["Cache-Control": "max-age=0"])) == .revalidate)
    #expect(HttpFreshness(response(["Cache-Control": "no-store"])) == .revalidate)
    #expect(HttpFreshness(response(["Cache-Control": "max-age=60", "Age": "100"])) == .maxAge(60, age: 100))
  }

  @Test func aBareEqualsSignIsSkipped() {
    #expect(HttpFreshness.lifetime(of: [response(["Cache-Control": "max-age=60,="])]) == 60)
  }

  @Test func noCacheNamingAFieldStillLetsTheResponseBeReused() {
    #expect(HttpFreshness.lifetime(of: [response(["Cache-Control": "max-age=600, no-cache=\"Set-Cookie\""])]) == 600)
  }
}
