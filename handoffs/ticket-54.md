# Handoff — ticket #54

**Ticket:** [FinAI-Mobile-2026#54](https://github.com/Humble-Coders/FinAI-Mobile-2026/issues/54) — [M6] Chat with the AI coach

## Summary

A **Coach** tab, after Goals, on Android and iOS. It shows only while capabilities enable `ai_chat`.
It talks to the backend's `/chat/*` endpoints (Finance-backend #84, #89 and #90).

- **The list.** The tab opens on the person's conversations, newest first, with a "New chat" pill.
  A conversation is deleted by swiping it away; TalkBack offers a custom action and VoiceOver the
  swipe action.
- **A conversation.** Opening one shows its history.
  - An empty chat is filled with the server's suggestion chips. Once there is history, the chips sit
    in a row above the input.
  - Typed questions and chips both appear at once as the person's bubble, followed by a typing
    indicator until the reply lands.
  - The learning card shows above the input while the reply's `learning` says the household isn't
    ready yet.
  - A reply with `disclaimer: true` carries the inline "not financial advice" note. The header's
    "Not financial advice" link opens the disclaimer text.
- **The rules live in shared code.** What shows while sending, what happens to typed text on a
  failure, where the chips go, which error message appears, and when consent is what's missing are
  all decided in shared `ChatThread`. Both apps call it and render the result. Reply text is shown
  exactly as sent; no figure is computed on the device.
- **One consent panel.** The AI-consent step is now a single panel per platform (`AiConsentPanel`),
  used by both the statement import and the coach.

## Files changed

**Shared (`sharedLogic`)**
- `model/Chat.kt`: the wire models for suggestions, conversations, messages and replies, plus
  `ChatRole` with its wire mapping.
- `repository/ChatRepository.kt` and `data/KtorChatRepository.kt`: the `/chat/*` client, with
  `@Throws` on every suspend function. A missing disclaimer reads as null.
- `usecase/ChatThread.kt`: the conversation fold (`ChatThreadState`, `ChatBubble`, `ChipPlacement`,
  `ChatFailure`) and its transitions.
- `model/ApiException.kt` and `data/ApiErrorMapper.kt`: add `ChatLimitReached` for
  `429 chat_limit_reached`.
- `i18n/Strings.kt` and `i18n/EnglishStrings.kt`: every `chat_*` string.
- Tests: `ChatThreadTest` (12) and `KtorChatRepositoryTest` (10).

**Android**
- `ui/chat/ChatViewModel.kt` and `ChatUiState.kt`: the list, the open thread, consent and the
  disclaimer. The owner, the open conversation and the draft are kept in `SavedStateHandle`.
- `ui/chat/ChatRoute.kt`, `ChatListScreen.kt` and `ChatScreen.kt`: the Coach tab, the list and the
  conversation. Consent and the disclaimer open in bottom sheets.
- `ui/components/AiConsentPanel.kt`: the consent step, extracted from the import.
  `StatementImportScreen.kt` now uses it.
- `navigation/AppNavigation.kt`: the `COACH` route, gated by `ai_chat`. The bottom bar is hidden
  while a conversation is open.
- Test: `ChatViewModelTest` (10).

**iOS**
- `viewmodel/ChatViewModel.swift`: Android's view model in Swift. The open conversation and draft
  are kept through a `@SceneStorage` snapshot that carries the owner.
- `ui/ChatListView.swift`: `CoachView` (the tab root, plus the consent and disclaimer sheets) and
  `ChatListView` (a native `List` with `swipeActions`).
- `ui/ChatView.swift`: the conversation, with bubbles, the typing indicator, chips, the learning
  card, the inline failure with Try again, and the input bar.
- `ui/components/AiConsentPanel.swift`: the consent step, extracted from the import.
  `StatementImportView.swift` now uses it.
- `navigation/RootView.swift` and `viewmodel/FeaturesViewModel.swift`: the `coach` route and tab
  gated by `ai_chat`, plus a `chat` gate that moves the person home if the feature turns off under
  them. The tab bar is hidden while a conversation is open.

## How to test

1. Run the checks:
   - `./gradlew ktlintCheck :sharedLogic:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`
   - On iOS: `xcodebuild -workspace iosApp/iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build`
2. On a test account, make sure `ai_chat` is on. In production it's `coming_soon`, so enable it
   for the test environment. Make sure finai-api is running; it's suspended at the moment.
3. Open **Coach**.
   - An empty list says so.
   - Tap **New chat**: the chips fill the chat.
   - Tap one: your bubble shows, then the typing indicator, then the reply. This works without
     AI-processing consent.
4. Type a question without having consented.
   - The consent sheet opens.
   - Tick the box and agree: the same question is sent again without retyping.
   - Choose "Not now" instead: the text stays in the field.
5. Turn off the network and send. You get "can't reach" with **Try again**, the text is back in the
   field, and the history is intact.
6. Go back to the list. The conversation is there with its title. Swipe it away and it's gone.
7. With `ai_chat` off, there is no Coach tab.
8. Check light and dark, the largest font size, and VoiceOver/TalkBack ("You said …",
   "Coach said …", and the typing indicator being announced).

## Acceptance criteria

| Criterion | Status |
|---|---|
| With `ai_chat` on: start a chat, ask, see a typing indicator, read the reply, on both platforms | Met in code; not run on a device (see below) |
| Chips come from `GET /chat/suggestions`, send `suggestion_id`, and work without consent | Met: a chip sends empty text plus its id, and `needsConsent` is only ever true for typed questions |
| List newest first; opening one shows history; delete removes it | Met: the order is the server's; delete is optimistic and restored if refused |
| With `ai_chat` off or unread, the entry point is hidden | Met: the same gate rule as Budget/Goals, and someone already on the tab keeps it until the payload says no |
| `409 consent_required` opens the consent screen; resend without retyping | Met: `agree()` then `retry()` |
| 503 / 429 / network errors: own message, typed text kept, conversation kept | Met: `chat_error_unavailable`, `chat_error_limit` and `chat_error_network`; `ChatThread.failed` puts the text back |
| Learning card while learning; factual questions still answered | Met: driven by the reply's `learning` |
| `disclaimer: true` shows the note; header link opens the disclaimer | Met |
| No figure computed on the device | Met: reply text is rendered as received |
| Every new string in i18n | Met |
| ktlint, Android build and tests, iOS `xcodebuild` | Met (see Verification) |

## Verification

- `./gradlew ktlintCheck`: passed.
- `:sharedLogic:testAndroidHostTest`: 765 tests, 0 failures.
- `:androidApp:testDebugUnitTest`: 355 tests, 0 failures.
- `:androidApp:assembleDebug`: BUILD SUCCESSFUL.
- iOS `xcodebuild` (simulator SDK, Debug): BUILD SUCCEEDED. The only warning in a touched file is
  the existing "default will never be executed" in `RootView.swift`'s destination switch.
- Not run on a simulator or device. The UI-standards checks (font scale, small and large phones,
  landscape, light and dark, screen readers) still need the manual pass in **How to test**.

## Deviations / decisions

- **No mockups,** so the screens are built in the app's existing style: the Field list, white
  sheets and Mint components.
- **The 503 code is `ai_processing_unavailable`,** which the backend sends. The ticket said
  `tier_not_confirmed`. It maps to the existing `ImportUnavailable`, and the coach shows its own
  words for it.
- **The consent step is a shared component** on each platform, rather than a second copy.
- **Swift doesn't edit Kotlin state.** The iOS view model replaces its `ChatThreadState` with
  whatever `ChatThread` returns.
- **Return in the iOS field sends.** A field that grows to five lines would otherwise put a line
  break into the text. Typing Return at the end sends instead; pasted line breaks are kept.

## Open questions / follow-ups

- finai-api is suspended, and in production `ai_chat` is `coming_soon`. The tab won't appear for
  anyone until both change.
- Backend PR #90 (learning gating) has merged but isn't deployed while finai-api is suspended.
  Until it is, replies carry no `learning`, so the learning card never shows.
