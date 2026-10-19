package com.cyotun.jpq

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import com.cyotun.jpq.core.CaptureScope

/** Uses only public API 37 methods, discovered at runtime to keep the stable SDK/toolchain unchanged. */
internal object ProjectionConsentRequest {
    const val SCOPE_EXTRA="jpq.capture.scope"
    val scope get()=if(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE) CaptureScope.APP_TASK else CaptureScope.DISPLAY

    fun create(manager: MediaProjectionManager): Intent {
        if(scope==CaptureScope.DISPLAY) return if(Build.VERSION.SDK_INT>=34)
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
        return if(Build.VERSION.SDK_INT>=37) manager.createScreenCaptureIntent(appOnlyConfig())
            else error("本轮单应用候选版需要 Android 17，请继续使用稳定版")
    }

    internal fun appOnlyConfig(): MediaProjectionConfig {
        if(Build.VERSION.SDK_INT<37) error("本轮单应用候选版需要 Android 17，请继续使用稳定版")
        // Public reflection avoids an unrelated AGP/Gradle migration. Missing vendor
        // APIs fail closed; never downgrade an app-only request into whole-screen capture.
        val configClass=MediaProjectionConfig::class.java
        val builderClass=Class.forName("android.media.projection.MediaProjectionConfig\$Builder")
        val builder=builderClass.getConstructor().newInstance()
        val setSource=builderClass.getMethod("setSourceEnabled",Integer.TYPE,java.lang.Boolean.TYPE)
        for((field,enabled) in listOf("PROJECTION_SOURCE_APP" to true,"PROJECTION_SOURCE_DISPLAY" to false,
            "PROJECTION_SOURCE_APP_CONTENT" to false)) {
            setSource.invoke(builder,configClass.getField(field).getInt(null),enabled)
        }
        builderClass.getMethod("setInitiallySelectedSource",Integer.TYPE)
            .invoke(builder,configClass.getField("PROJECTION_SOURCE_APP").getInt(null))
        return builderClass.getMethod("build").invoke(builder) as MediaProjectionConfig
    }
}
