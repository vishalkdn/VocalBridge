# VocalBridge 🌉

**VocalBridge** is a high-quality, privacy-first, on-device Text-to-Speech (TTS) engine for Android. Powered by [Sherpa-ONNX](https://github.com/k2fsa/sherpa-onnx) and advanced models like Kokoro, it bridges the gap between state-of-the-art AI voice synthesis and your everyday Android usage.

## Why VocalBridge?

Most high-quality TTS engines on Android rely on cloud services. This introduces latency, requires a constant internet connection, and compromises privacy since your text is sent to third-party servers. 

VocalBridge was created to solve this by providing:
- **Total Privacy**: Everything runs 100% locally on your device. Your data never leaves your phone.
- **System-Wide Integration**: It implements the standard Android TTS Engine (`android.speech.tts`), meaning you can use it to read books, navigate, or assist with accessibility across *any* app on your phone that supports text-to-speech.
- **Fast & Natural**: By leveraging ONNX runtime optimizations, it delivers incredibly natural, low-latency, and responsive voice generation even on mobile hardware.
- **Diverse Voices**: Shipped with multiple voice profiles (powered by Kokoro and espeak-ng-data), allowing for highly customizable reading experiences.

## Features

- Deep integration with Android's native TTS framework.
- Offline, on-device inference using Sherpa-ONNX.
- Support for modern, high-fidelity neural speech models.
- Clean, Jetpack Compose-based UI for engine configuration and testing.

## Getting Started

1. Build the project using Android Studio or via the Gradle wrapper: `./gradlew assembleDebug`
2. Install the APK on your Android device.
3. Go to your device's **Settings > Accessibility > Text-to-speech output**.
4. Select **VocalBridge** as your preferred engine.
5. Enjoy fast, private, and offline speech synthesis!
