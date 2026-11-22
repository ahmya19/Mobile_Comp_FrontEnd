# Backend continuous integration

The [Backend checks workflow](../.github/workflows/backend-checks.yml) runs on every pull request and on backend/Supabase/workflow changes pushed to main. It can also be dispatched manually after reaching the default branch. The job installs the lockfile dependencies on Node.js 24, runs the isolated backend suite (including Python runner checks and PGlite PostgreSQL scenarios), and type-checks both Edge Functions with the lockfile's Deno binary.

The workflow uses the standard Ubuntu 24.04 runner, read-only repository permissions, commit-pinned official checkout/setup actions and no persisted checkout credential. Older in-progress runs for the same ref are cancelled; a job is limited to ten minutes. It needs no project credentials, private configuration, hosted database, Android SDK or connected device. It does not deploy or run the hosted fixture scripts. An isolated test pass covers the local contracts; hosted integration and whole-app acceptance retain their separate evidence.

This repository is public. Standard GitHub-hosted runners in public repositories are free under [GitHub's Actions billing rules](https://docs.github.com/en/actions/concepts/billing-and-usage). No artifact uploads or dependency caches are configured. Recheck cost rules before moving to a private repository or choosing another runner class.

For a failing pull request, open its Checks tab or run `gh pr checks <number>`, then inspect the failing run with `gh run view <run-id> --log-failed`. Preserve the exact error and reproduce the failing command from backend locally. A workflow check does not configure branch protection: requiring this status for merges remains a repository-owner setting.
