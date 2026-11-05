package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.ArtworkVariants
import com.margelo.nitro.audiobrowser.Variant_String_ArtworkVariants
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A track's `artwork` in a browse response: a URL string, or `{ light, dark }` URLs.
 *
 * Android Auto renders only the dark variant, but both are kept: the JS side reads the same
 * payload.
 */
@Serializable(with = JsonArtworkSerializer::class)
sealed class JsonArtwork {
  data class Single(val url: String) : JsonArtwork()

  data class Variants(val light: String, val dark: String) : JsonArtwork()

  fun toNitro(): Variant_String_ArtworkVariants =
    when (this) {
      is Single -> Variant_String_ArtworkVariants.First(url)
      is Variants -> Variant_String_ArtworkVariants.Second(ArtworkVariants(light, dark))
    }
}

/**
 * Decodes by JSON shape, as the wire has no type tag: a primitive is a URL, an object must hold
 * both `light` and `dark`. Anything else throws, which fails the whole response, not just the
 * artwork.
 */
object JsonArtworkSerializer : KSerializer<JsonArtwork> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("JsonArtwork")

  override fun deserialize(decoder: Decoder): JsonArtwork {
    val input =
      decoder as? JsonDecoder
        ?: throw IllegalStateException("JsonArtwork can only be read from JSON")
    return when (val element = input.decodeJsonElement()) {
      is JsonPrimitive -> JsonArtwork.Single(element.content)
      else ->
        element.jsonObject.let {
          JsonArtwork.Variants(
            light = it.getValue("light").jsonPrimitive.content,
            dark = it.getValue("dark").jsonPrimitive.content,
          )
        }
    }
  }

  override fun serialize(encoder: Encoder, value: JsonArtwork) {
    val output =
      encoder as? JsonEncoder
        ?: throw IllegalStateException("JsonArtwork can only be written as JSON")
    output.encodeJsonElement(
      when (value) {
        is JsonArtwork.Single -> JsonPrimitive(value.url)
        is JsonArtwork.Variants ->
          JsonObject(
            mapOf("light" to JsonPrimitive(value.light), "dark" to JsonPrimitive(value.dark))
          )
      }
    )
  }
}
