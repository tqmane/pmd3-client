# pmd3-client

Minimal Android client for `pymobiledevice3 developer core-device display serve-web`.

It keeps the Android side out of the way: the pmd3 viewer is shown full-screen, the viewer chrome is hidden, page scrolling/overscroll/zoom are disabled, and touch gestures stay on the remote iPhone surface instead of dragging a browser page around.

## Host setup

Run pymobiledevice3 on the computer connected to the iPhone and expose `serve-web` to your LAN:

```bash
pymobiledevice3 developer core-device display serve-web \
  --bind 0.0.0.0 \
  --http-port 8080 \
  --password change-me
```

Then put the Android device on the same LAN.

## Android usage

1. Install the APK from the GitHub Actions artifact.
2. On first launch, enter the host address, for example `192.168.1.10` or `http://192.168.1.10:8080`.
3. Enter the `serve-web` password if you set one.
4. The URL and password are saved locally and reused on later launches.

To reopen connection settings without putting permanent controls over the iPhone display, press **Android Volume Up + Volume Down together**.

## Gesture behavior

- Full-screen immersive WebView.
- pmd3's native `/touch` contact/release handling is preserved.
- Viewer trays, browser-like page chrome, scrollbars, overscroll and pinch zoom are disabled.
- Android system gesture exclusion is requested for the viewer surface on Android 10+.
- The screen stays awake while the client is open.

The underlying video path is still pmd3 `serve-web`, so Android System WebView/Chrome must expose WebCodecs `VideoDecoder`. The app warns if it is unavailable.

## CI

Every pull request is built by `.github/workflows/android.yml`. The resulting debug APK is uploaded as the `pmd3-client-debug` workflow artifact.
