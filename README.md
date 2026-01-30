# Trick

A Kotlin Multiplatform messaging application for Android and iOS that enables peer-to-peer communication using WiFi Aware. Messages are encrypted using the Signal Protocol (libsignal) for end-to-end security.

## Features

- Peer-to-peer messaging over WiFi Aware
- End-to-end encryption using Signal Protocol
- Text and image messaging
- QR code-based key exchange

## Project Structure

- `composeApp/src/commonMain` - Shared code across platforms
- `composeApp/src/androidMain` - Android-specific implementation
- `composeApp/src/iosMain` - iOS-specific implementation
- `iosApp` - iOS app entry point

## Requirements

- Android: API 29+ (Android 10+) with WiFi Aware support
- iOS: iOS 13+ with WiFi Aware support
