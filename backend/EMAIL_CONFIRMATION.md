# CP-08 email confirmation

The user chose **demo accounts for now**. Existing citizen and official accounts remain usable without an SMTP provider. No Brevo account, SMTP credential, paid plan or billing account was created. Public registration is still a partial checkpoint.

## Implemented Android flow

1. Create account sends name, email and password to Supabase Auth over HTTPS. When Auth requires confirmation, the app shows the pending email and a six-digit code field. It does not create a signed-in session at this point.
2. Verify email sends email, token and type=email to auth/v1/verify. Only a successful Auth session response signs the user in. Invalid or expired codes leave the account signed out and display the backend error.
3. Resend code uses auth/v1/resend with type=signup. The app saves a 60-second cooldown before requesting delivery and honors a numeric Retry-After response on HTTP 429. Server limits remain authoritative.
4. The pending email and retry time survive restart in encrypted private storage. Passwords and confirmation codes are never persisted. A successful sign-in/verification or choosing another email clears the pending confirmation.
5. A password login rejected with email_not_confirmed opens confirmation. The sign-in screen also offers Confirm an existing account, without requesting another email until the user selects Resend code.

Auth errors preserve symbolic error_code before numeric HTTP code, so otp_expired is displayed instead of only 403. Database error codes continue to work.

This app is prepared for a six-digit signup code. **The hosted email template has not been changed in this batch.** Before testing real signup, publish [confirmation.html](../supabase/templates/confirmation.html) as the Supabase Confirm signup template, with a subject such as Confirm your PinPoint email. Keep email confirmation enabled and the email OTP length at six digits. The template uses the provider's Token variable and requires no browser redirect. See [Supabase email templates](https://supabase.com/docs/guides/auth/auth-email-templates), [verification](https://supabase.com/docs/reference/javascript/auth-verifyotp) and [resend](https://supabase.com/docs/reference/javascript/auth-resend).

## Free email recommendation, deferred

For classmates outside the Supabase organization, use **Brevo Free SMTP** when the team is ready to create and verify its own account. Its documented Free allowance is 300 emails a day, includes transactional email and requires no credit card. Account approval and provider sending limits still apply. [Brevo Free plan](https://help.brevo.com/hc/en-us/articles/208589409-About-Brevo-s-pricing-plans).

Supabase's built-in sender is restricted to organization-team email addresses and currently limited to two messages per hour. It is suitable for a restricted team test, but does not establish public registration. [Supabase SMTP limits](https://supabase.com/docs/guides/auth/auth-smtp).

Prepare these values in Supabase Authentication's custom SMTP settings when the provider is ready:

| Setting | Value |
| --- | --- |
| Sender name | PinPoint Citizen |
| Sender email | The verified sender in the team's Brevo account |
| Host | smtp-relay.brevo.com |
| Port | 587, STARTTLS |
| Username | Copy the SMTP Login from Brevo; it may differ from the account login |
| Password | A Brevo SMTP key, not an API key or account password |

Store SMTP credentials only in Supabase's server-side SMTP configuration. Do not add them to the Android app, local.properties, Git, test fixtures or chat. Supabase generates the confirmation content; a second Brevo template is not needed. [Brevo SMTP setup](https://help.brevo.com/hc/en-us/articles/7924908994450-Send-transactional-emails-using-Brevo-SMTP), [Supabase integration](https://developers.brevo.com/docs/supabase-smtp-integration).

Use a sender domain the team controls if available. Brevo documents rewriting unauthenticated/free-domain senders to its compliant domain; actual inbox delivery must still be tested. Do not buy a domain or attach a paid plan to finish this group prototype. [Sender requirements](https://help.brevo.com/hc/en-us/articles/14925263522578-Comply-with-Gmail-Yahoo-and-Microsoft-s-requirements-for-email-senders).

## Verification and remaining acceptance

EmailConfirmationTest passed six emulator checks: pending signup restoration without a session/password, successful-session boundary and restoration, server resend cooldown/restoration, unconfirmed login routing state, code-screen validation/busy controls, and rejection of an invalid code by live hosted Auth. Fake responses cover successful verification without consuming or fabricating real email delivery. These tests do not establish receipt of a real code.

After configuring SMTP and publishing the template, use a real address outside the organization. Confirm receipt, verify the code, restart the app, sign out/in, retry an invalid/expired code and check resend throttling. Confirm the new account is a citizen, with no official assignment. Record the exact errors without including passwords, OTPs, tokens or SMTP keys. Only then close the public-signup part of CP-08.

## Why the email shows a link instead of a code (and the fix)

The app already asks for a six-digit code. If the email contains a clickable link that does not open, the hosted project is still using Supabase's default templates (they print `{{ .ConfirmationURL }}`). Publish both repo templates in the Supabase dashboard (Authentication, Email Templates): **Confirm sign up** gets [confirmation.html](../supabase/templates/confirmation.html) (subject: `Your PinPoint confirmation code`) and **Reset Password** gets [recovery.html](../supabase/templates/recovery.html) (subject: `Your PinPoint password reset code`). Both use `{{ .Token }}`. Then register again; the new email shows the code and the app's confirmation screen accepts it.
