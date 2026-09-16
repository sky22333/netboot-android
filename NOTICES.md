# Third-party notices

## USB media libraries

`app/src/main/cpp/CMakeLists.txt` pins original source archives and SHA-256 values:

- [wimlib 1.14.5](https://wimlib.net/): library-only build without ntfs-3g; LGPL-2.1-or-later. No upstream source edits; the build omits unused platform/API sources.
- [libudfread 1.2.0](https://code.videolan.org/videolan/libudfread): LGPL-2.1-or-later. The build applies a one-line fix selecting the existing `-1` partition discovery mode rather than physical partition zero. Partition-map validation remains unchanged. This supports CDIMAGE volumes with nonzero physical partition identifiers.
- [FatFs R0.16](https://elm-chan.org/fsw/ff/): ChaN's redistribution license, with the published R0.16 patch-1/patch-2 corrections and application-specific configuration, all recorded in CMake.

Native library license texts are packaged in `app/src/main/assets/licenses/`. Distributors must preserve the corresponding source, CMake modifications, build configuration and relinkable native objects required by the LGPL; do not distribute an APK alone as the complete compliance package.

The application bundles the following upstream PXE assets so that network boot remains available offline after installation:

- `ipxe-arm64.efi`, `ipxe-x86_64.efi`, and `undionly.kpxe` from the [netboot v1.0.3 release](https://github.com/sky22333/netboot/releases/tag/v1.0.3).
- The default `embed.ipxe` menu from the [netboot repository](https://github.com/sky22333/netboot/blob/main/embed.ipxe).

The netboot project is distributed under GPL-3.0. Its license text is packaged at `app/src/main/assets/licenses/netboot-GPL-3.0.txt`; corresponding source is available from its public repository and release tag. iPXE builds are generally GPL-licensed, with the upstream licensing notice packaged at `app/src/main/assets/licenses/ipxe-COPYING.txt` and source available from [ipxe/ipxe](https://github.com/ipxe/ipxe).

Pinned asset SHA-256 values:

| Asset | SHA-256 |
| --- | --- |
| `ipxe-arm64.efi` | `a25ec1e57caf215108b92eeb22ccc081a2b3c238019af86e1f47bf2e8d043347` |
| `ipxe-x86_64.efi` | `9767ac1ab11b612c5e97db7a6c5a57267a6378a3eb174752ea5ff4f5974d19d8` |
| `undionly.kpxe` | `f0c1c2f07a15f6a8e987f61ec8835bdc85b5557754348be8fbbdb08af4dfcd30` |
| `embed.ipxe` | `cc69dc5bf6ad662f8abe627c4fbfeede9bd6db52b00f661b8feb3fcae966167d` |
