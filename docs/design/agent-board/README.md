# Agent board design

Mockups for a mobile-friendly wrapper around A-SH: a herdr agent board as the
home screen, with chat, trace and raw terminal views of each pane.

Open `index.html` in a browser to see every screen side by side. Each screen
also opens on its own and links to the others (board → agent detail →
approval, the + button → new agent, bottom nav → Shell / Activity, the
subtitle → Computers).

## Screens

| File | Screen |
| --- | --- |
| `Main.html` | 1 · Chat with a collapsible work trace |
| `SplitPeek.html` | 2 · Chat over a live terminal sheet |
| `AgentBoard.html` | 3 · herdr agent board (chosen direction) |
| `TermOverlay.html` | 4 · Terminal first, native approval card and composer |
| `AgentDetail.html` | 3a · Agent detail: Chat / Trace / Terminal over one session |
| `Approval.html` | 3b · Approval sheet with per-file diff |
| `NewAgent.html` | 3c · New agent: harness, folder, worktree, first prompt |
| `Activity.html` | 3d · Activity feed across all agents |
| `Shell.html` | 3e · Raw Ghostty shell tab |
| `Pair.html` | 3f · Computers and pairing |

The `.html` files are static renders. Tab switching on agent detail and the
harness picker on new agent are interactive only in the editable canvas
source under `src/` (`.dc.html` files plus `canvas.json`).

## Where the data comes from

- Progress bars: OSC 9;4, already parsed by `OscSideScanner`.
- Repo and agent names: OSC 7 working directory (unwired, see
  `TERMINAL-APP_TODO.md`) and the terminal title.
- Card tails: the last lines of each pane's `ScreenSnapshot`.
- Waiting / approval state: OSC 133 prompt marks, or a per-pane state from
  herdr (running, waiting, done).
- Chat and trace views: a structured event stream per agent over a second SSH
  channel (Claude Code `--output-format stream-json`, the OMP RPC surface,
  Hermes one-shot mode), with the terminal always one tap away.

## Look

Ground `#121314`, surface `#1A1B1E`, text `#ECECEA`, muted `#9A9C9F`.
Mint `#7FD1B9` for done and primary actions, amber `#E6B450` for running,
coral `#F07A6A` for needs-you. IBM Plex Sans for chat, JetBrains Mono for agent
work.
