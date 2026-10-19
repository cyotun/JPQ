# Reference APK capture and overlay occlusion review

Date: 2026-10-18. Read-only analysis of user-provided reference APK, SHA-256 `4ff7e67673f293efd8f6c5b5bbe6bdb3bf12e0bcaa447871ee4f084f40216161` (rehash matched existing research). Existing JADX output is `research-private/jadx/sources`. No app/device execution, production/test/tools changes or build was performed. The companion raw DEX check remains local and Git-ignored; only this analysis is included in the source archive.

## Decision supported by the evidence

No reference implementation was found that obtains the complete original game pixels beneath an opaque visible floating bar. The examined normal capture route uses ordinary MediaProjection + ImageReader, ordinary application overlay windows, and bitmap preprocessing. There is no identified capture-timed hide/show, FLAG_SECURE, manufacturer capture-exclusion flag, SurfaceControl layer exclusion, or second underlay capture in this route. Therefore the user's condition for copying a superior reference capture mechanism is not met; no extra production change is justified by this audit.

This is a static code conclusion, not a claim that both APKs were experimentally tested on the same phone. Actual compositor/OEM behavior and native implementation details are not independently established here. Removing our own bitmap mask alone would expose whatever pixels MediaProjection already delivered, not prove those are the hidden game pixels.

## Reference normal capture: exact chain

Paths below are relative to the workspace root; method and line numbers refer to the existing JADX output.

- `research-private/jadx/sources/com/aijpq/pokerrecorder/service/ScreenCapturePermissionIntentFactory.java:50-82`: Android34+ requests default-display MediaProjection except six-player configuration; otherwise ordinary user-choice capture intent. Four-player mode is not a separate raw game-surface capture route.
- `.../service/NormalCaptureStrategy.java:193-199`: `start` creates `ScreenCaptureHelper` and calls `startCapture`.
- `.../service/ScreenCaptureHelper.java:473`: `MediaProjection.createVirtualDisplay` with flags16 (AUTO_MIRROR), surface from its ImageReader. `:985`/`:1152` create an ImageReader with pixel format1 and buffer count3. Temporary screenshot virtual display `:1223` also uses flags16.
- `.../service/ScreenCaptureHelper.java:521-549`: acquire latest Image, convert it, call the named floating-mask method, copy the bitmap via Canvas, dispatch the frame, collect diagnostic bitmap.
- `.../service/ScreenCaptureHelper.java:421-425`: conversion passes the captured Image to `ModelEvalYuvConverter.m5044a`.
- `research-private/jadx/sources/com/aijpq/pokerrecorder/evaluation/ModelEvalYuvConverter.java:17-21,25-45`: native library `mnnyolodetector`, `nativeRgba8888ToArgb`; Java extracts the Image's crop and first plane, passes byte buffer, rowStride, pixelStride and crop coordinates. The exposed interface is RGBA buffer-to-bitmap conversion; it is not passed a separate game window or projection-layer handle.

### A misleading method name, checked against raw DEX

`ScreenCaptureHelper.applyFloatingWindowMask(Bitmap)` at `:199-205` appears almost empty in JADX. To avoid relying on optimizer presentation, the original `classes.dex` method was parsed directly (codeOffset6101148,27 code units). The supporting `reference-capture-occlusion-dex-check.json` is a private local artifact, not distributed in Git.

Normal bytecode path reads `LyF.g`, invokes `LwF.h` (JADX `C4163wF.m10434h`, `p000/C4163wF.java:93-94`), reads the floating manager's current state (`LyF.a`, `Lkr0.getValue`) and casts it, then returns. Exception path logs. The Bitmap parameter is never read or passed. There is no Canvas, hide, capture or native call in this method. The manager constructor/current-state fields are visible in `p000/C4290yF.java:16-43`. Thus this method does not actually paint a floating-window mask or recover hidden pixels in this APK version.

## Reference floating window flags and visibility

- `research-private/jadx/sources/p000/C1404WJ.java:154,173`: normal `game_view` and `game_view_six` use `C3368kF` and the same floating container family.
- `research-private/jadx/sources/p000/C3368kF.java:97`: `f16912t` (`windowFlags`, confirmed by its toString at134) is fixed at40 =0x28 = NOT_FOCUSABLE|NOT_TOUCH_MODAL.
- `research-private/jadx/sources/p000/C3835rF.java:516-518`: creates `WindowManager.LayoutParams(width,height,2038,windowFlags,-3)`, gravity TOP|LEFT, cutout mode1. Thus TYPE_APPLICATION_OVERLAY, translucent pixel format, ordinary input flags; no SECURE bit8192. A translucent window format alone does not imply its drawn card-counter cells are transparent.
- `research-private/jadx/sources/p000/AbstractC0079BF.java:141,242`: ordinary remove/add operations exist for window lifecycle. The examined capture frame acquisition/conversion/dispatch chain does not invoke these around each frame.
- Searches of the decompiled sources found no named `FLAG_SECURE`, `privateFlags`, `setSkipScreenshot`, `setScreenCapture`, `setTrustedOverlay`, or `setHideOverlay` capture-exclusion mechanism. This does not prove absence of all conceivable dynamically constructed or native behavior.

## Real reference black masking: configured block areas

This is separate from the no-op floating-mask method:

- `research-private/jadx/sources/com/aijpq/pokerrecorder/service/DirectionConfigProcessor.java:1827-1843`: selects `findBestBlockSchema(game,mode,playerCount)`, converts configured `BlockPositionRect` entries into CGRectF, updates `currentBlockAreas`. These are schema-configured rectangles; this code does not derive them from the current floating window's live bounds.
- `.../service/ScreenCaptureService.java:1593-1606`: fetches those current block areas, applies `BlockAreaMasker` to the bitmap, then calls the inference pipeline callback.
- `.../service/BlockAreaMasker.java:19-21,29-45`: FILL paint color-16777216 (black), clips each rectangle to the bitmap and draws it. This destroys pixels in the configured rectangles at that processing stage; it does not reconstruct covered content.

## Current JPQ strategy compared

| Point | Reference APK inspected | Current JPQ |
|---|---|---|
| Capture permission | Default-display MediaProjection for four-player on34+ | Same default-display request: `app/src/main/kotlin/com/cyotun/jpq/MainActivity.kt:472` |
| Capture surface | VirtualDisplay flags16 + ImageReader | `CaptureService.kt:175-176` AUTO_MIRROR; `:248` RGBA_8888 ImageReader3 |
| Floating flags | TYPE2038, flags40, translucent | `OverlayController.kt:31-38`: TYPE_APPLICATION_OVERLAY, NOT_FOCUSABLE|NOT_TOUCH_MODAL|LAYOUT_IN_SCREEN, translucent; no SECURE |
| Visibility around capture | No per-frame hide/restore identified | No per-frame hide/restore in capture loop; normal overlay lifecycle remains separate |
| Floating-region masking | Named method does not modify bitmap | `CaptureService.kt:261-265` snapshots bar/adjustment/tip masks; `vision/CaptureFrameProducer.kt:33-38` draws those rectangles gray on owned bitmap |
| Other black masking | Schema-selected block-area black rectangles before inference | `CaptureFrameProducer.kt:29` black-fills the canvas before copying input; this initializes uncovered/cropped areas, then actual overlay masks are drawn separately |
| Observation contract | No equivalent verified visibility contract established in this audit | Captured mask rectangles passed to recognizer (`TencentRecognizer.kt:106,121`); `TencentFrameVisibility.kt:15-33` removes affected hand/region observations and tags localized overlay faults |
| Pre-mask raw evidence hook | Normal diagnostic bitmap follows floating-mask no-op, before service block masking | `CaptureFrameProducer.kt:31` calls opt-in `CaptureFrameProbe` before overlay masks; probe is documented at `CaptureFrameProbe.kt:18-24` |

JPQ's explicit mask prevents recognizers from using its own bar text/graphics as cards. It also conservatively rejects covered badge/grade evidence. Reference's lack of that particular active mask is not evidence it reads the original card/badge beneath the visible bar.

## Native and runtime limits

The APK includes native capture conversion/preprocessing/inference components. `runtime/preprocess/NativeActiveRegionSession.java:38-55,286-350` loads `mnnyolodetector` and submits a Bitmap plus region/threshold configuration to native routines; those interfaces by themselves do not establish a compositor-layer bypass. The alternative DiagnosticEngineCaptureStrategy is also visible: `research-private/jadx/sources/p000/C1127Rv.java:757-789` supplies a SurfaceTexture/Surface to the same MediaProjection `createVirtualDisplay` with flags16; `:412` uses GLES20.glReadPixels for the resulting frame. It is a different GPU/readback transport, with no identified layer-exclusion or hide operation, and is not an experimentally confirmed underlay capture. No machine-code reverse engineering or runtime native capture trace was performed here. Hidden native/OEM behavior is therefore unknown, rather than asserted impossible.

A definitive same-device claim would require same-frame pre-mask capture evidence with a deliberately opaque reference bar covering a known game feature and the feature still visible in captured pixels. This audit did not produce that evidence and does not request device work or propose changing production behavior to assume it.
