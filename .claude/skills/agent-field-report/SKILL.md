---
name: agent-field-report
description: Use at the end of every substantive task in a Simple Agility repository (a build, an operation, a piece of content, a research question) to file one structured field report through the Wikantik knowledge MCP tool file_agent_report, saying what context helped, what was missing or wrong, and where a tool got in the way.
version: 1
---

# Agent field report

The suite owner learns how agents use the wiki and the products' MCP tools from these reports. They are
read in a weekly digest, next to counts of every MCP call. One honest report is worth more than a polished
one: what was missing and what got in the way matter most.

The contract is `SimpleAgilityAgentActivity` on the wiki.

## When to file

- At the end of every substantive task: something built, operated, written or researched.
- Not for a one-line lookup or a question answered from memory.
- One report per task. If a session does several tasks, file one for each.
- If you stop without finishing, still file it, with `outcome: partial` or `abandoned`.

## How to file

Call `file_agent_report` on the `wikantik-knowledge` MCP server. Do not supply who you are: the server
records your client name, session and key owner itself.

| Field | What to put |
|---|---|
| `workspace` | the repository or project you worked in, e.g. `jspwiki`, `blogger-agent` |
| `kind_of_work` | `operations`, `content`, `build`, `research` or `other` |
| `products` | every product the task touched: `wikantik`, `pressroom`, `jakemon`, `stockroom`, `backoffice` |
| `skills` | each skill you used, as `name@version` (the `version:` line of its `SKILL.md`) |
| `window_start`, `window_end` | ISO-8601 times the task started and ended, as near as you know them |
| `task` | one line, at most 300 characters: what you were asked to do |
| `outcome` | `completed`, `partial` or `abandoned` |
| `context_used` | each source you relied on: `{source: wiki\|blog\|repo\|web, ref: page slug, URL or path, helped: yes\|partly\|no}` |
| `context_missing` | at most 1000 characters: what you needed and did not find, or found and it was wrong or stale |
| `friction` | each place a tool got in the way: `{product, tool, what (at most 500 characters), blocking: true if you could not finish because of it}` |
| `notes` | at most 2000 characters: anything else the owner should know |

## Rules

- Be specific. "The wiki page X said Y, but the code does Z" is useful; "docs could be better" is not.
- Report the wiki page that would have helped if it existed: name it in `context_missing`.
- Never put secrets, tokens, passwords, personal data or customer content in a report.
- Do not file work requests or change anything because of what you report. The owner reviews the digest
  and decides what becomes work.
- If the call is refused, fix the field it names and call again. If the tool is unavailable, say so in
  your final message to the user instead.

## Example

```json
{
  "workspace": "jspwiki",
  "kind_of_work": "build",
  "products": ["wikantik", "jakemon"],
  "skills": ["agent-field-report@1"],
  "window_start": "2026-11-26T09:10:00Z",
  "window_end": "2026-11-26T11:40:00Z",
  "task": "Make the analytics proxy speak HTTP/1.1 so Umami answers it",
  "outcome": "completed",
  "context_used": [
    {"source": "wiki", "ref": "SimpleAgilityBackOfficeSuite", "helped": "yes"},
    {"source": "repo", "ref": "wikantik-http/src/main/java/com/wikantik/http/analytics/AnalyticsProxyServlet.java", "helped": "yes"}
  ],
  "context_missing": "No page says that Umami's Node server drops HTTP/2 upgrade requests; found it by curl.",
  "friction": [
    {"product": "wikantik", "tool": "read_page", "what": "A 49 KB page cannot be edited without resending all of it.", "blocking": false}
  ],
  "notes": ""
}
```
