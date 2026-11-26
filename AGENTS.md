# Physical device testing

- Never run `connected*AndroidTest` (or `connectedCheck`) directly against the default `com.neoruaa.meilox` application ID on a connected physical device: AGP uninstalls the target app after tests finish, wiping the user's local data and login session.
- Always pass an isolated application ID via Gradle property when running instrumented tests on a physical device, e.g. `-Pmei.applicationId=com.neoruaa.meilox.test`.
- Never run `adb uninstall com.neoruaa.meilox` or `adb shell pm clear com.neoruaa.meilox` on a physical device unless explicitly requested by the user.
