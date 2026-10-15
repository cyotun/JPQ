package com.cyotun.jpq.core

enum class Rank(val label: String, val capacity: Int) {
    BIG("大", 2), SMALL("小", 2), A("A", 8), K("K", 8), Q("Q", 8), J("J", 8),
    TEN("10", 8), NINE("9", 8), EIGHT("8", 8), SEVEN("7", 8), SIX("6", 8),
    FIVE("5", 8), FOUR("4", 8), THREE("3", 8), TWO("2", 8);
    val isJoker: Boolean get() = this == BIG || this == SMALL
}
/** The reference detector distinguishes red/black ranks, not all four suits. */
enum class Suit { SPADE, HEART, CLUB, DIAMOND, RED, BLACK, NONE }
data class Card(val rank: Rank, val suit: Suit) {
    init { require(rank.isJoker == (suit == Suit.NONE)) { "Physical suit required for ordinary cards" } }
}

fun Card.colorKey(): String = when(suit) {
    Suit.HEART, Suit.DIAMOND, Suit.RED -> "${rank.name}:RED"
    Suit.SPADE, Suit.CLUB, Suit.BLACK -> "${rank.name}:BLACK"
    Suit.NONE -> rank.name
}
enum class Seat(val label: String) { SELF("自己"), PREVIOUS("上"), OPPOSITE("对"), NEXT("下") }
enum class RoundPhase { WAITING, ACTIVE, SELF_FINISHED, RECOVERING, UNAVAILABLE, FINISHED }
enum class CountTone { ZERO, WHITE, RED, UNKNOWN }
fun countTone(rank: Rank, remaining: Int?): CountTone = when {
    remaining == null || remaining < 0 -> CountTone.UNKNOWN
    remaining == 0 -> CountTone.ZERO
    remaining >= if (rank.isJoker) 2 else 4 -> CountTone.RED
    else -> CountTone.WHITE
}

data class PlayEvent(val roundId: String, val eventId: String, val sequence: Long, val seat: Seat, val cards: List<Card>)
data class RoundSnapshot(
    val roundId: String?, val phase: RoundPhase, val remaining: Map<Rank, Int>?,
    val plays: Map<Seat, List<PlayEvent>>, val reason: String?,
) {
    fun played(seat: Seat) = plays[seat].orEmpty().sumOf { it.cards.size }
    fun history(seat: Seat) = plays[seat].orEmpty().joinToString("·") { event ->
        event.cards.joinToString("") { it.rank.label }
    }
}

val guandanOrder = Rank.entries.toList()
val landlordOrder = listOf(Rank.BIG, Rank.SMALL, Rank.TWO) + Rank.entries.filter { it !in setOf(Rank.BIG, Rank.SMALL, Rank.TWO) }
fun validOrder(order: List<Rank>) = order.size == Rank.entries.size && order.toSet().size == Rank.entries.size
