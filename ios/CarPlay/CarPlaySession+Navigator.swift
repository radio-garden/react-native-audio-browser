import CarPlay
import NitroModules

extension CarPlaySession {
  /// A row or tile was tapped. CarPlay is released at once, so the list never locks: a browse
  /// pushes its destination and fills it in, and playback owns its loading state on Now Playing.
  func select(_ track: Track, completion: @escaping () -> Void) {
    logger.info("Selected track: \(track.title)")
    // A tap queued before `end()`: the next run owns the screen.
    guard !ended else {
      completion()
      return
    }

    if track.src != nil, let identity = track.identity, player?.currentTrack?.identity == identity {
      try? audioBrowser.play()
      nowPlaying.showNowPlaying()
      completion()
      return
    }

    // A playable track always lands on Now Playing, so it is pushed now with the tapped
    // track's fields: the queue expansion and media resolve may both hit the network.
    if track.src != nil {
      audioBrowser.engine?.loadNowPlayingMetadata(for: track)
      nowPlaying.prepare(for: track)
      nowPlaying.showNowPlaying()
    }
    completion()

    spawn { session in
      guard let player = session.player else { return }
      let result = await session.trackSelector.select(track: track, player: player)
      guard !Task.isCancelled else {
        if track.src != nil { session.restoreNowPlaying() }
        return
      }
      switch result {
      case let .play(intent):
        session.player?.execute(intent)
        session.nowPlaying.showNowPlaying(popToFront: false)
      case .intercepted:
        session.nowPlaying.showNowPlaying(popToFront: false)
      case let .browse(path):
        await session.navigate(to: path, title: track.title)
      case .none:
        break
      }
    }
  }

  /// Puts the current track back on the now-playing surface, over a tapped one that won't play.
  private func restoreNowPlaying() {
    guard let engine = audioBrowser.engine else { return }
    if let current = player?.currentTrack {
      engine.loadNowPlayingMetadata(for: current)
      engine.nowPlayingUpdater.render()
    } else {
      engine.clearNowPlaying()
    }
  }

  /// Pushes `path`'s page as an empty, spinning template that fills in once it appears; or the
  /// Browse Gate's page when the gate gates it; or selects the tab that is that path.
  func navigate(to path: String, title: String) async {
    // A push lands asynchronously, so a double tap is caught by `navigatingPaths` until the
    // pushed template appears.
    guard interfaceController.topTemplate?.carPlayInfo?.path != path, !navigatingPaths.contains(path) else { return }
    navigatingPaths.insert(path)
    var pushing = false
    defer { if !pushing { navigatingPaths.remove(path) } }

    let chrome = audioBrowser.browseGate.isActive ? await gate(.browse, path: path) : nil
    guard !Task.isCancelled else { return }
    let template: CPTemplate
    if let chrome {
      template = makeGatePage(chrome, path: path, tab: nil)
    } else if selectTab(owning: path) {
      return
    } else {
      template = CPListTemplate.loading(title: title, path: path, loadingTitle: config.carPlayLoadingTitle)
    }

    pushing = true
    interfaceController.safePush(template, animated: true) { [weak self] pushed, error in
      guard !pushed else { return }
      // It will never appear to clear the guard (e.g. at the depth limit).
      self?.navigatingPaths.remove(path)
      if let error { self?.logger.error("pushTemplate failed for \(path): \(error.localizedDescription)") }
    }
  }

  /// Switches to the tab whose path this is, popping any pushed stack first, instead of pushing
  /// a second copy of the tab into another tab's stack. False when no tab has the path, when
  /// the root is not a tab bar, or before iOS 17, which cannot select a tab.
  private func selectTab(owning path: String) -> Bool {
    guard #available(iOS 17.0, *),
          let tabBar = interfaceController.rootTemplate as? CPTabBarTemplate,
          let tab = tabBar.templates.first(where: { $0.contentPath == path })
    else { return false }
    let isPushed = interfaceController.templates.count > 1
    if tabBar.selectedTemplate === tab, !isPushed { return true }
    guard isPushed else {
      tabBar.select(tab)
      return true
    }
    interfaceController.safePopToRoot(animated: true) { popped, _ in
      // After a failed pop the selection would land under the pushed stack.
      guard popped else { return }
      tabBar.select(tab)
    }
    return true
  }
}
