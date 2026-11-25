#!/usr/bin/env bash
# Agents server installer. Run this on your server:
#   curl -fsSL https://raw.githubusercontent.com/veelvoer/agents/main/install.sh | bash
# Options (after "bash -s --"):  --domain <name>  use your own domain for HTTPS
#                                --no-https       skip HTTPS, listen on port 7777 (plain HTTP)
#                                --no-browser     do not set up the web browser for agents
#                                --uninstall      remove the service (your agents file is kept)
# It installs one small program, a service that starts at boot, and (if you want) HTTPS.
set -euo pipefail
REPO="${AGENTS_REPO:-veelvoer/agents}"
VERSION="${AGENTS_VERSION:-latest}"
PORT="${AGENTS_PORT:-7777}"
CFG="${XDG_CONFIG_HOME:-$HOME/.config}/agents"
ENVF="$CFG/env"
DOMAIN=""; HTTPS=1; UNINSTALL=0; BROWSER=1
while [ $# -gt 0 ]; do case "$1" in
  --domain) DOMAIN="${2:-}"; shift 2;; --no-https) HTTPS=0; shift;; --no-browser) BROWSER=0; shift;; --uninstall) UNINSTALL=1; shift;;
  *) echo "Unknown option: $1" >&2; exit 2;; esac; done

say() { printf '\n  \033[1m%s\033[0m\n' "$*"; }
ok()  { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn(){ printf '  \033[33m!\033[0m %s\n' "$*"; }
die() { printf '\n  \033[31m✗ %s\033[0m\n\n' "$*" >&2; exit 1; }

[ "$(uname -s)" = "Linux" ] || die "This installer is for Linux servers."
command -v curl >/dev/null || die "Please install curl first (for example: sudo apt install curl)."
command -v systemctl >/dev/null || die "This installer needs systemd."
case "$(uname -m)" in x86_64|amd64) ARCH=x86_64;; aarch64|arm64) ARCH=aarch64;; *) die "Unsupported processor: $(uname -m)";; esac

SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"
# a plain ssh or tool session may not know where the user systemd lives
[ -z "${XDG_RUNTIME_DIR:-}" ] && [ -d "/run/user/$(id -u)" ] && export XDG_RUNTIME_DIR="/run/user/$(id -u)"
can_sudo() {   # root, passwordless sudo, or sudo with a password typed on the terminal
  [ "$(id -u)" -eq 0 ] && return 0
  command -v sudo >/dev/null || return 1
  sudo -n true 2>/dev/null && return 0
  if (: </dev/tty) 2>/dev/null; then echo "  I need your password once to set up HTTPS (sudo)." >&2; sudo -v </dev/tty 2>/dev/null && return 0; fi
  return 1
}
if [ "$(id -u)" -eq 0 ]; then
  BIN=/usr/local/bin/agents-server; UNIT=/etc/systemd/system/agents-server.service; SC="systemctl"; WANTED=multi-user.target
  CFG=/etc/agents; ENVF="$CFG/env"
else
  BIN="$HOME/.local/bin/agents-server"; UNIT="$HOME/.config/systemd/user/agents-server.service"; SC="systemctl --user"; WANTED=default.target
fi

if [ "$UNINSTALL" = 1 ]; then
  $SC disable --now agents-server 2>/dev/null || true; rm -f "$UNIT" "$BIN"; $SC daemon-reload 2>/dev/null || true
  ok "Removed the service and program. Your settings and agents are still in $CFG"; exit 0
fi

say "Installing the Agents server"
if ! command -v claude >/dev/null; then
  warn "Claude Code is not installed on this server. Agents use it to run on your Claude subscription."
  warn "Install it (npm install -g @anthropic-ai/claude-code), then run: claude   and log in. Then come back to the apps."
fi

# 1. program
if [ "$VERSION" = "latest" ]; then URL="https://github.com/$REPO/releases/latest/download/agents-server-linux-$ARCH"
else URL="https://github.com/$REPO/releases/download/$VERSION/agents-server-linux-$ARCH"; fi
mkdir -p "$(dirname "$BIN")"
TMP="$(mktemp)"; trap 'rm -f "$TMP"' EXIT
curl -fsSL "$URL" -o "$TMP" || die "Could not download the server from $URL. Check this server's internet connection."
install -m 755 "$TMP" "$BIN"; ok "Program installed: $BIN"

# 2. settings and key (kept when you run this again, so your apps stay connected)
mkdir -p "$CFG"; chmod 700 "$CFG"
if [ -f "$ENVF" ] && grep -q '^AGENTS_TOKEN=' "$ENVF"; then KEY="$(grep '^AGENTS_TOKEN=' "$ENVF" | cut -d= -f2-)"; ok "Using your existing key"
else KEY="$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 40)"; fi

# 3. address
IP=""; if [ "$HTTPS" = 1 ] && [ -z "$DOMAIN" ]; then
  IP="$(curl -fsS -4 -m 6 https://api.ipify.org 2>/dev/null || curl -fsS -4 -m 6 https://ifconfig.me 2>/dev/null || true)"
  [ -n "$IP" ] && DOMAIN="$(echo "$IP" | tr . -).sslip.io"
fi
USE_CADDY=0
if [ "$HTTPS" = 1 ] && [ -n "$DOMAIN" ]; then
  if can_sudo; then USE_CADDY=1; else warn "I need sudo to set up HTTPS and I can't use it here, so I will skip HTTPS. Re-run as root or with --no-https to choose."; fi
fi
if [ "$USE_CADDY" = 1 ]; then LISTEN="127.0.0.1:$PORT"; else LISTEN="0.0.0.0:$PORT"; fi

umask 077
{
  echo "AGENTS_TOKEN=$KEY"; echo "AGENTS_ADDR=$LISTEN"; echo "AGENTS_DB=$CFG/agents.json"
  grep -E '^(TELEGRAM_TOKEN|PATH)=' "$ENVF" 2>/dev/null || true
} > "$ENVF.new"
grep -q '^PATH=' "$ENVF.new" || echo "PATH=$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin:$(dirname "$(command -v claude 2>/dev/null || echo /usr/bin/claude)")" >> "$ENVF.new"
mv "$ENVF.new" "$ENVF"; ok "Settings saved: $ENVF"

# 4. service
mkdir -p "$(dirname "$UNIT")"
cat > "$UNIT" <<U
[Unit]
Description=Agents server
After=network-online.target

[Service]
EnvironmentFile=$ENVF
ExecStart=$BIN
Restart=always
RestartSec=3

[Install]
WantedBy=$WANTED
U
$SC daemon-reload || die "I cannot reach systemd for this user. Run this command again from a normal login shell (ssh), or as root."
$SC enable --now agents-server >/dev/null 2>&1 || true
$SC restart agents-server || die "The service did not restart. See: $SC status agents-server"
if [ "$(id -u)" -ne 0 ]; then loginctl enable-linger "$USER" 2>/dev/null || warn "Could not make the service start at boot. Run: sudo loginctl enable-linger $USER"; fi
sleep 1
curl -fsS -m 5 "http://127.0.0.1:$PORT/health" >/dev/null || die "The server did not start. See: $SC status agents-server"
ok "The server is running"

# 5. HTTPS with Caddy (free certificate for <ip>.sslip.io, or your own domain)
if [ "$USE_CADDY" = 1 ]; then
  if ! command -v caddy >/dev/null; then
    if command -v apt-get >/dev/null; then $SUDO apt-get install -y caddy >/dev/null 2>&1 || die "Could not install Caddy. Install it from caddyserver.com, then run this again."
    elif command -v dnf >/dev/null; then $SUDO dnf install -y caddy >/dev/null 2>&1 || die "Could not install Caddy. Install it from caddyserver.com, then run this again."
    else die "I can't install Caddy here. Install it from caddyserver.com, then run this again (or use --no-https)."; fi
  fi
  $SUDO mkdir -p /etc/caddy
  printf '# Written by the Agents installer\n%s {\n\treverse_proxy 127.0.0.1:%s {\n\t\tflush_interval -1\n\t\ttransport http {\n\t\t\tresponse_header_timeout 16m\n\t\t}\n\t}\n}\n' "$DOMAIN" "$PORT" | $SUDO tee /etc/caddy/agents.caddy >/dev/null
  $SUDO chmod 644 /etc/caddy/agents.caddy   # Caddy runs as its own user and must be able to read it
  MAIN=/etc/caddy/Caddyfile
  if [ ! -f "$MAIN" ]; then echo "import /etc/caddy/agents.caddy" | $SUDO tee "$MAIN" >/dev/null
  elif ! grep -q 'import /etc/caddy/agents.caddy' "$MAIN"; then $SUDO cp "$MAIN" "$MAIN.bak-agents"; echo "import /etc/caddy/agents.caddy" | $SUDO tee -a "$MAIN" >/dev/null; fi
  if $SUDO caddy validate --config "$MAIN" --adapter caddyfile >/dev/null 2>&1; then
    $SUDO systemctl enable caddy >/dev/null 2>&1 || true; $SUDO systemctl reload caddy 2>/dev/null || $SUDO systemctl restart caddy
    ok "HTTPS is set up for $DOMAIN"
    command -v ufw >/dev/null && $SUDO ufw allow 80,443/tcp >/dev/null 2>&1 || true
    warn "Ports 80 and 443 must be open to the internet (some hosts call this the firewall)."
  else
    $SUDO sed -i '\#import /etc/caddy/agents.caddy#d' "$MAIN"; $SUDO rm -f /etc/caddy/agents.caddy
    warn "Caddy did not accept the settings, so I left HTTPS off. The server answers on port $PORT."; LISTEN="0.0.0.0:$PORT"; USE_CADDY=0
    sed -i "s|^AGENTS_ADDR=.*|AGENTS_ADDR=$LISTEN|" "$ENVF"; $SC restart agents-server
  fi
fi

# 6. a real web browser for agents (Playwright + Chrome/Chromium), optional
if [ "$BROWSER" = 1 ]; then
  BD="$HOME/.local/share/agents-browser"
  if command -v node >/dev/null && command -v npm >/dev/null; then
    mkdir -p "$BD"
    if (cd "$BD" && { [ -f package.json ] || npm init -y >/dev/null 2>&1; } && npm install --no-audit --no-fund --cache "$HOME/.cache/agents-npm" @playwright/mcp@latest >/dev/null 2>&1); then ok "Browser program installed (agents can browse when you switch it on for them)"
    else warn "Could not install the browser program. Agents will work without a browser."; fi
    if ! ls /usr/bin/google-chrome* /usr/bin/chromium* >/dev/null 2>&1; then
      if [ -f "$BD/node_modules/playwright-core/cli.js" ] && (cd "$BD" && node node_modules/playwright-core/cli.js install chromium >/dev/null 2>&1); then ok "Chromium downloaded for the browser"
      else warn "No Chrome or Chromium found. Install one (for example: sudo apt install chromium) so agents can browse."; fi
    fi
  else warn "Node.js is not installed, so agents get no web browser. Install Node.js 18 or newer and run this again."; fi
fi

if [ "$USE_CADDY" = 1 ]; then ADDR="https://$DOMAIN"; else ADDR="http://${IP:-$(hostname -I 2>/dev/null | cut -d' ' -f1)}:$PORT"; fi
say "Done. Open the Agents app and paste this one line (address and key):"
printf '\n      \033[1m%s#%s\033[0m\n\n' "$ADDR" "$KEY"
echo "  Server address: $ADDR"; echo "  Key:            $KEY"
echo "  Keep the key secret. It is saved in $ENVF"
[ "$USE_CADDY" = 0 ] && warn "No HTTPS: the key travels unencrypted. Use a VPN (like Tailscale) or run again without --no-https."
echo; echo "  Update later: run the same command again. Remove: add  --uninstall"
