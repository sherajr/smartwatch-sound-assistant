package com.peaceantz.stagescope.shared.util

import kotlinx.serialization.json.Json

/**
 * The one Json configuration every shared contract and repository uses. `ignoreUnknownKeys` keeps
 * an older build decoding a newer peer's payload (additive evolution); `encodeDefaults` makes
 * version/discriminator fields explicit on the wire; `explicitNulls = false` keeps payloads small
 * (Data Layer items and messages have tight size limits).
 */
val StageScopeJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}
