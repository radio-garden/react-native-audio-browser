/// The image to show for a light and dark pair as its halves arrive: the first alone, then both.
struct ArtworkPair<Image> {
  private var light: Image?
  private var dark: Image?
  private let combine: (_ light: Image, _ dark: Image) -> Image

  init(combine: @escaping (_ light: Image, _ dark: Image) -> Image) {
    self.combine = combine
  }

  mutating func receive(light: Image) -> Image {
    self.light = light
    return dark.map { combine(light, $0) } ?? light
  }

  mutating func receive(dark: Image) -> Image {
    self.dark = dark
    return light.map { combine($0, dark) } ?? dark
  }
}
