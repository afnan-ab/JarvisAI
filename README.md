# Jarvis — personal AI assistant starter (Android)

A working scaffold for a Jarvis-style assistant: chat + voice interface,
on-device memory, a simple mood/"emotion" layer that colors its tone, and
real device-control actions (open apps, call, set alarms, search, volume,
camera), backed by Claude for conversation.

## What's actually here vs. what "Jarvis" implies

Being upfront about scope, since "controls anything, has feelings" is a
big claim:

- **Memory**: real and persistent — conversation history + durable facts
  saved to a local JSON file (`MemoryStore.kt`), fed back into every
  request so Jarvis stays consistent across sessions.
- **Emotion**: a transparent simulation, not real feeling — a mood score
  that shifts with sentiment and decays toward neutral (`EmotionEngine.kt`),
  which shapes the tone of replies. That's the honest ceiling for "feelings"
  in an LLM-backed assistant today.
- **Device control**: real, using standard Android APIs — Intents for
  calling, texting, alarms, search, app-launching, camera
  (`CommandProcessor.kt`), plus an AccessibilityService for tapping things
  on screen and reading screen text (`AssistantAccessibilityService.kt`).
  It does **not** control "anything" — no OS lets a third-party app do
  that, for security reasons. It controls what you explicitly grant it
  permission to touch.
- **Voice**: real STT (SpeechRecognizer) and TTS, on-device.

## Setup — with a PC (Android Studio)

1. Open this folder in Android Studio (Koala or newer), let Gradle sync.
2. Get an Anthropic API key from console.anthropic.com.
3. In `MainActivity.kt`, replace `YOUR_ANTHROPIC_API_KEY` — don't hardcode
   a real key if you'll commit this anywhere public. For real use, load it
   from `local.properties` (gitignored) via `BuildConfig`, or better, proxy
   the request through your own backend so the key never ships in the APK.
4. Build and run on a device or emulator (min SDK 26).
5. On first launch, grant the requested permissions (mic, call, SMS,
   contacts).
6. For on-screen control (tapping things, reading screen text), go to
   Settings > Accessibility > Installed apps > Jarvis and turn it on
   manually — Android requires this to be a deliberate user action.

## Setup — phone only, no PC (GitHub Actions cloud build)

This repo includes `.github/workflows/build.yml`, which builds a debug
APK in the cloud every time you push. You never need Android Studio.

1. **Install Termux** from F-Droid (not the outdated Play Store version) —
   this gives you a Linux command line on your phone.
2. In Termux:
   ```
   pkg install git unzip -y
   termux-setup-storage
   ```
3. Move the `JarvisAI.zip` you downloaded into Termux's view and unzip it:
   ```
   cd ~/storage/downloads
   unzip JarvisAI.zip -d ~/JarvisAI
   cd ~/JarvisAI/JarvisAI
   ```
4. Before pushing, edit `MainActivity.kt` and put your real Anthropic API
   key in (Termux has a built-in editor: `pkg install nano` then
   `nano app/src/main/java/com/jarvis/assistant/MainActivity.kt`).
5. On github.com (mobile browser is fine), create a new **empty** repo
   named `JarvisAI`. Then go to Settings > Developer settings > Personal
   access tokens > generate a token with `repo` scope — you'll use this
   as your password when pushing (GitHub no longer accepts your real
   account password over git).
6. Back in Termux:
   ```
   git init
   git add .
   git commit -m "initial"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/JarvisAI.git
   git push -u origin main
   ```
   When prompted, enter your GitHub username and the personal access
   token as the password.
7. Open your repo on github.com, tap the **Actions** tab — a build will
   already be running. Wait for it to finish (a few minutes).
8. Tap the completed run, scroll to **Artifacts**, download
   `jarvis-debug-apk` — this downloads a zip containing the APK straight
   to your phone.
9. Open it with your file manager, extract the `.apk`, tap it to install
   (you'll need to allow "install unknown apps" for your file manager or
   browser the first time — Android will prompt you).

From then on, any time you edit a file in `~/JarvisAI/JarvisAI` and
`git push`, a fresh APK builds automatically — no PC, ever.

**A simpler but more manual alternative:** skip Termux, unzip the project
with any Android file-manager app that supports zip extraction, then use
github.com's "Add file → Upload files" button in your browser to upload
the folder contents directly and commit from there. Slower for repeat
edits, but avoids the command line entirely.

## Extending it

- **More commands**: add patterns to `CommandProcessor.process()`.
- **Contact-name resolution for calls/texts**: query `ContactsContract`
  with `READ_CONTACTS` to turn "call mom" into an actual number.
- **Wake word / always-listening**: needs a foreground service + a
  wake-word engine (e.g. Porcupine) — always-on mic access is a real
  battery and privacy trade-off, worth doing deliberately.
- **Swap JSON memory for Room**: once conversation history gets large,
  migrate `MemoryStore` to a Room database for querying/searching past
  conversations.
- **Reading notifications aloud**: implement `onAccessibilityEvent` in
  `AssistantAccessibilityService.kt` to react to `TYPE_NOTIFICATION_STATE_CHANGED`.

## Files

```
app/src/main/java/com/jarvis/assistant/
  MainActivity.kt                  - chat UI, wiring everything together
  ChatMessage.kt / ChatAdapter.kt  - chat bubble list
  MemoryStore.kt                   - persistent memory (facts + history + mood)
  EmotionEngine.kt                 - mood scoring from message sentiment
  ClaudeApiClient.kt               - calls the Anthropic API for replies
  CommandProcessor.kt              - text -> real device actions
  AssistantAccessibilityService.kt - on-screen tap/read/back/home control
  VoiceManager.kt                  - speech-to-text + text-to-speech
```
