# CP-14 hosted notification pagination

Status: in progress; planning/read-only inspection complete. Base main is 708b466 (PR #1 merged). Working branch: backend/notification-pagination-validation.

## Scope

Validate the existing Kotlin repository/feed against hosted Supabase, then exercise the ordinary phone UI. The user authorized unattended project-specific phone/PC tests and synthetic hosted data. Remain on the existing Free project and demo accounts.

1. Capture the demo citizen's existing notification baseline; prepare one clearly labeled synthetic report and 123 labeled notice fixtures without push-outbox entries.
2. Verify actual authenticated REST ownership and read-state boundaries. A wrong-owner mark-read RPC may return success with zero rows affected; verify state rather than assuming an exception is required.
3. Run the real Kotlin pageNotifications and NotificationFeed with two loaded pages. Insert an 11-notice burst from the host; compare refreshed IDs with independent database ordering, then drain older pages and check completeness/uniqueness and equal-timestamp cursors.
4. Exercise mark-read and notification-to-report navigation in the ordinary app on the phone, including persistence after reopening.
5. Record exact outcomes, preserve unrelated data, restore temporary device settings, and update the tracker/handoff. Keep raw IDs/credentials/device evidence outside Git.

## Evidence so far

- Existing page size is 50 plus one look-ahead row, ordered by created_at descending and UUID descending.
- Existing RLS allows only the notice owner to select. mark_notification_read updates only rows owned by auth.uid().
- Status updates explicitly add push-outbox rows; fixture-only notification inserts must not add any. The captured baseline contains 15 citizen notices, zero matching CP-14 report/notices, and no custom notification-table trigger. No CP-14 hosted fixture has been created yet.
- Existing local regression/UI tests remain baseline evidence, not a hosted-pagination pass.

## Resume

Continue with baseline capture and guarded fixture setup. The private, ready-to-copy continuation prompt is saved in the user's Documents folder as PinPoint-Codex-to-Claude-CP14-Handoff.md; it names local credential/evidence paths without their contents. The in-session usage monitor is account-wide, and its latest sanitized snapshot stays outside Git.
