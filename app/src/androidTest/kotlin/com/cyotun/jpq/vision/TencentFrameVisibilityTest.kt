package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cyotun.jpq.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TencentFrameVisibilityTest {
    @Test fun passSourcesRequireFreshPositionedUnmaskedInputsAndNoCurrentCards() {
        val text=SceneText(listOf(OcrLine("不出",Rect(460,160,540,205))),900L,1000,500,7)
        fun read(t: SceneText?=text,now: Long=1000L,epoch: Long=7L,scene: TableScene=TableScene.TABLE,
            groups: Map<Seat,List<PlayGlyph>> = empty,masks: List<GlyphBox> = emptyList()) =
            TencentFrameVisibility.observedPasses(t,now,1000,500,epoch,scene,groups,masks)
        assertEquals(mapOf(Seat.OPPOSITE to 900L),read())
        assertEquals(read(),read(now=1100L)) // Same cached source, never a later vote.
        assertTrue(read(now=1901L).isEmpty()); assertTrue(read(now=899L).isEmpty())
        assertTrue(read(epoch=8).isEmpty()); assertTrue(read(t=text.copy(width=999)).isEmpty())
        assertTrue(read(scene=TableScene.TEAMMATE).isEmpty())
        assertTrue(read(groups=empty+(Seat.OPPOSITE to listOf(handGlyph))).isEmpty())
        val mask=GlyphBox(.7f,.2f,.75f,.3f) // Hidden possible group tail, outside the word.
        assertTrue(read(masks=listOf(mask)).isEmpty())
        assertTrue(read(t=text.copy(sourceMasks=listOf(mask))).isEmpty())
        assertTrue(read(masks=listOf(GlyphBox(Float.NaN,0f,.2f,.3f))).isEmpty())
        assertTrue(read(t=text.copy(lines=listOf(OcrLine("不出",null)))).isEmpty())
    }
    private val card=Card(Rank.FIVE,Suit.RED)
    private val handGlyph=PlayGlyph(card,.95f,GlyphBox(.20f,.65f,.23f,.75f))
    private val empty=Seat.entries.associateWith { emptyList<PlayGlyph>() }

    private fun adapt(masks: List<GlyphBox>,groups: Map<Seat,List<PlayGlyph>> = empty,
        hand: List<PlayGlyph> = listOf(handGlyph),scene: TableScene = TableScene.TABLE): Pair<TableFrame,Map<Seat,EmptyEvidence>> {
        val bitmap=Bitmap.createBitmap(1280,588,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(9,108,97))
            val evidence=EmptyRegionGuard().evaluate(bitmap,scene,hand.size,groups,"fixture",masks)
            // Deliberately start all plays as observed: the adapter must still
            // enforce the guard's negative evidence before anything reaches core.
            val plays=groups.mapValues { (_,glyphs) -> ObservedPlay(glyphs.map { it.card },.95f,glyphs) }
            val count=mapOf(Seat.PREVIOUS to RemainingHandCount(27,900L))
            val frame=TableFrame(1000L,scene,hand.map { it.card },plays,
                teammateHand=hand.map { it.card },teammateHandConfidence=.95f,remainingHandCounts=count)
            return TencentFrameVisibility.qualify(frame,hand,evidence,masks) to evidence
        } finally { bitmap.recycle() }
    }

    @Test fun realRoundMaskCrossingPoint13PreservesOwnHandAndVisibleSelfWhileOutsideSeatsAbstain() {
        // Frame 1705 in live-0.2.19-20261016-round1/failure-geometry.txt.
        val mask=GlyphBox(.204f,.0183f,.6296f,.1333f)
        val self=PlayGlyph(card,.95f,GlyphBox(.44f,.39f,.47f,.46f))
        val (frame,evidence)=adapt(listOf(mask),empty+(Seat.SELF to listOf(self)))
        assertEquals(TableScene.TABLE,frame.scene)
        assertEquals(listOf(card),frame.hand)
        assertTrue(frame.plays.getValue(Seat.SELF)!!.regionObserved)
        assertEquals(listOf(card),frame.plays.getValue(Seat.SELF)!!.cards)
        for(seat in listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT)) {
            assertEquals("overlay_occlusion",evidence.getValue(seat).reason)
            assertFalse(frame.plays.getValue(seat)!!.regionObserved)
        }
        assertEquals(RemainingHandCount(27,900L),frame.remainingHandCounts[Seat.PREVIOUS])
        assertEquals("悬浮条遮住出牌区，请移回顶部，正在重新核对",frame.incompleteReason)
        assertEquals(setOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT),frame.localizedOverlaySeats)
    }

    @Test fun hiddenSideTailCannotVoteOrCommitButVisibleOppositeAndCountsSurvive() {
        val side=PlayGlyph(card,.95f,GlyphBox(.19f,.38f,.22f,.46f))
        val opposite=PlayGlyph(Card(Rank.SIX,Suit.BLACK),.95f,GlyphBox(.46f,.18f,.49f,.25f))
        // Neither glyph overlaps this mask. A possible long side group still does.
        val (frame,_)=adapt(listOf(GlyphBox(.82f,.40f,.90f,.46f)),
            empty+(Seat.PREVIOUS to listOf(side))+(Seat.OPPOSITE to listOf(opposite)))
        assertFalse(frame.plays.getValue(Seat.PREVIOUS)!!.regionObserved)
        assertTrue(frame.plays.getValue(Seat.OPPOSITE)!!.regionObserved)
        assertEquals(listOf(opposite.card),frame.plays.getValue(Seat.OPPOSITE)!!.cards)
        assertNotNull(frame.hand)
        assertEquals(1,frame.remainingHandCounts.size)
        val ledger=RoundLedger()
        val baseline=Rank.entries.filter { !it.isJoker }.flatMap {
            listOf(Card(it,Suit.RED),Card(it,Suit.BLACK))
        }+Card(Rank.SMALL,Suit.NONE)
        ledger.begin("fixture",baseline)
        val observer=RoundObserver(ledger)
        repeat(8) { observer.observe(frame.copy(timeMs=1000L+it*100L,hand=baseline)) }
        assertEquals(0,observer.diagnosticStatus().playVotes.getValue(Seat.PREVIOUS))
        assertEquals(0,ledger.snapshot().played(Seat.PREVIOUS))
    }

    @Test fun aMaskOnlyInHandAreaSuppressesBothCompleteHandClaimsWithoutDiscardingTableEvidence() {
        val opposite=PlayGlyph(card,.95f,GlyphBox(.46f,.18f,.49f,.25f))
        val (frame,evidence)=adapt(listOf(GlyphBox(.60f,.78f,.75f,.88f)),empty+(Seat.OPPOSITE to listOf(opposite)))
        assertNull(frame.hand)
        assertNull(frame.teammateHand)
        assertTrue(frame.plays.getValue(Seat.OPPOSITE)!!.regionObserved)
        assertTrue(evidence.values.all { it.observed })
        assertEquals("悬浮条遮住手牌区，请移回顶部，正在重新核对",frame.incompleteReason)
        assertEquals(1,frame.remainingHandCounts.size)
        assertTrue(frame.localizedOverlaySeats.isEmpty())
    }

    @Test fun globalFaultsNeverAcquireTheLocalOverlayException() {
        val masks=listOf(GlyphBox(.82f,.40f,.90f,.46f))
        val evidence=Seat.entries.associateWith { seat ->
            if(seat==Seat.NEXT) EmptyEvidence(false,"overlay_occlusion") else EmptyEvidence(true,"detected_glyphs") }
        val plays=Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }
        for(change in listOf<(TableFrame)->TableFrame>(
            { it.copy(incompleteReason="adjustment") },{ it.copy(ambiguousPresentation=true) },{ it.copy(captureGap=true) }
        )) {
            val input=change(TableFrame(1000,TableScene.TABLE,listOf(card),plays))
            val output=TencentFrameVisibility.qualify(input,listOf(handGlyph),evidence,masks)
            assertTrue(output.localizedOverlaySeats.isEmpty())
            assertFalse(output.plays.getValue(Seat.NEXT)!!.regionObserved)
        }
    }

    @Test fun raisedHandGlyphFootprintAboveDefaultHandRegionStillPreventsCompleteHand() {
        val raised=handGlyph.copy(box=GlyphBox(.20f,.50f,.23f,.60f))
        assertFalse(TencentFrameVisibility.handObserved(listOf(raised),listOf(GlyphBox(.20f,.51f,.22f,.54f))))
        assertTrue(TencentFrameVisibility.handObserved(listOf(raised),listOf(GlyphBox(.02f,.01f,.19f,.12f))))
    }

    private fun choices(time: Long=800L,masks: List<GlyphBox> = emptyList())=SceneText(listOf(
        OcrLine("不加倍",Rect(420,210,510,250)),
        OcrLine("超级加倍x4",Rect(710,210,850,250))),time,1280,588,3L,sourceMasks=masks)
    private fun source(text: SceneText?,time: Long=1000L,masks: List<GlyphBox> = emptyList())=
        TencentFrameVisibility.openingChoiceSourceTimeMs(text,time,1280,588,3L,masks)

    private fun earlyFinishText(time: Long=800L,rank: String="二游",box: Rect?=Rect(76,108,120,125),
        masks: List<GlyphBox> = emptyList())=SceneText(listOf(OcrLine(rank,box)),time,1000,500,7,sourceMasks=masks)

    private fun earlyFinishSource(text: SceneText?=earlyFinishText(),time: Long=1000L,
        scene: TableScene=TableScene.TABLE,masks: List<GlyphBox> = emptyList())=
        TencentFrameVisibility.observedEarlyFinishes(text,time,1000,500,7,scene,masks)

    @Test fun earlyFinishSourcesNeedExactPositionedFirstOrSecondRankInFreshUnmaskedCapture() {
        val text=earlyFinishText()
        assertEquals(mapOf(Seat.PREVIOUS to 800L),earlyFinishSource())
        assertEquals(mapOf(Seat.PREVIOUS to 800L),earlyFinishSource(text,time=1500L,scene=TableScene.TEAMMATE))
        assertTrue(earlyFinishSource(text,time=1801L).isEmpty())
        assertTrue(earlyFinishSource(text,time=799L).isEmpty())
        assertTrue(earlyFinishSource(text.copy(width=999)).isEmpty())
        assertTrue(earlyFinishSource(text.copy(visibilityEpoch=8)).isEmpty())
        assertTrue(earlyFinishSource(text.copy(lines=listOf(OcrLine("二游头像",Rect(76,108,120,125))))).isEmpty())
        assertTrue(earlyFinishSource(earlyFinishText(rank="三游")).isEmpty())
        assertTrue(earlyFinishSource(earlyFinishText(rank="末游")).isEmpty())
        assertTrue(earlyFinishSource(earlyFinishText(box=null)).isEmpty())
        assertTrue(earlyFinishSource(text,scene=TableScene.RESULT).isEmpty())
        val badgeMask=GlyphBox(.07f,.21f,.13f,.26f)
        assertTrue(earlyFinishSource(text,masks=listOf(badgeMask)).isEmpty())
        assertTrue(earlyFinishSource(text.copy(sourceMasks=listOf(badgeMask))).isEmpty())
        assertTrue(earlyFinishSource(text.copy(sourceMasks=listOf(GlyphBox(Float.NaN,0f,.2f,.3f)))).isEmpty())
    }

    @Test fun lastPlaceSourceRecognizesTheCentralSelfSlotAndRejectsStaleMaskedOrAmbiguousHints() {
        val text=SceneText(listOf(OcrLine("末游",Rect(1229,808,1376,886))),900L,2608,1200,7)
        fun read(t: SceneText?=text,now: Long=1000L,epoch: Long=7L,scene: TableScene=TableScene.RESULT,
            masks: List<GlyphBox> = emptyList()) =
            TencentFrameVisibility.observedLastPlaceSeat(t,now,2608,1200,epoch,scene,masks)
        assertEquals(Seat.SELF,read())
        assertEquals(Seat.SELF,read(scene=TableScene.TABLE))
        assertNull(read(now=1901L)); assertNull(read(now=899L)); assertNull(read(epoch=8L))
        assertNull(read(t=text.copy(width=2607)))
        assertNull(read(masks=listOf(GlyphBox(.47f,.67f,.53f,.74f))))
        assertNull(read(t=text.copy(sourceMasks=listOf(GlyphBox(.47f,.67f,.53f,.74f)))))
        val conflict=text.copy(lines=text.lines+OcrLine("末游",Rect(1071,83,1201,133)))
        assertNull(read(t=conflict))
    }

    @Test fun teammateAvatarFinishBadgeIsFreshOppositeMetadata() {
        val text=SceneText(listOf(OcrLine("二游",Rect(1071,83,1201,133))),900L,2608,1200,7)
        assertEquals(mapOf(Seat.OPPOSITE to 900L),TencentFrameVisibility.observedEarlyFinishes(
            text,1000L,2608,1200,7,TableScene.TABLE,emptyList()))
    }

    @Test fun sourceIdentityRemainsOriginalCaptureTimeAcrossRepeatedCachedOcrDelivery() {
        val text=choices()
        assertEquals(800L,source(text))
        assertEquals(800L,source(text,1100L))
        assertNull(source(text,1801L))
        assertNull(source(text,799L))
        assertNull(source(text.copy(visibilityEpoch=4L)))
        assertNull(source(text.copy(width=2560)))
    }

    @Test fun openingProofRejectsAvatarLabelsSingleChoiceAndEitherSourceOrCurrentOcclusion() {
        val mask=GlyphBox(.32f,.35f,.41f,.44f)
        assertNull(source(choices(masks=listOf(mask))))
        assertNull(source(choices(),masks=listOf(mask)))
        assertNull(source(choices().copy(lines=choices().lines.take(1))))
        assertNull(source(choices().copy(lines=listOf(
            OcrLine("加倍x2",Rect(90,130,180,155)),OcrLine("不加倍",Rect(1100,130,1200,155))))))
        assertNull(source(choices().copy(lines=listOf(
            OcrLine("加倍x2",Rect(420,100,510,125)),OcrLine("不加倍",Rect(710,100,850,125))))))
        assertEquals(800L,source(choices(),masks=listOf(GlyphBox(.01f,.01f,.20f,.12f))))
    }

    @Test fun unknownMaskGeometryCannotProveACompleteHandOrOpeningChoiceSource() {
        for(mask in listOf(GlyphBox(Float.NaN,0f,.5f,.1f),GlyphBox(.5f,0f,.1f,.1f),GlyphBox(-.1f,0f,.5f,.1f))) {
            assertFalse(TencentFrameVisibility.handObserved(listOf(handGlyph),listOf(mask)))
            assertNull(source(choices(masks=listOf(mask))))
            assertNull(source(choices(),masks=listOf(mask)))
        }
    }
}
