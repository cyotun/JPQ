package com.cyotun.jpq.updates

import org.junit.Assert.*
import org.junit.Test

class UpdateRowPolicyTest {
    private fun state(available: Long=0,ready: Long=0,operation: UpdateOperation?=null,recording: Boolean=false,
                      beta: Boolean=false,current: Long=28,needsCheck: Boolean=false,progress: Int=-1)=
        UpdateRowPolicy.state(beta,current,available,ready,needsCheck,recording,operation,progress)

    @Test fun aSingleButtonMovesFromCheckingThroughDownloadToInstall() {
        assertEquals(UpdateRowAction.CHECK,state().action)
        assertEquals("检查中",state(operation=UpdateOperation.CHECKING).button)
        assertEquals(UpdateRowAction.DOWNLOAD,state(29).action)
        assertEquals("下载 42%",state(29,operation=UpdateOperation.DOWNLOADING,progress=42).button)
        assertFalse(state(29,operation=UpdateOperation.DOWNLOADING,progress=42).enabled)
        assertEquals(UpdateRowAction.INSTALL,state(29,29).action)
        assertEquals("校验中",state(29,29,UpdateOperation.INSTALLING).button)
        assertFalse(state(29,29,UpdateOperation.INSTALLING).enabled)
    }
    @Test fun betaNeverEnablesAFormalUpdateEvenWithAPreparedPackage() {
        for(op in listOf(null,*UpdateOperation.entries.toTypedArray())) {
            val row=state(29,29,op,beta=true)
            assertEquals("BETA",row.button); assertFalse(row.enabled); assertEquals(UpdateRowAction.NONE,row.action)
        }
    }
    @Test fun aRecordingRoundNeverOffersDownloadOrInstall() {
        for(ready in listOf(0L,29L)) {
            val row=state(29,ready,recording=true)
            assertEquals("记牌中",row.button); assertFalse(row.enabled); assertEquals(UpdateRowAction.NONE,row.action)
        }
    }
    @Test fun anInstalledUpdateCannotKeepTheOldInstallActionAfterProcessRestart() {
        assertEquals(UpdateRowAction.CHECK,state(29,29,current=29).action)
        assertEquals(UpdateRowAction.CHECK,state(29,29,current=30).action)
        assertEquals(UpdateRowAction.DOWNLOAD,state(30,29,current=29).action)
    }
    @Test fun aChangedOrRejectedManifestRequiresAFreshCheckBeforeAnyCachedAction() {
        assertEquals(UpdateRowAction.CHECK,state(29,29,needsCheck=true).action)
        assertEquals(UpdateRowAction.CHECK,state(29,needsCheck=true).action)
    }
    @Test fun aPausedTransferRemainsDownloadableAndUnknownProgressNeverShowsGarbage() {
        assertEquals("下载",state(29).button)
        assertEquals("下载中",state(29,operation=UpdateOperation.DOWNLOADING,progress=-1).button)
        assertEquals("下载中",state(29,operation=UpdateOperation.DOWNLOADING,progress=101).button)
        assertTrue(state(29).enabled)
    }
}
