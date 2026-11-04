package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.util.Money

/**
 * A bill's shares, previewed while the person chooses — worked out exactly as
 * the server works them out (backend `services/splits.py`, the same test
 * cases), so the figures shown are the figures saved and sent.
 *
 * Integer minor units throughout, never `Double`: decimal strings in and out.
 */
object SplitMath {

    /** [total] over [people]; the minor units left over go to the first ones. */
    fun equalShares(total: Long, people: Int): List<Long> {
        require(people > 0) { "a split needs someone to split with" }
        val base = total / people
        val left = total - base * people
        return List(people) { if (it < left) base + 1 else base }
    }

    /** [amount] in proportion to [weights]; the largest remainders get the left-over units, ties to the earlier. */
    internal fun inProportion(amount: Long, weights: List<Long>): List<Long> {
        val whole = weights.sum()
        if (amount == 0L) return List(weights.size) { 0L }
        if (whole <= 0L) return equalShares(amount, weights.size)
        val raw = weights.map { amount * it }
        val shares = raw.map { it / whole }.toMutableList()
        val left = amount - shares.sum()
        val order = weights.indices.sortedWith(compareBy<Int>({ -(raw[it] % whole) }, { it }))
        order.take(left.toInt()).forEach { shares[it] += 1 }
        return shares
    }

    /** Each person's share: each item shared evenly by its people, tax and tip following what each had. */
    fun itemShares(people: Int, items: List<Pair<Long, Set<Int>>>, tax: Long, tip: Long): List<Long> {
        val subtotals = MutableList(people) { 0L }
        items.forEach { (amount, who) ->
            val sharing = who.sorted()
            require(sharing.isNotEmpty()) { "an item belongs to someone" }
            equalShares(amount, sharing.size).forEachIndexed { i, share -> subtotals[sharing[i]] += share }
        }
        val extras = inProportion(tax + tip, subtotals)
        return subtotals.mapIndexed { i, s -> s + extras[i] }
    }

    /** `"12.50"` as 1250 minor units; null when it is not money. */
    fun minor(amount: String, currency: String): Long? {
        val digits = Money.fractionDigits(currency)
        val normalized = Money.normalize(amount, digits) ?: return null
        return normalized.replace(".", "").toLongOrNull()
    }

    /** 1250 minor units as `"12.50"`. */
    fun decimal(minor: Long, currency: String): String {
        val digits = Money.fractionDigits(currency)
        if (digits == 0) return minor.toString()
        val text = minor.toString().padStart(digits + 1, '0')
        return text.dropLast(digits) + "." + text.takeLast(digits)
    }
}
