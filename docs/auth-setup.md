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
- Paste the bodies from `docs/email-templates/` — `confirm-signup.html` and
  `reset-password.html`. Subjects: "Your FinAI code: {{ .Token }}" and
  "Reset your FinAI password".

**Authentication → Emails → SMTP settings — Resend**

Supabase creates, emails and checks the codes itself; Resend only delivers
them. Supabase's built-in mailer is for testing: it delivers only to project
team members and is heavily rate-limited, so anyone else gets
`email_address_not_authorized`, which the app shows as the sign-in method being
unavailable.

1. **Resend → Domains → Add domain.** Use a subdomain you control (e.g.
   `mail.<your-domain>`), add the DNS records Resend shows (SPF and DKIM), and
   wait for **Verified**. Until then Resend only delivers to the address the
   Resend account was created with.
2. **Resend → API Keys → Create.** Permission **Sending access**, restricted to
   that domain. Copy it straight into step 3 — do not paste it anywhere else.
3. **Supabase → Authentication → Emails → SMTP settings → Enable custom SMTP:**
   - Sender email: `no-reply@mail.<your-domain>` (must be on the verified domain)
   - Sender name: `FinAI`
   - Host: `smtp.resend.com`
   - Port: `465`
   - Username: `resend`
   - Password: the API key from step 2
4. **Supabase → Authentication → Rate Limits → emails sent per hour.** Custom
   SMTP starts low; raise it to what launch needs (Resend's plan caps it too).
5. Test: create an account in the app with an address on a real inbox, and
   check the code arrives and verifies; then do Forgot password.

Resend's Supabase integration (Resend → Integrations) can fill step 3 in for
you; the result must match the values above.

**Authentication → Settings (or Sign In / Providers)**
- **Allow manual linking:** on. Linking Google or Apple to an existing account
  (`linkIdentityWithIdToken`) is refused without it.

**Google** and **Apple** providers are as set up for ticket 2.3 (#16); nothing
changes there.

## Render (`finai-shared` environment group)

- `SUPABASE_SERVICE_ROLE_KEY` — a **secret key** (`sb_secret_…`, Project
  Settings → API Keys → Secret keys) or the legacy `service_role` key; the
  backend handles either. Used only by
  `app/services/supabase_admin.py` to delete the empty Supabase account when a
  sign-in method is linked. Without it, `POST /me/link` answers
  `502 orphan_auth_cleanup_failed`. **Backend only: it must never appear in this
  repo, a client build, or a log.**

- `RESEND_API_KEY` and `NOTIFICATION_FROM` — the backend's own emails, today
  only the "sign-in method added" alert. The key can be the same Resend key
  Supabase uses, or a second one with sending access to the same domain.
  `NOTIFICATION_FROM` looks like `FinAI <no-reply@mail.<your-domain>>` and must
  be on the verified domain. Either missing, and alerts are logged as not sent.

## Database

- Migration `d91e4b7c2a15` (adds `email` to `auth_provider`) must be applied to
  production **before** the backend that uses it is deployed. An email sign-in
  against the old enum fails on the first `/me`.

## Still to do before launch

- **Alert sender configured in production.** Email linking is safe because the
  account owner hears about a new method, so `RESEND_API_KEY` and
  `NOTIFICATION_FROM` must be set in Render before launch.
- **Orphan cleanup job.** The app removes the empty account when linking
  finishes. If someone abandons the flow, it stays. A periodic sweep of
  phone-less accounts with no data, older than a day, closes that.
