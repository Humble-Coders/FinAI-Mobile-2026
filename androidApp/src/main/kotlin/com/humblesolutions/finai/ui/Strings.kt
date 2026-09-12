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

/**
 * The same, filling `{0}`-style placeholders.
 *
 * Substitution lives in the shared registry, not here, so both platforms render
 * one translator's template the same way.
 */
@Composable
@ReadOnlyComposable
fun strings(key: String, vararg args: String): String =
    LocalizationRegistry.format(key, args.toList())
