# tvhome

A small Android TV home launcher. It has a wallpaper gradient, a top bar with a clock and Wi-Fi, Bluetooth and settings shortcuts, a dock of pinned apps and an all apps grid. Press Down on the dock to open the grid. Press Up on the first row, Back or Home to return.

## Customize

Select the sliders icon in the top bar. From there you can choose the wallpaper, the theme (Auto, Dark or Light) and the top bar icons, and check for updates. The theme only changes the layers over the wallpaper: the top bar, the dock, the all apps screen and the dialogs.

## Dock

Hold OK on an app, in the dock or in all apps, to open its menu. A dock app can move left or right or be removed. Any other app can be added to the dock. The dock holds 5 apps, and adding a sixth shows "Dock is full right now".

## Build

You need JDK 17 and the Android SDK (`ANDROID_HOME`).

```shell
make build
```

This builds one APK per ABI in `app/build/outputs/apk/release/`: `arm64-v8a`, `armeabi-v7a` and `x86_64`. Without a `key.properties` file the APKs are signed with the debug key.

```shell
make install DEVICE=<ip>:5555
```

where `<ip>:5555` is the address of the device in `adb`. It installs the APK that matches the device ABI.

## Releases

Conventional commits drive the version. Every push to `main` updates a release PR (`version.txt` and `CHANGELOG.md`): `feat:` bumps the minor version, `fix:` the patch version. Merging it creates the tag and the GitHub release. Then CI builds the signed APKs and attaches them as `tvhome-<version>-<abi>.apk`.

For this to work:

1. In the repo settings, under Actions > General, allow GitHub Actions to create and approve pull requests.
2. Create a release keystore once and keep a backup outside the repo. **Every release must use the same key**, or installed devices can't update.

```shell
keytool -genkeypair -keystore release.keystore.jks -alias tvhome -keyalg RSA -keysize 2048 -validity 10000
```

3. Add the keystore and its passwords as repo secrets:

```shell
base64 -i release.keystore.jks | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS
gh secret set ANDROID_KEY_PASSWORD
```

The release job fails when `ANDROID_KEYSTORE_BASE64` is missing. To rebuild the APKs of an existing tag, run the `release` workflow manually with the tag name.

## Updates

Customize > Check for updates follows the `releases/latest` redirect of this repo, so it doesn't use the GitHub API and its per-IP rate limit. It then downloads `tvhome-<version>-<abi>.apk` for the device ABI and hands it to the system installer. A failure shows its reason in the toast and in `logcat` under the `tvhome` tag. The first time, you have to allow installs from TV Home in the system settings, and you confirm each install. Keep the full ABI string in the asset names, because installed apps match on it.
