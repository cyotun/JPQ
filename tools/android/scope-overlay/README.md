# Separate-UID scope overlay probe

This helper draws one non-touchable magenta overlay through its existing signature-protected service. It performs no capture, game launch, input injection, network access, or file storage. The exported launcher exits unless explicitly opted in. In launcher mode only, it starts the same service as a short foreground service with a low-importance notification so it can remain active behind the consent flow; it stops and unbinds within 75 seconds, and the service retains its independent 90-second watchdog. The existing bound test-client path does not start a foreground service.

With the helper and candidate installed and signed compatibly, and the game's task already in the foreground, explicitly launch the helper with:

```powershell
adb shell am start -n com.cyotun.jpq.scopeoverlay/.ScopeOverlayLauncherActivity --ez overlayProbe true
```

The activity binds its own service, shows the overlay, then moves itself to the back after the service reports the overlay visible. Stop it earlier by closing the helper task. Opening the launcher without `overlayProbe=true` exits immediately.

To diagnose a standalone run, read the helper's concise lifecycle logs:

```powershell
adb logcat -s ScopeProbeLauncher:I ScopeProbeService:I
```
