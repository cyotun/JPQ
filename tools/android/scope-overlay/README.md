# Separate-UID scope overlay probe

This helper draws one non-touchable magenta overlay through its existing signature-protected service. It performs no capture, game launch, input injection, network access, or file storage. The exported launcher exits unless explicitly opted in. In launcher mode only, it starts the same service as a short foreground service with a low-importance notification so it can remain active behind the consent flow; it stops and unbinds within 75 seconds, and the service retains its independent 90-second watchdog. The existing bound test-client path does not start a foreground service.

With the helper and candidate installed and signed compatibly, and the game's task already in the foreground, explicitly launch the helper with:

```powershell
adb shell am start -n com.cyotun.jpq.scopeoverlay/.ScopeOverlayLauncherActivity --ez overlayProbe true
```

The activity binds its own service, shows the overlay, then moves itself to the back after the service reports the overlay visible. Stop it earlier by closing the helper task. Opening the launcher without `overlayProbe=true` exits immediately.

For a synchronized system-notification visibility check, add `--ez notifyProbe true`:

```powershell
adb shell am start -n com.cyotun.jpq.scopeoverlay/.ScopeOverlayLauncherActivity --ez overlayProbe true --ez notifyProbe true
```

This opt-in posts one separate-channel `IMPORTANCE_HIGH` notification, intended to appear as a heads-up banner for at most 10 seconds while the magenta overlay runs. It does not request `POST_NOTIFICATIONS` or change notification settings. On Android 13 and later it posts only if notification permission is already granted; notifications disabled by the user or a blocked channel are also logged as skipped. The launcher logs `notification_probe_event=posted`, `skipped`, and `cancelled` under `ScopeProbeLauncher`. The notification is removed at 10 seconds or as soon as the probe stops. For a simultaneous-visible result, independently verify the physical display shows the candidate counter bar, this helper's magenta window, and the notification banner together; the notification is not part of the selected app-task capture pixels. Channel importance can be lowered by the user, in which case the event log reports the effective importance and a heads-up banner is not guaranteed.

To diagnose a standalone run, read the helper's concise lifecycle logs:

```powershell
adb logcat -s ScopeProbeLauncher:I ScopeProbeService:I
```
