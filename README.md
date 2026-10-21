# VAG Diag Android — experimental KW1281 alpha

Android/Kotlin project for a Volkswagen Golf IV 1.6 16V BCB with a USB KKL cable using CH340 and USB OTG.

## Current implementation (experimental, unverified on a car)

### Interface update

- Reworked the main screen into a VAG-inspired mobile dashboard: dark-blue header, connection status, Golf IV vehicle card, two-column quick-action tiles, engine diagnostics panel, warning panel, and a separate diagnostic log.
- Main actions are wired to the existing experimental USB/KW1281 routines; adaptation and basic-settings tiles explicitly report that those functions are not implemented.
- UI is built programmatically with native Android views; no mock ECU values are displayed.

- USB Host device discovery and runtime permission handling.
- Serial-port access through `usb-serial-for-android` (library includes CH340-family drivers).
- Experimental KW1281 slow-init by toggling the serial BREAK line at 5 baud, then switching to 10400 8N1.
- KW1281 block send/receive with byte-complement acknowledgements and block counters.
- Engine controller address `0x01` defaults to 9600 baud: attempt connection, read identification, read DTC response blocks, request measurement group and request DTC clear after explicit confirmation.
- Experimental address scan for a small list of common VAG controller addresses, using per-address baud assumptions. A failed KW1281 attempt does not prove that a module is absent; it may use KWP2000, another baud rate, or a different address.
- Share/export of the on-screen diagnostic log through Android share sheet.
- Raw response logging when a block/parameter cannot be decoded.
- GitHub Actions workflow `.github/workflows/build-apk.yml` to build a debug APK and upload it as an artifact.

## Important limitations

This is **not yet a full replacement for VCDS/Vasya Diagnost**. The protocol code is experimental and has not been tested on the user's actual cable, Android phone, or BCB ECU. CH340 support in the USB-serial library does not guarantee that a specific KKL adapter can perform the slow-init sequence correctly. Opening USB is not proof that the ECU is communicating.

- ECU identification and DTC response parsing are preliminary; DTCs are shown as raw controller codes/status, not a complete manufacturer-specific translation database.
- The KWP protocol version is estimated from the keyword bytes; KWP2000 is explicitly rejected rather than treated as KW1281.
- Measuring groups are shown as raw type/A/B triples. Correct formulas, units, and group labels still need ECU-specific validation.
- Auto-scanning all controllers, KWP2000 support, coding, login/security, basic settings, actuator tests, and adaptation read/write are not implemented.
- DTC clear is a write operation. The app asks for confirmation and does not claim success unless the response is verified.
- Do not use experimental diagnostic code while driving. Stop safely before connecting or testing.

## Build APK using GitHub Actions

This workspace has no Android SDK or Gradle installation, so an APK cannot be built or tested locally here. The included workflow can build it in GitHub-hosted CI:

1. Upload the contents of this project to a new GitHub repository.
2. Open **Actions** and enable workflows if GitHub asks.
3. Run **Build Android APK** from **Actions → Build Android APK → Run workflow**, or push to `main`/`master`.
4. Open the completed run and download the `VAG-Diag-Android-debug-apk` artifact. Inside is `app-debug.apk`.
5. Install the APK on Android and test only with ignition on and vehicle stationary.

Build requirements: JDK 17, Android Gradle Plugin 8.7.3, Gradle 8.9, Android SDK 35. The workflow installs Gradle and uses GitHub's Android build environment.

## Sources and protocol background

The implementation was cross-checked against public KW1281 protocol descriptions and reference tools, but no third-party implementation has been copied into this project. Protocol timing and the initial session handshake still require bench/vehicle validation.

KW1281 is a VAG K-Line protocol with a 5-baud address wake-up, `0x55` sync plus keyword bytes, and byte-by-byte complement acknowledgements. Useful public protocol references:

- https://github.com/gmenounos/kw1281test (MIT-licensed KW1281 reference implementation)
- https://github.com/muki01/VAG_KW1281 (GPL-3.0 firmware/reference implementation)

This project is an independent experimental implementation, not a port of either project. Review their licences before copying code or tables.


## Автоопределение скорости подключения

Кнопки подключения двигателя сначала пробуют выбранную в настройках скорость, затем остальные распространённые скорости 10400, 9600 и 4800 бод. Успешным определением считается только установленный KW1281-сеанс; отсутствие ответа не означает, что ЭБУ неисправен или отсутствует. Эта версия пока не реализует все протоколы VAG (в частности, полноценный KWP2000) и не является заменой VCDS/Васи.
