# Release checklist

Run through every item before tagging the release. Nothing ships with an
unchecked box, and the order below is deliberate.

Before the tag:

1. All CI jobs green on the release branch.
2. Changelog reviewed and dated.
3. Version bumped in every manifest.

After the tag:

1. Store listing screenshots refreshed.
2. Staged rollout opened at five percent.
3. Crash dashboard watched for one hour.

Useful reminders:

- The rollback script lives next to the deploy script.
- Release notes are written for users, not for developers.
- A red dashboard always beats a silent regression.
