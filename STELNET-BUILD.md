# Building and releasing Stelnet Tools

1. Tools: JDK 17+ (21 is fine), Android Studio or the Android SDK with platform 37.
2. Make a signing key ONCE and never lose it (updates only install over the same key):
   keytool -genkeypair -v -keystore stelnet-release.jks -alias stelnet -keyalg RSA -keysize 4096 -validity 10000
3. Local build: create keystore.properties (gitignored) next to settings.gradle.kts:
   storeFile=/path/to/stelnet-release.jks
   storePassword=...
   keyAlias=stelnet
   keyPassword=...
   then: ./gradlew assembleRelease   ->  app/build/outputs/apk/release/
4. Without keystore.properties the build is signed with a DEBUG key: fine to test, not to ship.
5. CI release: add repo secrets KEYSTORE_BASE64 (base64 -w0 stelnet-release.jks), KEYSTORE_PASSWORD,
   KEY_ALIAS, KEY_PASSWORD, then: git tag v1.3.1 && git push --tags
   The workflow uploads stelnet-tools-1.3.1.apk to the release; the in-app updater picks it up.
6. Bump versionCode/versionName in app/build.gradle.kts before each new tag.
7. Certificate fingerprint for the README:
   keytool -list -v -keystore stelnet-release.jks -alias stelnet | grep SHA256
