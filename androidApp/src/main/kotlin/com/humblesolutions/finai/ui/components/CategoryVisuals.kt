package com.humblesolutions.finai.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.humblesolutions.finai.usecase.CategoryIcon

/** The shared icon names as Material's pictures; SF Symbols' on iOS. */
internal fun CategoryIcon.vector(): ImageVector = when (this) {
    CategoryIcon.HOME -> Icons.Filled.Home
    CategoryIcon.TRANSFER -> Icons.Filled.SwapHoriz
    CategoryIcon.DOCUMENT -> Icons.AutoMirrored.Filled.ReceiptLong
    CategoryIcon.CAR -> Icons.Filled.DirectionsCar
    CategoryIcon.BAG -> Icons.Filled.ShoppingBag
    CategoryIcon.BOLT -> Icons.Filled.Bolt
    CategoryIcon.DINING -> Icons.Filled.Restaurant
    CategoryIcon.CART -> Icons.Filled.LocalGroceryStore
    CategoryIcon.HEART -> Icons.Filled.LocalHospital
    CategoryIcon.SALARY -> Icons.Filled.Payments
    CategoryIcon.SHIELD -> Icons.Filled.Security
    CategoryIcon.SCHOOL -> Icons.Filled.School
    CategoryIcon.TICKET -> Icons.Filled.TheaterComedy
    CategoryIcon.GIFT -> Icons.Filled.CardGiftcard
    CategoryIcon.SPA -> Icons.Filled.Spa
    CategoryIcon.PIGGY -> Icons.Filled.Savings
    CategoryIcon.REPEAT -> Icons.Filled.Subscriptions
    CategoryIcon.PLANE -> Icons.Filled.Flight
    CategoryIcon.CARD -> Icons.Filled.CreditCard
    CategoryIcon.PHONE -> Icons.Filled.PhoneAndroid
    CategoryIcon.TAG -> Icons.AutoMirrored.Filled.Label
    CategoryIcon.UNFILED -> Icons.AutoMirrored.Filled.HelpOutline
}

/**
 * A colour per kind of spending, for the tile behind a category's icon: the
 * home, the table, getting about, looking after yourself, money coming in,
 * going out, and paperwork. Kinds share a colour so the list stays calm, and
 * an unfiled row is amber — the one that needs somebody. Each pair keeps the
 * icon at 3:1 or better on its own tile, in either theme.
 */
internal fun CategoryIcon.tint(dark: Boolean): Color {
    val (light, night) = when (this) {
        CategoryIcon.HOME, CategoryIcon.BOLT, CategoryIcon.PHONE, CategoryIcon.REPEAT ->
            Color(0xFF2563EB) to Color(0xFF60A5FA)

        CategoryIcon.DINING, CategoryIcon.CART, CategoryIcon.BAG ->
            Color(0xFFEA580C) to Color(0xFFFB923C)

        CategoryIcon.CAR, CategoryIcon.PLANE, CategoryIcon.TRANSFER ->
            Color(0xFF0D9488) to Color(0xFF2DD4BF)

        CategoryIcon.HEART, CategoryIcon.SPA, CategoryIcon.SHIELD ->
            Color(0xFFDB2777) to Color(0xFFF472B6)

        CategoryIcon.SALARY, CategoryIcon.PIGGY ->
            Color(0xFF15803D) to Color(0xFF4ADE80)

        CategoryIcon.TICKET, CategoryIcon.GIFT, CategoryIcon.SCHOOL ->
            Color(0xFF7C3AED) to Color(0xFFA78BFA)

        CategoryIcon.DOCUMENT, CategoryIcon.CARD, CategoryIcon.TAG ->
            Color(0xFF475569) to Color(0xFF94A3B8)

        CategoryIcon.UNFILED ->
            Color(0xFFB45309) to Color(0xFFFBBF24)
    }
    return if (dark) night else light
}
