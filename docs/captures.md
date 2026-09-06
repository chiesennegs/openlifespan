# Capture Retrieval

The debug app writes visible log lines to private app storage:

`files/openlifespan-log.txt`

It also mirrors each line to Logcat with tag:

`OpenLifeSpanLogger`

## Pull The App Log

Use Android Studio's bundled `adb` if `adb` is not on your shell path:

```sh
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
mkdir -p captures
"$ADB" devices
"$ADB" shell run-as dev.openlifespan.logger cat files/openlifespan-log.txt > captures/openlifespan-log.txt
```

`run-as` works only for debug builds, which is what Android Studio installs with the play button.

## Stream Logcat

```sh
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
"$ADB" logcat -s OpenLifeSpanLogger
```

## Suggested Capture Routine

1. Pair the treadmill in Android's Bluetooth settings.
2. Run the debug app from Android Studio.
3. Tap Clear Log.
4. Tap Scan / Probe.
5. Press the treadmill Bluetooth button.
6. Wait 20 seconds.
7. Pull `files/openlifespan-log.txt` into `captures/`.
