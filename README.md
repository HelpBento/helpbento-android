# helpbento-android

HelpBento AI chat widget for native Android apps.

This SDK renders **no native chat UI**. `HelpBento.open()` launches a full-screen Activity
containing a WebView that loads the same `widget.js` that powers the JavaScript SDK and the
[Capacitor plugin](https://github.com/HelpBento/helpbento-capacitor). One UI source of truth: widget fixes and new features ship
server-side with no SDK release required.

## Install

Add JitPack as a repository (in `settings.gradle.kts`):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then add the dependency:

```kotlin
dependencies {
    implementation("com.github.HelpBento:helpbento-android:0.1.0")
}
```

## Quick start

Initialize once, e.g. in your `Application` class:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HelpBento.init(this, companyId = "YOUR_COMPANY_ID")
    }
}
```

Identify the logged-in user after login (the HMAC is generated on **your** server — see
the HelpBento widget integration guide):

```kotlin
HelpBento.identify(
    userId = user.id,
    email = user.email,
    name = user.name,
    hmac = hmacFromYourServer,
)
```

Open the chat from a Help button:

```kotlin
findViewById<Button>(R.id.helpButton).setOnClickListener {
    HelpBento.open(this)
}
```

On logout:

```kotlin
HelpBento.clearUser()
```

## API

| Member | Description |
|---|---|
| `init(context, companyId, agentId?, scriptUrl?)` | Configure the SDK. Call once before anything else. |
| `identify(userId, email?, name?, hmac?)` | Identify the logged-in user. Safe to call before `open()` or before the chat page has loaded — the identity is queued and (re)applied to every chat page load. |
| `clearUser()` | Clear the identified user (call on logout). |
| `open(context)` | Launch the chat Activity. |
| `close()` | Close the chat if it's currently showing. |
| `destroy()` | Full teardown; `init()` may be called again. |
| `isOpen` | Whether the chat Activity is currently visible. |
| `setListener(listener)` | Set (or clear) the `HelpBentoListener` notified of chat events. |

```kotlin
HelpBento.setListener(object : HelpBentoListener {
    override fun onOpen() { /* ... */ }
    override fun onClose() { /* ... */ }
    override fun onMessageReceived(content: String) { /* assistant replied */ }
    override fun onError(message: String) { /* ... */ }
})
```

## Domain allowlist caveat

If your workspace restricts the widget to specific domains (Settings > Support Agents), the
WebView's requests will be denied — a mobile app has no meaningful origin to allowlist. Leave
the allowlist empty if you ship the widget in this SDK.

## Building this module

No system Gradle needed — use the wrapper:

```bash
./gradlew :helpbento-sdk:assembleRelease
```
