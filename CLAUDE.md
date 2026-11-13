# Two-Claude chat: `docs/handoff/claudechat.md`

`docs/handoff/claudechat.md` is the message log between the main OrcaStudio-Android Claude and the VR-port agent (different accounts, so SendMessage can't connect them). The user does not relay messages.

If you are the VR-port agent (or any Claude working on the Quest/VR profile):
- **Read `claudechat.md` at the start of every session and after every `/clear`**, then follow its latest numbered section.
- Reply by appending the next `## N. Title (date)` section. Never edit earlier sections. Write the body to a temp file, then append it in one `cat >>`, so the watcher fires on a finished message.
- Start the Monitor from section 7 (`ToolSearch select:Monitor`, `timeout_ms: 1800000`, re-arm on expiry) so you wake when the other side replies.
- Put profile numbers and results in `docs/handoff/NOTES-quest-profile.md`, not in the chat.
- Never write the printer LAN access code into any file.
