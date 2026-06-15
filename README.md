# 🎧 BookPlayer Android

A modern, high-performance audiobook player built with Jetpack Compose and the latest Android architecture components. Designed for a seamless, continuous, and high-quality listening experience.

---

## ✨ Features

### 🔊 Advanced Playback
- **Precise Control**: Variable playback speed, custom skip intervals, and volume boost.
- **Smart Rewind**: Automatically rewinds a few seconds after a pause to help you catch back up.
- **Continuous Play**: Seamless transition between library items with auto-play support.
- **Background Play**: Robust foreground service with rich notification controls, including book artwork and navigation buttons (Next/Previous).

### 📚 Library Management
- **Nested Organization**: Full support for folders and subfolders.
- **Batch Actions**: Powerful multiselect mode to move, delete, or edit multiple items at once.
- **Metadata Editor**: Customizable book titles, authors, and high-quality artwork.
- **Artwork Engine**: Automatic image compression (512px) and caching for a visually rich library.

### 🛡️ Sophisticated Concurrency & Sync
- **Multi-Queue Engine**: Concurrent processing of different task types (Uploads, Server Updates, External Integrations).
- **Persistent Tasks**: All background tasks are stored in a database, ensuring they survive app restarts or device reboots.
- **Tiered Access Policy**: Built-in security that manages feature access (Lite/Pro) based on account tier.

### 🔐 Modern Authentication
- **Passkeys**: Biometric-backed, passwordless login for ultimate security.
- **Google Sign-In**: Quick and easy social authentication.
- **Secure Sessions**: Full account synchronization with the BookPlayer backend.

### 🎨 Beautiful & Customizable
- **Theming System**: Includes premium themes like Ayu, Pure Black, and Green Forrest.
- **Dynamic UI**: Fully responsive layouts built with 100% Jetpack Compose.
- **Haptic Feedback**: Tactile responses for critical actions like long-press selection.

---

## 🛠️ Tech Stack

- **UI**: Jetpack Compose (Material 3)
- **Media**: AndroidX Media3 (ExoPlayer + MediaSession)
- **Database**: Room (SQL Persistence)
- **Networking**: Retrofit + OkHttp + Gson
- **Auth**: Android Credential Manager + Identity GoogleID
- **Images**: Coil (Async Loading & Caching)
- **Architecture**: MVVM + Repository Pattern + Kotlin Coroutines & Flow

---

## 🚀 Getting Started

1.  Clone the repository.
2.  Open in **Android Studio Koala** or newer.
3.  Copy `local.properties.example` to `local.properties`. The `dev` flavor builds with no further setup; fill in `GOOGLE_CLIENT_ID`, `SENTRY_DSN`, or `REVENUECAT_API_KEY` only if you want to exercise those features.
4.  Select the **devDebug** build variant, then Build and Run.

See [CONTRIBUTING.md](./CONTRIBUTING.md) for the full setup and contribution guide.

---

## 🤝 Contributing

Contributions are welcome! Please read our [Contribution Guidelines](./CONTRIBUTING.md) and [Code of Conduct](./CODE_OF_CONDUCT.md) before opening an issue or pull request.

---

## 📄 License

Licensed under [GNU GPL v. 3.0](https://opensource.org/licenses/GPL-3.0). See [`LICENSE`](./LICENSE) for details.

---

## ⚖️ Legal

- [Privacy Policy](./PRIVACY_POLICY.md)
- [Terms of Use](./TERMS_CONDITIONS.md) — [General](./GENERAL_TERMS.md) · [BookPlayer Pro](./SUPPLEMENTAL_TERMS.md)
