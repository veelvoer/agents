# Agents

Run Claude agents on your own server and watch them from your desktop and phone.

- Pick any Claude model per agent, give it instructions and memory, and decide if it may start subagents.
- See every agent, whether it is working, and its tokens and cost.
- Runs on **your Claude subscription**: the server drives the official `claude` CLI that you log in to once.
- Small and fast: the server is one static Rust program (about 1 MB). The apps are Electron (Linux) and Kotlin (Android).
- Any agent can **repeat by itself**: open it, press Repeat, and choose when (every day, weekdays, every N minutes, once, or by web link). It runs on the server even when your apps are closed and sends you the result. You can also just tell an agent "every morning at 8 tell me the weather".
- Agents can **live on their own**: switch on "Lives on its own" and the agent wakes up by itself, thinks about its purpose (its instructions), does things with its tools, keeps a journal, builds and develops things in its own workspace, messages you only when it matters, and chooses when to wake next. You don't schedule anything: it reads its instructions and works out the timing itself.
- Phone notifications come from the Android app itself, instantly. Telegram, ntfy and web hooks are optional extras.
- Talk to your agents from Telegram too (optional).
- Colours follow your computer (GTK/KDE/GNOME theme on Linux, Material You on Android), or use the warm clay colours. Light, dark, text size and reduced motion are in Settings.
- Updates come from GitHub: the desktop app and the Android app check for new releases, and the server can update itself from Settings.
- Built to stay fast over time: chats are stored one file per agent and read in pages, the apps wait on the server instead of polling, and answers survive a phone going to sleep.

## 1. Set up the server

On a Linux server (x86_64 or arm64) with systemd:

```bash
curl -fsSL https://raw.githubusercontent.com/veelvoer/agents/main/install.sh | bash
```

It installs the server as a service, makes a secret key, and sets up HTTPS (a free `<ip>.sslip.io` address through Caddy, or your own with `--domain agents.example.com`). At the end it prints one line like `https://1-2-3-4.sslip.io#KEY`.

Then log in to Claude on the server once: install Claude Code and run `claude`.

Options: `bash -s -- --domain agents.example.com`, `--no-https`, `--uninstall`. Later, the apps can update the server for you from Settings. Run the command again to update; your key and agents are kept.

## 2. Install the apps

Download from the [latest release](https://github.com/veelvoer/agents/releases/latest):

| | File |
|---|---|
| Linux | `Agents-…-linux-x86_64.AppImage` (`chmod +x` it, then run) |
| Android | `Agents-….apk` (allow installs from this source) |

Open the app and paste the line the installer printed.

## Telegram (optional)

Create a bot with @BotFather and add `TELEGRAM_TOKEN=<token>` to `~/.config/agents/env` (or `/etc/agents/env` when installed as root), then restart the service. Text the bot to talk to agent 1, or `/3 hello` to talk to agent 3.

## Tools and subagents

Give an agent an MCP config file path (on the server) to let it use tools such as a headless browser. The "can start subagents" switch controls the `Task` tool.

## Safety

The key is the only lock: anyone with it can run your agents. Keep it private, use HTTPS, and never post it. Agents run `claude` as the user who installed the service.

## Build it yourself

```bash
cargo build --release                         # server
cd desktop && npm install && npm start        # desktop app
cd android && ./gradlew assembleRelease       # Android app
```

MIT licensed.
