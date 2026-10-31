# Pool Vision Pro 0.4 — experimental source, NOT device-verified

This update adds real screen-capture plumbing, OpenCV circle candidates, a selected-shot geometry guide, and Android Accessibility gesture dispatch. It is not a finished autonomous Miniclip bot, and no APK is included. Circle detection does not classify ball numbers, suits, turn ownership, or the cue ball automatically. You must select the white cue ball, a legal target and a pocket. The app does not verify that the game accepted the desired aim angle; automatic shot execution is experimental.

## Android Studio / Windows setup
1. Install Android SDK 35 and use JDK 17 (or a compatible Android Studio bundled JDK).
2. This archive originally lacked a Gradle wrapper JAR. Run `powershell -ExecutionPolicy Bypass -File .\setup-gradle.ps1` in the extracted PoolVisionPro folder. It downloads Gradle 8.11.1 from Gradle, checks the published checksum and generates the official wrapper in an empty bootstrap project. Internet access is required. If Java is missing, set JAVA_HOME to your Android Studio `jbr` directory first.
3. Open PoolVisionPro in Android Studio; allow Gradle Sync. Dependencies: AGP 8.9.2, Kotlin 2.0.21, OpenCV 4.10.0.
4. Connect a phone with USB debugging. Run `app`. For a debug APK: `gradlew.bat assembleDebug`. Expected output: `app/build/outputs/apk/debug/app-debug.apk`.

## First device test
Use an expendable practice session to calibrate; gestures move real controls.
1. Rotate to landscape. Enable the overlay and Pool Vision gestures in Android settings.
2. Start screen capture; select **entire screen**, not a single app. No images are stored or uploaded.
3. Open 8 Ball Pool. Enable its Tap-to-Aim option if available. If your version does not support this interaction, this automation path is unsupported; leave Aim + Shoot disabled.
4. Tap Table, then two opposite corners of the inner playable cloth (not outer rails). Default geometry is only a rough starting point from the supplied 1280×576 video.
5. Wait for circle outlines. Tap Cue, then the detected white ball. Tap Target, then a detected legal object ball. Tap Pocket, then the pocket mouth. A yellow cue-to-ghost line and green target-to-pocket line appear only for an unobstructed direct shot.
6. Tap Power, then the power slider's neutral grip point, then the desired pull endpoint. These are overlay calibration taps and are not passed to the game. Choose low power initially. Recalibrate if screen size, slider side or layout changes.
7. Confirm the Tap-to-Aim switch only after enabling that option in the game. Press Aim + Shoot. The service sends a tap at the computed ghost-ball location, waits 900 ms, and sends your calibrated swipe. It checks foreground package, three stable detection frames, recent capture, selected clear path, and enabled gesture service. It does NOT recognize whose turn it is.
8. Selections clear after execution. Choose the next shot yourself. STOP in the panel or notification cancels a pending shot and stops capture; a gesture already dispatched may finish.

## Known limitations
- No full-match autoplay, automatic legal target selection, bank-shot planner, spin, speed solver, throw, cushion calibration or guaranteed pots.
- Hough circle candidates can miss balls or detect logos, aiming markers and overlay outlines. The white-table video has shadows and aim marks; numeric accuracy has not been measured.
- Full-screen overlays may appear in the capture. Temporal stability and obstruction checks reduce some errors but do not establish detection correctness.
- Aim by tap and power direction depend on actual game settings. Gesture success means Android dispatched input, not that the cue angle or pot was correct.
- Restart capture after rotation or any capture-size change. Turn off accessibility access in settings to revoke gesture capability.
- Overlay header is fixed at the top and may cover game UI. Detection uses manually calibrated table bounds.

## Verification performed here
- Inspected the source and a frame at 12 seconds in the supplied video (1280×576, 58.26 seconds).
- Parsed manifest/resource XML and checked required declarations/source presence.
- Build and phone tests were NOT run: Android SDK/Gradle were unavailable and dependency downloads were unavailable in this environment. The setup script and native OpenCV loading also need Windows/phone verification.

## Technical references
- https://developer.android.com/media/grow/media-projection
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- https://docs.opencv.org/4.10.0/d5/df8/tutorial_dev_with_OCV_on_Android.html
- https://developer.android.com/build/releases/agp-8-9-0-release-notes
