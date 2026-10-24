package com.cyotun.jpq.updates

internal enum class UpdateRowAction { CHECK, DOWNLOAD, INSTALL, NONE }
internal enum class UpdateOperation { CHECKING, DOWNLOADING, INSTALLING }
internal data class UpdateRowState(val button: String,val action: UpdateRowAction,val enabled: Boolean)

/** Display-only cache: actions still verify the signed release and APK off the UI thread. */
internal object UpdateRowPolicy {
    fun state(beta: Boolean,currentVersion: Long,availableVersion: Long,readyVersion: Long,
              needsCheck: Boolean,recording: Boolean,operation: UpdateOperation?,progress: Int): UpdateRowState = when {
        beta -> UpdateRowState("BETA",UpdateRowAction.NONE,false)
        recording -> UpdateRowState("记牌中",UpdateRowAction.NONE,false)
        operation==UpdateOperation.INSTALLING -> UpdateRowState("校验中",UpdateRowAction.NONE,false)
        operation==UpdateOperation.DOWNLOADING -> UpdateRowState(if(progress in 0..100) "下载 $progress%" else "下载中",UpdateRowAction.NONE,false)
        operation==UpdateOperation.CHECKING -> UpdateRowState("检查中",UpdateRowAction.NONE,false)
        needsCheck -> UpdateRowState("检查更新",UpdateRowAction.CHECK,true)
        availableVersion>currentVersion && readyVersion==availableVersion -> UpdateRowState("安装",UpdateRowAction.INSTALL,true)
        availableVersion>currentVersion -> UpdateRowState("下载",UpdateRowAction.DOWNLOAD,true)
        else -> UpdateRowState("检查更新",UpdateRowAction.CHECK,true)
    }
}
