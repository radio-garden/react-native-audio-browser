/*
 Feature Lab — replaces the sample's Apple-Music-backed TemplateManager with a
 static playground exercising every CarPlay API under evaluation for
 react-native-audio-browser (see the audit report). No network, no tokens.

 Root = tab bar: "Lab" (feature list) + "Badge" (shows a tab badge; also the
 target of the programmatic tab-select demo).
 */

import CarPlay
import UIKit

class TemplateManager: NSObject {

    private var interfaceController: CPInterfaceController?
    private var tabBar: CPTabBarTemplate?
    private var badgeTemplate: CPListTemplate?
    private var sessionConfiguration: CPSessionConfiguration?

    // Toggleable demo state
    private var gridShapeCircular = true
    private var cardsFullHeight = false

    // MARK: - Connection

    func connect(_ interfaceController: CPInterfaceController) {
        self.interfaceController = interfaceController
        sessionConfiguration = CPSessionConfiguration(delegate: self)

        let lab = CPListTemplate(title: "Lab", sections: [labRootSection()])
        lab.tabTitle = "Lab"
        lab.tabImage = UIImage(systemName: "flask")

        let badge = CPListTemplate(title: "Badge", sections: [
            CPListSection(items: [infoItem("This tab shows a badge dot", detail: "showsTabBadge = true")]),
        ])
        badge.tabTitle = "Badge"
        badgeTemplate = badge
        badge.tabImage = UIImage(systemName: "bell")
        badge.showsTabBadge = true

        let tabBar = CPTabBarTemplate(templates: [lab, badge])
        self.tabBar = tabBar
        interfaceController.setRootTemplate(tabBar, animated: true, completion: nil)
    }

    func disconnect() {
        interfaceController = nil
        tabBar = nil
        sessionConfiguration = nil
    }

    // MARK: - Lab root

    private func labRootSection() -> CPListSection {
        var items: [CPListItem] = []

        func demo(_ title: String, _ detail: String?, minimum: Double = 14.0,
                  build: @escaping () -> CPTemplate?) {
            let available = ProcessInfo.processInfo.isOperatingSystemAtLeast(
                OperatingSystemVersion(majorVersion: Int(minimum), minorVersion: Int((minimum * 10).truncatingRemainder(dividingBy: 10)), patchVersion: 0))
            let item = CPListItem(text: title, detailText: available ? detail : "requires iOS \(minimum)")
            item.handler = { [weak self] _, completion in
                if available, let template = build() {
                    self?.interfaceController?.pushTemplate(template, animated: true, completion: nil)
                }
                completion()
            }
            if #available(iOS 15.0, *) { item.isEnabled = available }
            items.append(item)
        }

        demo("List row knobs", "progress · explicit · accessories · disabled") { [weak self] in self?.rowKnobsTemplate() }
        demo("Single-line row elements", "single-line · subtitles", minimum: 26.0) { [weak self] in
            if #available(iOS 26.0, *) { return self?.rowElementsTemplate() } else { return nil }
        }
        demo("Wrapping image grid", "shapes · accessory symbols", minimum: 26.0) { [weak self] in
            if #available(iOS 26.0, *) { return self?.imageGridTemplate() } else { return nil }
        }
        demo("Cards", "tint · full-height images", minimum: 26.0) { [weak self] in
            if #available(iOS 26.0, *) { return self?.cardsTemplate() } else { return nil }
        }
        demo("Condensed elements", "condensed cells", minimum: 26.0) { [weak self] in
            if #available(iOS 26.0, *) { return self?.condensedTemplate() } else { return nil }
        }
        demo("Enhanced section header", "subtitle · image · CPButton", minimum: 15.0) { [weak self] in
            if #available(iOS 15.0, *) { return self?.enhancedHeaderTemplate() } else { return nil }
        }
        demo("Header grid buttons", "Play / Shuffle strip", minimum: 26.0) { [weak self] in
            if #available(iOS 26.0, *) { return self?.headerGridButtonsTemplate() } else { return nil }
        }
        demo("Section index A–Z", "fast scrubber") { [weak self] in self?.sectionIndexTemplate() }
        demo("Assistant cell", "position · visibility", minimum: 15.0) { [weak self] in
            if #available(iOS 15.0, *) { return self?.assistantCellTemplate() } else { return nil }
        }
        demo("Nav-bar buttons", "leading / trailing CPBarButton") { [weak self] in self?.navBarButtonsTemplate() }
        demo("Now Playing buttons", "upNextTitle · more · add-to-library") { [weak self] in self?.nowPlayingDemo() }
        demo("Sports mode", "two-team scoreboard", minimum: 18.4) { [weak self] in
            if #available(iOS 18.4, *) { return self?.sportsModeDemo() } else { return nil }
        }

        // Programmatic tab selection (iOS 17)
        let select = CPListItem(text: "Select the Badge tab in 1.5 s", detailText: "selectTemplate(at:)")
        select.handler = { [weak self] _, completion in
            if #available(iOS 17.0, *) {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                    guard let tabBar = self?.tabBar, tabBar.templates.count > 1 else { return }
                    tabBar.selectTemplate(at: 1)
                }
            }
            completion()
        }
        items.append(select)

        // Badge experiment: does mutating showsTabBadge in place redraw the
        // tab, or does it need a tab-bar refresh? (The header documents no
        // dynamic update, unlike headerGridButtons.)
        let toggleBadge = CPListItem(text: "Toggle Badge tab's badge (mutate in place)", detailText: "watch the Badge tab's dot")
        toggleBadge.handler = { [weak self] _, completion in
            if let badge = self?.badgeTemplate {
                badge.showsTabBadge.toggle()
                MemoryLogger.shared.appendEvent("showsTabBadge is now \(badge.showsTabBadge)")
            }
            completion()
        }
        items.append(toggleBadge)

        let refreshTabs = CPListItem(text: "Force tab refresh (updateTemplates)", detailText: "the guaranteed badge-apply path")
        refreshTabs.handler = { [weak self] _, completion in
            if let tabBar = self?.tabBar {
                tabBar.updateTemplates(tabBar.templates)
                MemoryLogger.shared.appendEvent("updateTemplates re-applied")
            }
            completion()
        }
        items.append(refreshTabs)

        demo("Session info", "limited UI · content style") { [weak self] in self?.sessionInfoTemplate() }

        return CPListSection(items: items, header: "Feature demos", sectionIndexTitle: nil)
    }

    // MARK: - 1. List row knobs

    private func rowKnobsTemplate() -> CPTemplate {
        let progress = CPListItem(text: "playbackProgress = 0.62", detailText: "progress alone — renders?", image: art("P", .systemIndigo))
        progress.playbackProgress = 0.62

        let playingProgress = CPListItem(text: "progress + isPlaying", detailText: "bar may be part of the playing treatment", image: art("P2", .systemIndigo))
        playingProgress.playbackProgress = 0.62
        playingProgress.isPlaying = true

        let trailingProgress = CPListItem(text: "progress + isPlaying + trailing indicator", detailText: "indicator moved out of the artwork slot", image: art("P3", .systemIndigo))
        trailingProgress.playbackProgress = 0.62
        trailingProgress.isPlaying = true
        trailingProgress.playingIndicatorLocation = .trailing

        let explicit = CPListItem(text: "isExplicitContent", detailText: "explicit badge trails the text", image: art("E", .systemPink))
        explicit.isExplicitContent = true

        let cloud = CPListItem(text: "accessoryType = .cloud", detailText: "trailing cloud glyph", image: art("C", .systemTeal))
        cloud.accessoryType = .cloud

        let accessory = CPListItem(text: "accessoryImage (heart)", detailText: "custom trailing image", image: art("A", .systemRed))
        accessory.setAccessoryImage(UIImage(systemName: "heart.fill"))

        let disabled = CPListItem(text: "isEnabled = false", detailText: "grayed out, dead to taps", image: art("D", .systemGray))
        if #available(iOS 15.0, *) { disabled.isEnabled = false }

        let both = CPListItem(text: "progress + explicit together", detailText: nil, image: art("B", .systemOrange))
        both.playbackProgress = 0.31
        both.isExplicitContent = true

        return CPListTemplate(title: "Row knobs", sections: [CPListSection(items: [progress, playingProgress, trailingProgress, explicit, cloud, accessory, disabled, both])])
    }

    // MARK: - 2. Single-line row elements

    @available(iOS 26.0, *)
    private func rowElementsTemplate() -> CPTemplate {
        let elements = (1...8).map { i in
            CPListImageRowItemRowElement(image: art("\(i)", palette[i % palette.count]),
                                         title: "Station \(i)",
                                         subtitle: i % 2 == 0 ? "Subtitle \(i)" : nil)
        }
        let row = CPListImageRowItem(text: "Single line (allowsMultipleLines: false)", elements: elements, allowsMultipleLines: false)
        row.listImageRowHandler = { _, index, completion in
            MemoryLogger.shared.appendEvent("Row element tapped: \(index)")
            completion()
        }
        let titleless = CPListImageRowItem(text: nil, elements: elements, allowsMultipleLines: false)
        return CPListTemplate(title: "Row elements", sections: [
            CPListSection(items: [row]),
            CPListSection(items: [titleless], header: "text: nil — enhanced-header candidate", sectionIndexTitle: nil),
        ])
    }

    // MARK: - 3. Wrapping image grid

    @available(iOS 26.0, *)
    private func imageGridTemplate() -> CPTemplate {
        let template = CPListTemplate(title: "Image grid", sections: imageGridSections())
        return template
    }

    @available(iOS 26.0, *)
    private func imageGridSections() -> [CPListSection] {
        let shape: CPListImageRowItemImageGridElement.Shape = gridShapeCircular ? .circular : .roundedRectangle
        let elements = (1...12).map { i in
            CPListImageRowItemImageGridElement(image: art("\(i)", palette[i % palette.count]),
                                               imageShape: shape,
                                               title: "Tile \(i)",
                                               accessorySymbolName: i == 3 ? "speaker.wave.2.fill" : nil)
        }
        let grid = CPListImageRowItem(text: "Wrapping grid", imageGridElements: elements, allowsMultipleLines: true)
        grid.listImageRowHandler = { _, index, completion in
            MemoryLogger.shared.appendEvent("Grid tile tapped: \(index)")
            completion()
        }

        let toggle = CPListItem(text: "Toggle shape", detailText: gridShapeCircular ? "circular → rounded-rectangle" : "rounded-rectangle → circular")
        toggle.handler = { [weak self] _, completion in
            guard let self else { completion(); return }
            self.gridShapeCircular.toggle()
            (self.interfaceController?.topTemplate as? CPListTemplate)?.updateSections(self.imageGridSections())
            completion()
        }
        return [CPListSection(items: [toggle], header: "Settings", sectionIndexTitle: nil), CPListSection(items: [grid])]
    }

    // MARK: - 4. Cards

    @available(iOS 26.0, *)
    private func cardsTemplate() -> CPTemplate {
        CPListTemplate(title: "Cards", sections: cardsSections())
    }

    @available(iOS 26.0, *)
    private func cardsSections() -> [CPListSection] {
        let tints: [UIColor] = [.systemIndigo, .systemOrange, .systemGreen]
        let elements = (0..<3).map { i in
            CPListImageRowItemCardElement(image: art("Card \(i + 1)", palette[i]),
                                          showsImageFullHeight: cardsFullHeight,
                                          title: "Featured \(i + 1)",
                                          subtitle: "Card subtitle",
                                          tintColor: tints[i])
        }
        let row = CPListImageRowItem(text: "Featured", cardElements: elements, allowsMultipleLines: false)
        row.listImageRowHandler = { _, index, completion in
            MemoryLogger.shared.appendEvent("Card tapped: \(index)")
            completion()
        }

        let toggle = CPListItem(text: "Toggle showsImageFullHeight", detailText: cardsFullHeight ? "on — image fills card, tint = background" : "off — tint = gradient behind text")
        toggle.handler = { [weak self] _, completion in
            guard let self else { completion(); return }
            self.cardsFullHeight.toggle()
            (self.interfaceController?.topTemplate as? CPListTemplate)?.updateSections(self.cardsSections())
            completion()
        }
        return [CPListSection(items: [toggle], header: "Settings", sectionIndexTitle: nil), CPListSection(items: [row])]
    }

    // MARK: - 5. Condensed elements

    @available(iOS 26.0, *)
    private func condensedTemplate() -> CPTemplate {
        let elements = (1...6).map { i in
            CPListImageRowItemCondensedElement(image: art("\(i)", palette[i % palette.count]),
                                               imageShape: .roundedRectangle,
                                               title: "Recent \(i)",
                                               subtitle: "Condensed cells",
                                               accessorySymbolName: i == 1 ? "clock" : nil)
        }
        let row = CPListImageRowItem(text: "Recently played", condensedElements: elements, allowsMultipleLines: true)
        row.listImageRowHandler = { _, index, completion in
            MemoryLogger.shared.appendEvent("Condensed element tapped: \(index)")
            completion()
        }
        return CPListTemplate(title: "Condensed", sections: [CPListSection(items: [row])])
    }

    // MARK: - 6. Enhanced section header

    @available(iOS 15.0, *)
    private func enhancedHeaderTemplate() -> CPTemplate {
        let button = CPButton(image: UIImage(systemName: "play.fill")!) { _ in
            MemoryLogger.shared.appendEvent("Header button tapped")
        }
        button.title = "Play"  // does the list header render this label?

        let section = CPListSection(items: (1...4).map { infoItem("Row \($0)", detail: nil) },
                                    header: "Popular",
                                    headerSubtitle: "Updated hourly",
                                    headerImage: art("H", .systemPurple),
                                    headerButton: button,
                                    sectionIndexTitle: nil)
        let plain = CPListSection(items: [infoItem("Plain-header section for contrast", detail: nil)],
                                  header: "Plain",
                                  sectionIndexTitle: nil)
        return CPListTemplate(title: "Enhanced header", sections: [section, plain])
    }

    // MARK: - 7. Header grid buttons

    @available(iOS 26.0, *)
    private func headerGridButtonsTemplate() -> CPTemplate {
        let template = CPListTemplate(title: "Header buttons", sections: [
            CPListSection(items: (1...5).map { infoItem("Track \($0)", detail: nil) }),
        ])
        template.headerGridButtons = [
            CPGridButton(titleVariants: ["Play"], image: UIImage(systemName: "play.fill")!) { _ in
                MemoryLogger.shared.appendEvent("Header Play tapped")
            },
            CPGridButton(titleVariants: ["Shuffle"], image: UIImage(systemName: "shuffle")!) { _ in
                MemoryLogger.shared.appendEvent("Header Shuffle tapped")
            },
        ]
        return template
    }

    // MARK: - 8. Section index

    private func sectionIndexTemplate() -> CPTemplate {
        let letters = (0..<20).map { String(UnicodeScalar(UInt8(65 + $0))) }  // A…T
        let sections = letters.map { letter in
            CPListSection(items: [infoItem("\(letter) item", detail: nil)], header: letter, sectionIndexTitle: letter)
        }
        return CPListTemplate(title: "A–Z", sections: sections)
    }

    // MARK: - 9. Assistant cell

    @available(iOS 15.0, *)
    private func assistantCellTemplate() -> CPTemplate {
        let template = CPListTemplate(
            title: "Assistant cell",
            sections: [CPListSection(items: [
                infoItem("Cell should render above this row", detail: "position .top, visibility .always"),
                infoItem("Note: Siri entitlement was stripped", detail: "cell may not render in this build"),
            ])],
            assistantCellConfiguration: CPAssistantCellConfiguration(position: .top, visibility: .always, assistantAction: .playMedia))
        return template
    }

    // MARK: - 10. Nav-bar buttons

    private func navBarButtonsTemplate() -> CPTemplate {
        let template = CPListTemplate(title: "Nav-bar buttons", sections: [
            CPListSection(items: [infoItem("Pushed template — buttons live in the nav bar", detail: nil)]),
        ])
        let shuffle = CPBarButton(title: "Shuffle") { _ in
            MemoryLogger.shared.appendEvent("Trailing bar button tapped")
        }
        let icon = CPBarButton(image: UIImage(systemName: "heart")!) { _ in
            MemoryLogger.shared.appendEvent("Leading bar button tapped")
        }
        template.trailingNavigationBarButtons = [shuffle]
        template.leadingNavigationBarButtons = [icon]
        return template
    }

    // MARK: - 11. Now Playing

    private func nowPlayingDemo() -> CPTemplate? {
        let nowPlaying = CPNowPlayingTemplate.shared
        nowPlaying.isUpNextButtonEnabled = true
        nowPlaying.upNextTitle = "Queue"

        var buttons: [CPNowPlayingButton] = [
            CPNowPlayingImageButton(image: UIImage(systemName: "heart")!) { _ in
                MemoryLogger.shared.appendEvent("Heart tapped")
            },
            CPNowPlayingAddToLibraryButton { _ in
                MemoryLogger.shared.appendEvent("Add-to-library tapped")
            },
        ]
        buttons.append(CPNowPlayingMoreButton { [weak self] _ in
            let actions = ["Start Station", "Report Problem"].map { title in
                CPAlertAction(title: title, style: .default) { _ in
                    MemoryLogger.shared.appendEvent("Action: \(title)")
                    self?.interfaceController?.dismissTemplate(animated: true, completion: nil)
                }
            }
            let cancel = CPAlertAction(title: "Cancel", style: .cancel) { _ in
                self?.interfaceController?.dismissTemplate(animated: true, completion: nil)
            }
            let sheet = CPActionSheetTemplate(title: "More", message: nil, actions: actions + [cancel])
            self?.interfaceController?.presentTemplate(sheet, animated: true, completion: nil)
        })
        nowPlaying.updateNowPlayingButtons(buttons)
        return nowPlaying
    }

    // MARK: - 12. Sports mode

    @available(iOS 18.4, *)
    private func sportsModeDemo() -> CPTemplate? {
        let left = CPNowPlayingSportsTeam(name: "Porto",
                                          logo: CPNowPlayingSportsTeamLogo(teamInitials: "POR"),
                                          teamStandings: "1st",
                                          eventScore: "2",
                                          possessionIndicator: nil,
                                          favorite: true)
        let right = CPNowPlayingSportsTeam(name: "Ajax",
                                           logo: CPNowPlayingSportsTeamLogo(teamInitials: "AJX"),
                                           teamStandings: "3rd",
                                           eventScore: "1",
                                           possessionIndicator: nil,
                                           favorite: false)
        let status = CPNowPlayingSportsEventStatus(eventStatusText: ["78’"], eventStatusImage: nil, eventClock: nil)
        CPNowPlayingTemplate.shared.nowPlayingMode = CPNowPlayingModeSports(leftTeam: left,
                                                                            rightTeam: right,
                                                                            eventStatus: status,
                                                                            backgroundArtwork: art("", .systemIndigo, size: 480))
        return CPNowPlayingTemplate.shared
    }

    // MARK: - 13. Session info

    private func sessionInfoTemplate() -> CPTemplate {
        CPListTemplate(title: "Session", sections: [CPListSection(items: sessionInfoItems())])
    }

    private func sessionInfoItems() -> [CPListItem] {
        let limited = sessionConfiguration?.limitedUserInterfaces ?? []
        var rows = [
            infoItem("limitedUserInterfaces", detail: limited.isEmpty ? "none" : "\(limited.contains(.keyboard) ? "keyboard " : "")\(limited.contains(.lists) ? "lists" : "")"),
        ]
        if #available(iOS 13.0, *) {
            let style = sessionConfiguration?.contentStyle
            rows.append(infoItem("contentStyle", detail: style == .dark ? "dark" : style == .light ? "light" : "unspecified"))
        }
        return rows
    }

    // MARK: - Helpers

    private let palette: [UIColor] = [.systemIndigo, .systemOrange, .systemGreen, .systemPink, .systemTeal, .systemPurple, .systemRed, .systemBlue]

    private func infoItem(_ text: String, detail: String?) -> CPListItem {
        let item = CPListItem(text: text, detailText: detail)
        item.handler = { _, completion in completion() }
        return item
    }

    /// A solid-color square with centered label — display-ready artwork without any network.
    private func art(_ label: String, _ color: UIColor, size: CGFloat = 180) -> UIImage {
        let rect = CGRect(x: 0, y: 0, width: size, height: size)
        return UIGraphicsImageRenderer(size: rect.size).image { context in
            color.setFill()
            context.fill(rect)
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.boldSystemFont(ofSize: size * 0.3),
                .foregroundColor: UIColor.white,
            ]
            let text = NSString(string: label)
            let textSize = text.size(withAttributes: attributes)
            text.draw(at: CGPoint(x: (size - textSize.width) / 2, y: (size - textSize.height) / 2),
                      withAttributes: attributes)
        }
    }
}

// MARK: - CPSessionConfigurationDelegate

extension TemplateManager: CPSessionConfigurationDelegate {
    func sessionConfiguration(_ sessionConfiguration: CPSessionConfiguration,
                              limitedUserInterfacesChanged limitedUserInterfaces: CPLimitableUserInterface) {
        MemoryLogger.shared.appendEvent("Limited UI changed: \(limitedUserInterfaces)")
        (interfaceController?.topTemplate as? CPListTemplate)?.updateSections([CPListSection(items: sessionInfoItems())])
    }
}
