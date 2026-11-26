<p align="center">
  <img src="design/logo/keeberry-mark.svg" width="106" height="120" alt="KeeBerry logo"/>
</p>

<h1 align="center">KeeBerry</h1>

[![CI](https://github.com/keeberry/keeberry/actions/workflows/ci.yml/badge.svg)](https://github.com/keeberry/keeberry/actions/workflows/ci.yml)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
![Android 9+](https://img.shields.io/badge/Android-9%2B-3ddc84.svg)

KeeBerry is a lightweight, offline password manager for Android that opens KeePass (KDBX) databases.
It stores your usernames, passwords, URLs, notes and custom fields in one encrypted file that you
keep wherever you like, on your phone, a USB drive or any cloud storage that Android's file picker
can reach. KeeBerry never talks to the internet: the app doesn't even request network access.

KeeBerry can unlock databases with a YubiKey over NFC or USB, using HMAC-SHA1 challenge-response,
so one database and one key work on your desktop and your phone.

## Quick start

KeeBerry is in early development and there are no prebuilt releases yet. To try it, build it from
source as described in [Building KeeBerry](#building-keeberry) and install the APK on a device
running Android 9 or later.

1. Tap **Open database** and choose a `.kdbx` file, or tap **Create database** to start a new one.
2. Unlock it with your master password, a key file, a YubiKey, or any combination of them.
3. Search, view and copy entries. Copied passwords are cleared from the clipboard after 30 seconds.

## Features

### Core features

- Opens KeePass databases in KDBX 3 and KDBX 4 format
- Creates new KDBX 4 databases protected by a master password, a key file, a YubiKey, or any
  combination of them, and can generate a new key file for you
- Unlocks with a master password, a key file, a YubiKey, or any combination of them
- Changes the master key of an open database to a new combination of password, key file and YubiKey
- YubiKey HMAC-SHA1 challenge-response over NFC or USB, with the slot found on its own and remembered for each database
- Key files in XML (`.keyx` and `.key`), 64-character hex, or any other file
- Searches entries by title, group, username, URL and notes
- Adds, edits and deletes entries, including custom fields with hidden values and groups
- Moves deleted entries to the recycle bin, where you can restore them to their group or delete them for good
- Remembers up to 10 recently opened databases for quick access; you choose how many in Settings
- Adaptive layout for phones, foldables and tablets, with light and dark themes that follow the system or your choice in Settings

### Security

- No internet permission: your database never leaves your device through KeeBerry
- Encryption: AES-256, ChaCha20 and Twofish
- Key derivation: Argon2id, Argon2d and AES-KDF, run in native code (AES-KDF uses the CPU's AES
  instructions)
- Copied passwords are marked as sensitive and cleared from the clipboard after 30 seconds
- Screenshots and the recent-apps preview are blocked while a database is open
- Android backups are turned off, so app data isn't copied to the cloud
- Every save is read back to check it, and the original file is restored if anything went wrong

## Building KeeBerry

You need:

- JDK 25
- The Android SDK, with the NDK and CMake versions listed in
  [`gradle/libs.versions.toml`](gradle/libs.versions.toml)

Clone the repository and build a debug APK:

```bash
git clone https://github.com/keeberry/keeberry.git
cd keeberry
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Install it on a connected device with
`./gradlew installDebug`.

To run the same checks as CI:

```bash
./gradlew testDebugUnitTest lintDebug
```

Device tests, which cover the UI and the native Argon2 and AES-KDF code, need an emulator or a
connected device:

```bash
./gradlew connectedDebugAndroidTest
```

KeeBerry builds for 64-bit devices only (`arm64-v8a` and `x86_64`). Lint treats every warning as an
error, so fix new warnings before opening a pull request.

## Contributing

Bug reports and feature ideas are welcome in the
[issue tracker](https://github.com/keeberry/keeberry/issues). If you'd like to send a pull
request, please open an issue first to discuss the change, and make sure the unit tests and lint
pass.

## Reporting security issues

Please don't report vulnerabilities in public issues. Use GitHub's
[private vulnerability reporting](https://github.com/keeberry/keeberry/security/advisories/new)
instead, and include the steps to reproduce the problem.

## License

KeeBerry is licensed under the [GNU General Public License v3.0](LICENSE).

It includes the [Argon2 reference implementation](app/src/main/cpp/argon2), used under CC0 1.0 or
Apache 2.0, and depends on [Bouncy Castle](https://www.bouncycastle.org/) (MIT license),
[Yubico YubiKit](https://github.com/Yubico/yubikit-android) and Android Jetpack (Apache 2.0).
It bundles the Google Sans Flex, Inter and Roboto fonts, used under the SIL Open Font License 1.1;
their licenses are in [`app/src/main/assets/licenses`](app/src/main/assets/licenses).

KeeBerry is an independent project and isn't affiliated with KeePass or Yubico.
YubiKey is a trademark of Yubico AB.
