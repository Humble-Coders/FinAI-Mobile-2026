package com.humblesolutions.finai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.humblesolutions.finai.i18n.LocalizationRegistry

/**
 * Resolves a shared string key for Compose.
 *
 * Platform code must not contain user-facing string literals — every label
 * comes from sharedLogic/i18n so Android and iOS cannot diverge
 * (kmp-arch-v2, CLAUDE.md).
 */
@Composable
@ReadOnlyComposable
fun strings(key: String): String = LocalizationRegistry.get(key)
