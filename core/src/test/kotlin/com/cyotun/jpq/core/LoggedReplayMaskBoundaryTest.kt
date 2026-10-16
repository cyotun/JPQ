package com.cyotun.jpq.core

import java.nio.file.Files
import kotlin.test.*

class LoggedReplayMaskBoundaryTest {
    @Test fun recordedOverlayOcclusionCannotBecomeCompleteMerelyBecauseAGlyphWasDetected() {
        val baseline=Rank.entries.filterNot { it.isJoker }.flatMap {
            listOf(Card(it,Suit.RED),Card(it,Suit.BLACK))
        }+Card(Rank.SMALL,Suit.NONE)
        val hand=baseline.mapIndexed { i,card ->
            "${card.rank},${card.suit},0.99,${.12+i*.026},0.70,${.14+i*.026},0.75"
        }.joinToString("|")
        for((observed,metadata,expected) in listOf(
            Triple(false,"",0), Triple(true,"",1),
            Triple(false,";false;",1), // Verified empty source mask set, rather than missing metadata.
            Triple(false,";false;0.3,0.3,0.4,0.4",0), // Hidden tail beyond the directly visible glyph.
            Triple(true,";false;0.7,0.3,0.8,0.4",0),
            Triple(false,";false;?",0),
        )) {
            val folder=Files.createTempDirectory("jpq-logged-mask-")
            val tape=folder.resolve("source.tape"); val output=folder.resolve("report.json")
            try {
                val rows=mutableListOf("0;LOBBY;false;true,true,true,true;true;false;;;false")
                for(t in 60..180 step 60) rows.add("$t;TABLE;false;true,true,true,true;true;false;$hand;;false")
                val cards="$hand|THREE,RED,0.99,0.75,0.30,0.77,0.35"
                for(t in 300..480 step 60) rows.add("$t;TABLE;false;true,true,true,$observed;true;false;$cards;;false$metadata")
                Files.writeString(tape,rows.joinToString("\n"))
                replayLoggedTape(arrayOf(tape.toString(),output.toString()))
                val report=Files.readString(output)
                val next=Regex("\"next\":(\\d+)").findAll(report).map { it.groupValues[1].toInt() }.maxOrNull()
                assertEquals(expected,next)
                assertTrue(report.contains("\"baselineCount\":1"))
            } finally {
                Files.deleteIfExists(output); Files.deleteIfExists(tape); Files.deleteIfExists(folder)
            }
        }
    }
}
