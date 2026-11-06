# README documentation

- Do not edit the repository-root `README.md`. Put DINA setup and usage documentation in `dina-tak-gpt/README.MD`.

When an LLM edits the README in this directory:

- Include only what is necessary to set up and use the plugin.
- Prefer commands with short comments and minimal supporting text.
- Use Bash for setup and usage commands, not PowerShell.
- Use placeholders for real GCP project IDs in repository configuration and documentation; do not add project IDs to diagnostic log messages.
- Do not add detailed sections about capabilities, architecture, background, or optional features.
- Keep instructions concise and actionable; omit unnecessary explanations.
- Do not include change history or lists of repository modifications in the README.
- Include required changes or additions to the original repository only as actionable setup steps, in the order they must be performed before building, configuring, and using the plugin.

# Recording repository changes

- Maintain `CHANGELOG.md` in this directory for modifications to the original repository outside `dina-tak-gpt/`.
- Use a change log title followed by a simple, flat bullet list in chronological order.
- Include added, modified, and deleted files, including manually supplied libraries. Exclude generated build output and changes within `dina-tak-gpt/` itself.
- Name affected repository-relative paths and briefly describe the actual change and its purpose.
- Record only changes already made. Keep planned changes, setup commands, and detailed explanations out of the change log.
- Update or consolidate existing bullets when revising the same change so the log stays accurate and avoids duplicates.
