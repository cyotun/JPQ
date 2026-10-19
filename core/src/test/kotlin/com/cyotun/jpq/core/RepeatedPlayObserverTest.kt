package com.cyotun.jpq.core

import kotlin.test.*

class RepeatedPlayObserverTest {
    private val deck=Rank.entries.flatMap { rank -> if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
        else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } } }
    private val initial=deck.filter { it.rank!=Rank.A }.take(27)
    private val ace=Card(Rank.A,Suit.BLACK)
    private val glyph=PlayGlyph(ace,.96f,GlyphBox(.475f,.162f,.495f,.207f))
    private val play=ObservedPlay(listOf(ace),.96f,listOf(glyph))
    private class Fixture(val ledger: RoundLedger,val observer: RoundObserver)
    private fun fixture(): Fixture {
        val outside=deck.toMutableList().apply { initial.forEach { remove(it) } }
        val ledger=RoundLedger().apply {
            begin("repeated-a",initial)
            commit(PlayEvent("repeated-a","opposite-24",1,Seat.OPPOSITE,outside.filter { it.rank!=Rank.A }.take(24)))
        }
        val observer=RoundObserver(ledger)
        val fixture=Fixture(ledger,observer)
        listOf(1000L,1120L,1240L,1360L).forEach { tick(fixture,it) }
        assertEquals(25,ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(7,ledger.snapshot().remaining!![Rank.A])
        return fixture
    }
    private fun tick(f: Fixture,time: Long,count: Int?=null,source: Long=time,observed: Boolean=true) =
        f.observer.observe(TableFrame(time,TableScene.TABLE,initial,
            mapOf(Seat.OPPOSITE to play.copy(regionObserved=observed)),
            remainingHandCounts=if(count==null) emptyMap() else mapOf(Seat.OPPOSITE to RemainingHandCount(count,source))))
    private fun anchor(f: Fixture) {
        tick(f,1480,2,1480); tick(f,1580,2,1480); tick(f,1680,2,1480); tick(f,1780,2,1730)
    }
    @Test fun twoIndependentBadgesProveASecondBlackAceWithoutAnEmptyTable() {
        val f=fixture(); anchor(f)
        tick(f,1940,1,1930); tick(f,2040,1,1930); tick(f,2140,1,1930)
        assertEquals(25,f.ledger.snapshot().played(Seat.OPPOSITE))
        tick(f,2240,1,2180)
        assertEquals(26,f.ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(6,f.ledger.snapshot().remaining!![Rank.A])
        repeat(5) { tick(f,2340+it*100L,1,2280+it*100L) }
        assertEquals(26,f.ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(3,f.ledger.snapshot().plays.getValue(Seat.OPPOSITE).size)
    }
    @Test fun theRepeatedRankOrOneCachedCountCannotCreateAnotherEvent() {
        val f=fixture(); anchor(f)
        repeat(6) { tick(f,1940+it*100L,1,1930) }
        assertEquals(25,f.ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(7,f.ledger.snapshot().remaining!![Rank.A])
    }
    @Test fun anObscuredPlayBreaksTheIndependentRepeatedPlayProof() {
        val f=fixture(); anchor(f)
        tick(f,1940,1,1930)
        tick(f,2040,1,1930,observed=false)
        repeat(5) { tick(f,2140+it*100L,1,2130+it*100L) }
        assertEquals(25,f.ledger.snapshot().played(Seat.OPPOSITE))
    }
    private fun physicalTick(f: Fixture,time: Long,suit: Suit)=f.observer.observe(TableFrame(time,TableScene.TABLE,initial,
        mapOf(Seat.OPPOSITE to play.copy(singleCardIdentity=SingleCardIdentity(Card(Rank.A,suit),.86f,.15f,time)))))
    @Test fun changingFromClubToSpadeIsNotSuppressedByTheSameBlackAceKey() {
        val f=fixture()
        for(time in listOf(1630L,1896L,2162L)) physicalTick(f,time,Suit.CLUB)
        for(time in listOf(2428L,2694L)) { physicalTick(f,time,Suit.SPADE); assertEquals(25,f.ledger.snapshot().played(Seat.OPPOSITE)) }
        physicalTick(f,2960,Suit.SPADE)
        assertEquals(26,f.ledger.snapshot().played(Seat.OPPOSITE))
        repeat(6) { physicalTick(f,3226+it*266L,Suit.SPADE) }
        assertEquals(26,f.ledger.snapshot().played(Seat.OPPOSITE))
    }
}
