# Auth setup — email, Google, Apple, and linking

What has to be switched on outside the code for sign-in to work (PRD §9,
2026-09-15). None of it is in either repo, and every item fails in a way that
looks like an app bug, so check here first.

Never paste a key or secret into a ticket, PR, chat or commit. Names only.

## Supabase dashboard

**Authentication → Sign In / Providers → Email**
- **Enable Email provider:** on. Off, and every email call fails as
  "This build isn't set up for that sign-in method yet."
- **Confirm email:** on. Off, and signup signs the user in straight away with
  an address nobody has proven they own. Linking by verified email then trusts
  addresses that were never verified.
- **Email OTP length:** 6. The code cells and the Verify button expect six
  digits (`OnboardingUiState.CODE_LENGTH`, `OnboardingViewModel.codeLength`).
- **Minimum password length:** 8, matching `Credentials.MIN_PASSWORD_LENGTH`.
- **Leaked password protection:** on, where the plan allows it. It shows up as
  "Choose a stronger password…".

**Authentication → Emails → Templates**
- **Confirm signup** and **Reset password** must include `{{ .Token }}`. The
  apps take a typed code, not a link. A template with only
  `{{ .ConfirmationURL }}` sends an email the app cannot use.

**Authentication → Emails → SMTP settings**
- Configure a real sender **before anyone outside the team signs up**.
  Supabase's built-in mailer only delivers to project team members and is
  heavily rate-limited. Anyone else gets `email_address_not_authorized`, which
  the app shows as the sign-in method being unavailable.

**Authentication → Settings (or Sign In / Providers)**
- **Allow manual linking:** on. Linking Google or Apple to an existing account
  (`linkIdentityWithIdToken`) is refused without it.

**Google** and **Apple** providers are as set up for ticket 2.3 (#16); nothing
changes there.

## Render (`finai-shared` environment group)

- `SUPABASE_SERVICE_ROLE_KEY` — used only by
  `app/services/supabase_admin.py` to delete the empty Supabase account when a
  sign-in method is linked. Without it, `POST /me/link` answers
  `502 orphan_auth_cleanup_failed`. **Backend only: it must never appear in this
  repo, a client build, or a log.**

## Database

- Migration `d91e4b7c2a15` (adds `email` to `auth_provider`) must be applied to
  production **before** the backend that uses it is deployed. An email sign-in
  against the old enum fails on the first `/me`.

## Still to do before launch

- **"Sign-in method added" notification.** `app/services/notifications.py`
  only logs today. Email linking is safe because the account owner hears about
  a new method, so a real sender has to exist before launch.
- **Orphan cleanup job.** The app removes the empty account when linking
  finishes. If someone abandons the flow, it stays. A periodic sweep of
  phone-less accounts with no data, older than a day, closes that.
