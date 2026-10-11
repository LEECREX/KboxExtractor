# KboxExtractor

A simple Android app that fetches and decodes `keybox.xml` from the
[Integrity-Box](https://github.com/MeowDump/Integrity-Box) module by MeowDump.
No root required. No ZIP modification. Just tap and get your keybox.

## How it works

Integrity-Box hosts its keybox encoded online. This app replicates the
module's decoding process (ROT13 → hex → base64 ×10) and delivers the
resulting `keybox.xml` ready to use — without needing to flash anything.

## How to use

1. Download and install the APK from the Releases tab
2. Open the app
3. Select your Integrity-Box module ZIP when prompted
4. Tap Get Keybox
5. Preview, copy, or save keybox.xml to your Downloads folder

## Requirements

- Android 8.0 (API 26) or higher
- Internet connection
- The Integrity-Box ZIP from MeowDump/Integrity-Box releases

## Credits

All credit goes to MeowDump for creating and maintaining Integrity-Box.
This app is simply a graphical interface around his work.

Integrity-Box is licensed under GPL-3.0.
This project is also licensed under GPL-3.0 in compliance.

## By Lecrex

Built with
- [Claude](https://claude.ai) by Anthropic
- [OpenCode](https://github.com/sst/opencode) by SST

- > **Note:** The contributor graph may show an unrelated GitHub account
> due to a git configuration issue during development. That account has
> no involvement in this project whatsoever.
