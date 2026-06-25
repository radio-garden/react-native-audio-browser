import Testing

@testable import AudioBrowserTestable

/// `CastStateCoalescer` suppresses duplicate `(state, deviceName)` emits and lets
/// any genuine change (in state OR device name) through.
@Suite("CastStateCoalescer")
struct CastStateCoalescerTests {
  @Test func firstEmitAlwaysPasses() {
    var coalescer = CastStateCoalescer()
    #expect(coalescer.shouldEmit(state: .noDevices, deviceName: nil))
  }

  @Test func identicalPairIsSuppressed() {
    var coalescer = CastStateCoalescer()
    #expect(coalescer.shouldEmit(state: .connected, deviceName: "Living Room"))
    #expect(!coalescer.shouldEmit(state: .connected, deviceName: "Living Room"))
    #expect(!coalescer.shouldEmit(state: .connected, deviceName: "Living Room"))
  }

  @Test func stateChangeEmits() {
    var coalescer = CastStateCoalescer()
    #expect(coalescer.shouldEmit(state: .connecting, deviceName: "Kitchen"))
    #expect(coalescer.shouldEmit(state: .connected, deviceName: "Kitchen"))
  }

  @Test func deviceNameChangeWithSameStateEmits() {
    var coalescer = CastStateCoalescer()
    #expect(coalescer.shouldEmit(state: .connected, deviceName: "Kitchen"))
    // Same state, different device — must still emit.
    #expect(coalescer.shouldEmit(state: .connected, deviceName: "Bedroom"))
  }

  @Test func deviceNameToNilWithSameStateEmits() {
    var coalescer = CastStateCoalescer()
    #expect(coalescer.shouldEmit(state: .notConnected, deviceName: "Kitchen"))
    #expect(coalescer.shouldEmit(state: .notConnected, deviceName: nil))
  }

  @Test func resetReallowsAnIdenticalEmit() {
    var coalescer = CastStateCoalescer()
    #expect(coalescer.shouldEmit(state: .connected, deviceName: "Living Room"))
    #expect(!coalescer.shouldEmit(state: .connected, deviceName: "Living Room"))
    coalescer.reset()
    #expect(coalescer.shouldEmit(state: .connected, deviceName: "Living Room"))
  }
}
