package com.audiobrowser.browser

import com.audiobrowser.BuildConfig
import com.audiobrowser.util.toWireString
import com.margelo.nitro.audiobrowser.GridTile
import com.margelo.nitro.audiobrowser.Section
import com.margelo.nitro.audiobrowser.SectionStyle
import com.margelo.nitro.audiobrowser.StyleDisplay
import com.margelo.nitro.audiobrowser.TrackStyle
import timber.log.Timber

/**
 * Reports the style declarations on a resolved page that can never render on any surface (ADR
 * 0011). Only structural inertness: a surface dropping a property it can't draw is intended usage,
 * not a finding. Mirrors `ios/Browser/InertStyleDiagnostic.swift`.
 */
object InertStyleDiagnostic {
  /**
   * Logs every finding; a no-op in release builds, so a page only a release build serves reports
   * nothing until reproduced against a debug build.
   */
  fun warn(path: String, pageStyle: SectionStyle?, sections: List<Section>) {
    if (!BuildConfig.DEBUG) return
    findings(path, pageStyle, sections).forEach { Timber.w(it) }
  }

  /**
   * The keys with an inertness rule. `src/style-resolution.test.ts` reads the entry keys: every key
   * of the spec's style block is here, in [ALWAYS_RENDERABLE] or in [POSITIONAL].
   */
  private enum class Property(val key: String, val isItemProperty: Boolean) {
    // Item properties inherit `track ?? section ?? page`, so tracks may declare them too; container
    // properties resolve `section ?? page` and never reach an item.
    GRID_WRAP("gridWrap", isItemProperty = false),
    GRID_TILE("gridTile", isItemProperty = false),
    IMAGE_SHAPE("imageShape", isItemProperty = true),
    ACCESSORY_SYMBOL("accessorySymbol", isItemProperty = true),
    CARD_TINT("cardTint", isItemProperty = true),
    CARD_IMAGE("cardImage", isItemProperty = true);

    fun isInert(context: Context): Boolean =
      when (this) {
        GRID_WRAP,
        GRID_TILE -> !context.isGrid
        IMAGE_SHAPE -> !context.isShaped
        ACCESSORY_SYMBOL -> context.isGrid && !context.isShaped
        CARD_TINT,
        CARD_IMAGE -> !context.isCard
      }

    val rule: String
      get() =
        when (this) {
          GRID_WRAP,
          GRID_TILE -> "`$key` renders only in a grid"
          IMAGE_SHAPE -> "only image and condensed tiles have a shape"
          ACCESSORY_SYMBOL ->
            "among grid tiles only image and condensed ones have an accessory slot"
          CARD_TINT,
          CARD_IMAGE -> "the card properties render only under `gridTile: 'card'`"
        }

    val hint: String
      get() =
        when (this) {
          GRID_WRAP,
          GRID_TILE -> "Declare `display: 'grid'` on the section or the page, or drop `$key`."
          IMAGE_SHAPE -> "Use `gridTile: 'image'` or `'condensed'` in a grid, or drop `imageShape`."
          ACCESSORY_SYMBOL ->
            "Use `gridTile: 'image'` or `'condensed'`, or a list, or drop `accessorySymbol`."
          CARD_TINT,
          CARD_IMAGE -> "Declare `display: 'grid'` with `gridTile: 'card'`, or drop `$key`."
        }

    fun declaredIn(style: SectionStyle?): Boolean =
      when (this) {
        GRID_WRAP -> style?.gridWrap != null
        GRID_TILE -> style?.gridTile != null
        IMAGE_SHAPE -> style?.imageShape != null
        ACCESSORY_SYMBOL -> style?.accessorySymbol != null
        CARD_TINT -> style?.cardTint != null
        CARD_IMAGE -> style?.cardImage != null
      }

    // Nitro flattens the spec's `extends`, so SectionStyle and TrackStyle share no type to look up.
    fun declaredIn(style: TrackStyle?): Boolean =
      when (this) {
        GRID_WRAP,
        GRID_TILE -> false
        IMAGE_SHAPE -> style?.imageShape != null
        ACCESSORY_SYMBOL -> style?.accessorySymbol != null
        CARD_TINT -> style?.cardTint != null
        CARD_IMAGE -> style?.cardImage != null
      }
  }

  // Unused in code: `src/style-resolution.test.ts` reads these lists to check that every key of
  // the block is dispositioned. `artworkRendering` renders in every layout; `display` is inert only
  // on a playable track (see [playableDisplayFinding]).
  val ALWAYS_RENDERABLE = listOf("artworkRendering")

  val POSITIONAL = listOf("display")

  // Folds through StyleResolver so it predicts what the renderers resolve.
  private class Context(section: SectionStyle?, page: SectionStyle?) {
    private val folded = StyleResolver.sectionStyle(section, page)
    val isGrid = folded.display == StyleDisplay.GRID
    val tile = folded.gridTile ?: GridTile.PLAIN

    // Exhaustive, like isShaped, so a new tile kind has to be ruled on here.
    val isCard: Boolean
      get() =
        isGrid &&
          when (tile) {
            GridTile.CARD -> true
            GridTile.PLAIN,
            GridTile.IMAGE,
            GridTile.CONDENSED -> false
          }

    val isShaped: Boolean
      get() =
        isGrid &&
          when (tile) {
            GridTile.IMAGE,
            GridTile.CONDENSED -> true
            GridTile.PLAIN,
            GridTile.CARD -> false
          }

    // gridTile is named only in a grid: on a list it would point at the wrong property to fix.
    val described: String
      get() = if (!isGrid) "display 'list'" else "display 'grid', gridTile '${tile.toWireString()}'"
  }

  /**
   * Every inert declaration on a resolved page, as log-ready messages.
   *
   * @param pageStyle the page's own block (`ResolvedTrack.style`).
   * @param sections the page's sections with their blocks unfolded: each finding names the level
   *   that declared it.
   */
  fun findings(path: String, pageStyle: SectionStyle?, sections: List<Section>): List<String> {
    val findings = mutableListOf<String>()
    // A page declaration is live if it renders in any section that inherits it.
    val pageReached = mutableSetOf<Property>()
    val pageRendered = mutableSetOf<Property>()

    sections.forEachIndexed { index, section ->
      val context = Context(section.style, pageStyle)
      val sectionLabel = label(section, index)

      for (property in Property.entries) {
        val inert = property.isInert(context)

        if (property.declaredIn(section.style)) {
          if (inert) {
            findings +=
              message(property, path, sectionLabel, "its effective style is ${context.described}")
          }
        } else if (property.declaredIn(pageStyle)) {
          pageReached += property
          if (!inert) pageRendered += property
        }

        if (!property.isItemProperty || !inert) continue
        val tracks = section.children.count { property.declaredIn(it.style) }
        if (tracks > 0) {
          findings +=
            message(
              property,
              path,
              "${trackCount(tracks)} in $sectionLabel",
              "the section's effective style is ${context.described}",
            )
        }
      }

      playableDisplayFinding(section, path, sectionLabel)?.let { findings += it }
    }

    for (property in Property.entries) {
      if (property in pageReached && property !in pageRendered) {
        findings +=
          message(property, path, "the page block", "no section that inherits it renders one")
      }
    }

    return findings
  }

  private fun playableDisplayFinding(section: Section, path: String, label: String): String? {
    val tracks = section.children.count { it.src != null && it.style?.display != null }
    if (tracks == 0) return null
    return message(
      path = path,
      owner = "${trackCount(tracks)} in $label",
      key = "display",
      reason =
        "`display` is the layout promise for the page a track opens, and a track rendered playable " +
          "opens none",
      hint = "Drop `display`, or give the track a `path` if it should be browsable.",
    )
  }

  private fun message(property: Property, path: String, owner: String, scope: String): String =
    message(
      path = path,
      owner = owner,
      key = property.key,
      reason = "${property.rule}, but $scope",
      hint = property.hint,
    )

  private fun message(
    path: String,
    owner: String,
    key: String,
    reason: String,
    hint: String,
  ): String =
    "Inert style declaration on page '$path': `style.$key` on $owner never renders — $reason. $hint"

  private fun trackCount(count: Int): String = "$count ${if (count == 1) "track" else "tracks"}"

  private fun label(section: Section, index: Int): String =
    section.title?.takeIf { it.isNotEmpty() }?.let { "section '$it'" } ?: "section #${index + 1}"
}
