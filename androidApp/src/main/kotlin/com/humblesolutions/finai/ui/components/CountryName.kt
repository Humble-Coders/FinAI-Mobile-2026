package com.humblesolutions.finai.ui.components

import java.util.Locale

/**
 * A country's name in the user's own language.
 *
 * From the platform rather than the shared string table: `Locale` already
 * translates every region code, so 240 hand-written English names would be
 * wrong for most users and a translation burden forever. Shared owns the data
 * that is the same everywhere (the dialling codes); the platform owns the
 * words.
 */
fun countryName(region: String): String =
    Locale.Builder().setRegion(region).build()
        .getDisplayCountry(Locale.getDefault())
        .ifBlank { region }
