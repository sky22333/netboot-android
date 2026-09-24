# NetBoot

[简体中文](README.md) | English

Turn an Android phone with root permission into a **PXE boot server** or a **read-only USB boot device**.
Download Windows ISOs from Microsoft, import local images, and pause and resume downloads.

## Requirements

- Android 8.0 or later, with permission from your root manager for this app to use `su`. The app checks existing permissions on startup; it does not install or patch root software.
- PXE: connect the phone and computer to the same local network, with communication between devices allowed. The computer must support network boot.
- USB: connect the phone to the computer with a data cable. The phone's kernel and USB configuration must support adding a mass storage function.

## PXE network boot

1. Open the PXE page and select a network interface. Use **ProxyDHCP** on home networks; use full DHCP only on isolated networks.
2. Leave the boot file blank to select it automatically for the computer's architecture, or import a boot file and configure the iPXE script.
3. Start PXE, then select network boot in the computer's boot menu. Stop the service in the app when finished.

The default menu requires internet access to download installation resources. ISOs on the Images page are not automatically used as PXE installation sources. Restart the service after changing its configuration.

## Images and USB boot

1. Download or import an ISO on the Images page and wait until it is ready.
2. Connect the phone to the computer with a data cable, tap the image's USB icon, and enable USB boot. If preparation is required, progress is shown and you can cancel. The original image remains unchanged.
3. Select the phone's read-only disk or optical drive in the computer's boot menu.
4. When finished, stop USB boot in the app and wait for the phone's USB configuration to be restored before unplugging the cable. If restoration fails, retry; if it still fails, restart the phone.

Do not switch USB modes while USB boot is active. File transfer and USB debugging may temporarily disconnect. Compatibility depends on the image, computer firmware, and phone drivers.

### Optional: include Windows drivers

Use the image's **Include drivers** icon to select a ZIP or folder containing a complete INF driver package and add it to the Windows/WinPE USB media, without requiring the computer to be online.
Windows Setup can discover the drivers automatically, or you can browse the `$WinPEDriver$` folder through **Load driver**; standalone WinPE requires manual loading.

## Preview

<div style="display:inline-block">
<img src=".github/image/demo1.jpg" alt="App screenshot 1" width="230">
<img src=".github/image/demo2.jpg" alt="App screenshot 2" width="230">
<img src=".github/image/demo3.jpg" alt="App screenshot 3" width="230">
</div>

## Build and release

Use the same toolchain as CI: JDK 25, Android SDK 37, NDK 28.2.13676358, and Go 1.27.1.
The JVM target is 17, and the Gradle version is pinned by the project wrapper.

```bash
./gradlew testDebugUnitTest lintRelease
./gradlew assembleRelease -PnetbootVersionName=1.0.0
# Only when the emulator ABI is needed
./gradlew assembleDebug -PnetbootAbis=arm64-v8a,armeabi-v7a,x86_64
# Requires a connected, dedicated test device
./gradlew connectedDebugAndroidTest
```

See [AGENTS.md](AGENTS.md) for development guidelines and testing requirements, and [NOTICES.md](NOTICES.md) for third-party sources and licenses.
