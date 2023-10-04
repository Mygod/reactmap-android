# ReactMap for Android

[![API](https://img.shields.io/badge/API-26%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=26)

Use ReactMap as an Android app with battery improvements, including:

* Use Google location services to follow location.
* Reduce animations.

Other features:

* See map even behind your status bar and navigation bar!
* More conveniently refresh the webpage when fullscreen. (During network congestion, Chrome HTTP/3 scheduler retries requests at its own pace. Doing a manual refresh overrides this.)
* Login button is clicked automatically (Discord login only).
* You could make alerts follow location in background.
* Handle links to the map without opening a new tab (requires custom build for custom domains).

For best experiences, ReactMap should be updated to this commit or later:
https://github.com/WatWowMap/ReactMap/commit/addb5f3b27c49fe9d7165c8b76f54a5b10912f67

## Guide for custom build

### Setup instructions

1. If you have not already, generate a signing key with [`keytool`](https://developer.android.com/build/building-cmdline#sign_cmdline).
   Using [RSA with a key length of 4096, 8192, or 16384 bits](https://github.com/google/bundletool/blob/0b9149c283e2df73850da670f2130a732639283d/src/main/java/com/android/tools/build/bundletool/commands/AddTransparencyCommand.java#L97) is recommended.
2. (Required for deep link support on Android 12+) Create the following file at `https://mymap.com/.well-known/assetlinks.json` by creating the file in `/path/to/reactmap/public/.well-known/assetlinks.json`:
   ```json
   [
     {
       "relation": [
         "delegate_permission/common.handle_all_urls"
       ],
       "target": {
         "namespace": "android_app",
         "package_name": "be.mygod.reactmap.com.mymap",
         "sha256_cert_fingerprints": [
           "insert your sha256 cert fingerprint"
         ]
       }
     }
   ]
   ```
   where the fingerprint can be obtained via `keytool -list -v -keystore /path/to/keystore`.
   (If you prefer to using Android Studio to create this file with a wizard, see [here](https://developer.android.com/studio/write/app-link-indexing#associatesite) for instructions.)
3. (Optional) Make modifications to the source code if you wish.

### Build instructions

Build with [injected properties](https://stackoverflow.com/a/47356720/2245107) (modifying these values as you wish):
```
./gradlew assembleRelease \
 -Pandroid.injected.signing.store.file=$KEYFILE \
 -Pandroid.injected.signing.store.password=$STORE_PASSWORD \
 -Pandroid.injected.signing.key.alias=$KEY_ALIAS \
 -Pandroid.injected.signing.key.password=$KEY_PASSWORD \
 -Preactmap.defaultHost=mymap.com \
 -Preactmap.packageName=be.mygod.reactmap.com.mymap
```

See `gradle.properties` for a complete list of supported properties.
An alternative to using `-P` switches is to adding your properties to the `gradle.properties` file in the root directory.

Success! Find your apk in `app/build/outputs/apk/release`.

### Support in-app GitHub update checking

In-app update checking is supported if you use your custom GitHub repository to distribute built apks.
To do this, it is required to use a release name of format `[v]X.Y.Z` with an arbitrary optional suffix followed by `-`.
The app version name should follow the same format (usually without `v` prefix), and the `X,Y,Z` in the version name will be compared to determine the latest update.
You may configure this by using properties `reactmap.versionCode,reactmap.versionName,reactmap.githubReleases` (see `gradle.properties` for details).
