import Foundation

/// How `ArtworkImageFetcher` turns fetched bytes into the image it returns. Only a URL whose path
/// ends in `.svg`, in any case, is an SVG: a `data:` URL or an extensionless one is not.
enum ArtworkProcessing: Equatable {
  case svg(onCanvas: Bool)
  case downsampledOntoCanvas
  case asFetched

  init(url: URL, onCanvas: Bool) {
    self = if url.pathExtension.lowercased() == "svg" {
      .svg(onCanvas: onCanvas)
    } else if onCanvas {
      .downsampledOntoCanvas
    } else {
      .asFetched
    }
  }
}
