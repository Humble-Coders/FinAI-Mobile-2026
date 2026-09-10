package com.humblesolutions.finai.data

import kotlinx.serialization.json.Json

/** The one JSON configuration for the Render API. */
internal val FinAiJson: Json = Json {
    // A field the server adds must never break an installed app.
    ignoreUnknownKeys = true
    // An explicit null for a non-null field falls back to its default.
    coerceInputValues = true
    explicitNulls = false
}
