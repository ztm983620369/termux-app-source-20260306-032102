---
name: session-title
description: Use when the user asks to rename, inspect, verify, or reason about the current conversation title.
---

# Session Title

Use the session title tools for authoritative current-conversation title access:

- Use `get_session_title` to read the current title from Crush session storage.
- Use `set_session_title` to rename the current conversation.

Guidelines:

- If the user asks what the title is, asks you to check it, or challenges a remembered title, call `get_session_title` before answering.
- If the user gives an exact title, use that title exactly.
- If the user asks you to choose a title, keep it short, specific, and on one line.
- Rename only the current conversation.
- Do not rename repeatedly unless the user asks or the conversation topic clearly changes.
- After renaming, continue with the user's task; no extra explanation is needed unless the user asks.
