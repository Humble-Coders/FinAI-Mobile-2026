package com.humblesolutions.finai.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Decodes an enum through its own `fromWire`, falling back instead of failing.
 *
 * kotlinx.serialization rejects an enum value it does not know, which would turn
 * a value the server adds next month into a decoding failure in every installed
 * app. Each enum owns its wire mapping; this routes decoding through it.
 */
internal abstract class WireEnumSerializer<E : Enum<E>>(
    serialName: String,
    private val fromWire: (String?) -> E,
    private val toWire: (E) -> String,
) : KSerializer<E> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): E = fromWire(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(toWire(value))
}
