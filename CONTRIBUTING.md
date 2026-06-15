# Contributing

When contributing to this repository, please first discuss the change you wish to make via issue,
email, or any other method with the owners of this repository before making a change.

Check the [open pull requests](https://github.com/TortugaPower/bookplayer-android/pulls) for previous efforts. If in doubt, open a new issue. Don't be shy, but understand that we might close issues to focus the discussion and development.

Please note we have a code of conduct, please follow it in all your interactions with the project.

## Setting up the project

1. Clone the repository and open it in **Android Studio** (Koala or newer).
2. Copy `local.properties.example` to `local.properties` and fill in the values you need. Every key is optional for local development and is also read from the environment, so you can leave the file uncommitted and inject values via env vars in CI:
   - `DEV_BASE_URL` — defaults to `http://10.0.2.2:5003` (the emulator's alias for your host's localhost), so the `dev` flavor builds with no setup.
   - `GOOGLE_CLIENT_ID`, `SENTRY_DSN`, `REVENUECAT_API_KEY` — only needed to exercise Google Sign-In, crash reporting, or purchases.
3. Select the **devDebug** build variant and run. Release signing is optional and only needed to produce signed builds — see `keystore.properties.example`.

## Reporting a bug

1. Check [previous issues](https://github.com/TortugaPower/bookplayer-android/issues). Your bug may already be fixed in the next version waiting to be released.
2. Describe your issue as extensively as possible. Our issue template will provide some basics.
3. BookPlayer shows its version number and other debug info at the very end of the settings screen.

## Pull Request Process

1. Make sure the code you present is properly documented and tested.
2. Try to be as concise as possible. This makes it easier to discuss and evaluate the changes you propose.
3. Be patient. Open Source takes time.
4. Be attentive. Pull requests are the base for discussion and will require your feedback.
5. Pull requests without further interaction may be closed at any point.

While your contribution will be under [the license of the project](./LICENSE) (GNU GPL v. 3.0), be aware that it will most likely end up being part of a compiled binary available on the Google Play Store.

# Code of Conduct

Be excellent to each other. See [our Code of Conduct](./CODE_OF_CONDUCT.md) for details.
