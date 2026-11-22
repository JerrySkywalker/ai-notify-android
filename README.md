# AINotify for Android and Wear OS

One Gradle project builds a phone inbox and a watch companion.

- Phone: Add the relay URL and reader token. Enable receiving. Tap a reply notification to read the entire stored message.
- Wear OS: In paired mode the phone passes recent reply previews through the Wearable Data Layer. Both APKs must use the same application ID and signing key. The watch app posts its own notifications.
- Wear OS direct mode: Enter the relay URL and reader token from **Relay settings** on the watch. The watch uses the same foreground receiver as the phone. To return to paired mode, select **Use phone**.

For the prototype, reception uses an explicitly started foreground data sync service. Android may stop it, including when its background execution budget is exhausted. Reopen the app to resume and fetch later events.

## Local build

Use JDK 17, Android Platform 36, Build Tools 36.0.0 and two Android Studio AVDs. Run:

```powershell
./scripts/local-ci.ps1 -SdkRoot $env:ANDROID_HOME -JavaHome $env:JAVA_HOME
```

The debug packages use `io.github.jerryskywalker.ainotify.dev` and allow HTTP only on emulator or loopback host addresses. Release builds require HTTPS. APKs are in the individual module's `build/outputs/apk/debug/` directories. Run the AINotify entry repository's local CI for the two-AVD end-to-end check.

The Gradle project layout was adapted from Token Monitor Companion.
