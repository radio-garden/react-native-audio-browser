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
 * Dev diagnostic (ADR 0011): the declarations on a resolved page that can never render.
 *
 * The block model deliberately gave up compile-time invalid-combination errors — combinations are
 * not invalid, they are *inert*, the way `grid-template` on a non-grid element is inert in CSS.
 * This is the recovery: at page resolution every declaration its own block makes unreadable is
 * reported once, with the effective context and the fix.
 *
 * The line it draws, and never crosses: only **structural** inertness — a declaration the resolved
 * block itself renders unreadable, on every surface (`gridWrap` outside a grid, `cardTint` with no
 * card treatment in scope). *Surface* inertness is never reported, because declarations are
 * aspirational: `imageShape` on Android Auto, or a card treatment on a pre-26 CarPlay, is intended
 * usage — the renderer drops what it can't draw.
 *
 * Runs where every page passes regardless of its source (static config, route callback, or fetched
 * browse JSON), and only on a cache miss, so a mistake is reported once per resolution rather than
 * once per serve. Mirrors `ios/Browser/InertStyleDiagnostic.swift`.
 *
 * Debug builds only: [warn] is the whole seam, so a release build never even walks the page (the
 * trade is that a page served only to a release build — an internal track, production browse JSON —
 * reports nothing; reproduce it against a debug build).
 */
object InertStyleDiagnostic {
  /**
   * Reports every inert declaration on a resolved page. The one gate — the [BuildConfig] check is
   * here rather than at the call sites so callers read unconditionally and the rule set stays free
   * of build-configuration knowledge.
   */
  fun warn(path: String, pageStyle: SectionStyle?, sections: List<Section>) {
    if (!BuildConfig.DEBUG) return
    findings(path, pageStyle, sections).forEach { Timber.w(it) }
  }

  /**
   * The declarations that carry a structural inertness condition.
   *
   * Two keys of the block are deliberately absent — `display` and `artworkRendering`, dispositioned
   * below instead.
   */
  private enum class Property(val key: String, val isItemProperty: Boolean) {
    // Container properties resolve by scope override (`section ?? page`) and never reach an item;
    // item properties inherit (`track ?? section ?? page`), so they may also be declared per track.
    GRID_WRAP("gridWrap", isItemProperty = false),
    GRID_TILE("gridTile", isItemProperty = false),
    IMAGE_SHAPE("imageShape", isItemProperty = true),
    ACCESSORY_SYMBOL("accessorySymbol", isItemProperty = true),
    CARD_TINT("cardTint", isItemProperty = true),
    CARD_IMAGE("cardImage", isItemProperty = true);

    /**
     * Whether a value of this property, resolved into [context], has any rendering to affect. Each
     * branch is the spec's own "inert unless" clause.
     */
    fun isInert(context: Context): Boolean =
      when (this) {
        GRID_WRAP,
        GRID_TILE -> !context.isGrid
        IMAGE_SHAPE -> !context.isGrid || context.isCard
        ACCESSORY_SYMBOL -> context.isCard
        CARD_TINT,
        CARD_IMAGE -> !context.isCard
      }

    /** The rule the declaration falls foul of, stated positively. */
    val rule: String
      get() =
        when (this) {
          GRID_WRAP,
          GRID_TILE -> "`$key` renders only in a grid"
          IMAGE_SHAPE -> "only plain and condensed tiles have a shape"
          ACCESSORY_SYMBOL -> "card tiles have no accessory slot"
          CARD_TINT,
          CARD_IMAGE -> "the card properties render only under `gridTile: 'card'`"
        }

    val hint: String
      get() =
        when (this) {
          GRID_WRAP,
          GRID_TILE -> "Declare `display: 'grid'` on the section or the page, or drop `$key`."
          IMAGE_SHAPE -> "Render the items as plain or condensed grid tiles, or drop `imageShape`."
          ACCESSORY_SYMBOL -> "Use `gridTile: 'plain'` or `'condensed'`, or drop `accessorySymbol`."
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

    // Nitro flattens the spec's `extends`, so the two blocks share no type — hence two lookups over
    // the same key set rather than one interface.
    fun declaredIn(style: TrackStyle?): Boolean =
      when (this) {
        // Container properties have no Track slot to be declared in.
        GRID_WRAP,
        GRID_TILE -> false
        IMAGE_SHAPE -> style?.imageShape != null
        ACCESSORY_SYMBOL -> style?.accessorySymbol != null
        CARD_TINT -> style?.cardTint != null
        CARD_IMAGE -> style?.cardImage != null
      }
  }

  /**
   * The rest of the block's keys, dispositioned rather than ruled on: `artworkRendering` has no
   * structural inertness condition (artwork renders in every layout), and `display` is positional —
   * inert only in the one position that opens no page (see [playableDisplayFinding]). Declared here
   * so the spec-level completeness test can hold this file to the whole vocabulary: every key of
   * the block is a rule, always renderable, or positional, and a newly added property is a decision
   * rather than an omission.
   */
  val ALWAYS_RENDERABLE = listOf("artworkRendering")

  val POSITIONAL = listOf("display")

  /**
   * A section's effective container context: the two container values every inertness condition is
   * stated against.
   *
   * Folded through [StyleResolver] rather than re-deriving the scope override: the diagnostic
   * predicts what a renderer will do with these declarations, so it has to resolve them the way the
   * renderers do.
   */
  private class Context(section: SectionStyle?, page: SectionStyle?) {
    private val folded = StyleResolver.sectionStyle(section, page)
    val isGrid = folded.display == StyleDisplay.GRID
    val tile = folded.gridTile ?: GridTile.PLAIN

    /**
     * Whether the card treatment is what this container renders — the `card*` family's condition.
     */
    val isCard: Boolean
      get() = isGrid && tile == GridTile.CARD

    /**
     * The effective style, for the message — in the wire spelling the author types ([toWireString]
     * is the decoder's own mapping). `gridTile` is quoted only where a grid gives it meaning:
     * naming it on a list would be advice to fix the wrong property.
     */
    val described: String
      get() = if (!isGrid) "display 'list'" else "display 'grid', gridTile '${tile.toWireString()}'"
  }

  /**
   * Every inert declaration on a resolved page, as ready-to-log messages — pure, so the whole rule
   * set is testable off-device; the caller logs.
   *
   * @param path the resolved path, named in every message.
   * @param pageStyle the page's own block (`ResolvedTrack.style`).
   * @param sections the page's normalized sections, blocks *unfolded* — the diagnostic attributes
   *   each finding to the level that declared it, so it must see the levels before [StyleResolver]
   *   merges them.
   */
  fun findings(path: String, pageStyle: SectionStyle?, sections: List<Section>): List<String> {
    val findings = mutableListOf<String>()
    // A page-level declaration is judged across the whole page: it is live if it renders in any
    // section it reaches, however many others ignore it.
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
          // The section takes the page's value (nothing closer overrode it).
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

  /**
   * `display` is positional, so it is never inert *in* a container — but on a track that renders
   * playable (`src` wins the rendering) it promises the layout of a page that never opens.
   */
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

  /**
   * The one message skeleton — every finding names the page, the declaration, who declared it, why
   * it can't render, and what to do about it.
   */
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
