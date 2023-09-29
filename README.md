# ReactMap for Android

Use ReactMap as an Android app with battery improvements, including:

* Use Google location services to follow location.
* Reduce animations.

Other features:

* See map even behind your status bar and navigation bar!
* More conveniently refresh the webpage when fullscreen. (During network congestion, Chrome HTTP/3 scheduler retries requests at its own pace. Doing a manual refresh overrides this.)
* Login button is clicked automatically (Discord login only).
* You could make alerts follow location in background.

## Guide for custom build

1. Customize `reactmap.defaultDomain` and `reactmap.packageName` in `gradle.properties`.
2. If you want to support more domains, edit `app/src/main/AndroidManifest.xml`: find the line `<data android:host="${defaultDomain}" />` and add more domains by adding lines like `<data android:host="www.reactmap.dev" />`.
3. Build.
