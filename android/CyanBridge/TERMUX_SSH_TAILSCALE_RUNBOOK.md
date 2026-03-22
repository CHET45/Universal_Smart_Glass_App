# CyanBridge Termux SSH + Tailscale Runbook

This runbook is for operating the phone-hosted backend remotely from your PC, without repeated manual file transfers.

## 1) One-time setup on Termux phone

```bash
pkg update -y && pkg upgrade -y
pkg install -y openssh rsync python curl tar
passwd

mkdir -p ~/.ssh
chmod 700 ~/.ssh
```

Add your PC public key to `~/.ssh/authorized_keys` and lock permissions:

```bash
printf '%s\n' 'ssh-ed25519 <YOUR_PUBLIC_KEY> cyanbridge-termux' > ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

Start SSH daemon:

```bash
pkill sshd || true
sshd
```

## 2) One-time setup on PC

Create dedicated key:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/cyanbridge_termux -C "cyanbridge-termux" -N ""
```

Test SSH over Tailscale (Termux uses port 8022):

```bash
ssh -i ~/.ssh/cyanbridge_termux -p 8022 u0_a258@<PHONE_TAILSCALE_IP>
```

### Tailscale quick connect notes (current setup)

- Termux user: `u0_a258`
- SSH key on PC: `~/.ssh/cyanbridge_termux`
- Current phone Tailscale IP (may change): `100.93.63.113`

Direct connect command:

```bash
ssh -i ~/.ssh/cyanbridge_termux -p 8022 u0_a258@100.93.63.113
```

If SSH fails, run this on phone in Termux:

```bash
pkill sshd || true
sshd
cat ~/.ssh/authorized_keys
```

Then retest from PC with timeout:

```bash
ssh -o ConnectTimeout=8 -i ~/.ssh/cyanbridge_termux -p 8022 u0_a258@100.93.63.113 'echo ok'
```

## 3) Deploy/update server files from PC

```bash
rsync -avz --delete \
  --exclude ".venv/" \
  --exclude "data/" \
  --exclude "logs/" \
  --exclude "run/" \
  --exclude "backups/" \
  -e "ssh -i ~/.ssh/cyanbridge_termux -p 8022" \
  /home/fertroll10/Documents/ML/HeyCyanSmartGlassesSDK/android/CyanBridge/_local_termux_server/ \
  u0_a258@<PHONE_TAILSCALE_IP>:~/cyanbridge_termux_server/_local_termux_server/
```

## 4) Install and run on phone (remote from PC)

```bash
ssh -i ~/.ssh/cyanbridge_termux -p 8022 u0_a258@<PHONE_TAILSCALE_IP> \
  'cd ~/cyanbridge_termux_server/_local_termux_server && chmod +x *.sh termux-boot/*.sh && ./install-termux.sh && ./start-server.sh && ./status-server.sh'
```

## 5) Useful remote operations

Status:

```bash
ssh -i ~/.ssh/cyanbridge_termux -p 8022 u0_a258@<PHONE_TAILSCALE_IP> \
  'cd ~/cyanbridge_termux_server/_local_termux_server && ./status-server.sh'
```

Restart:

```bash
ssh -i ~/.ssh/cyanbridge_termux -p 8022 u0_a258@<PHONE_TAILSCALE_IP> \
  'cd ~/cyanbridge_termux_server/_local_termux_server && ./restart-server.sh'
```

Logs:

```bash
ssh -i ~/.ssh/cyanbridge_termux -p 8022 u0_a258@<PHONE_TAILSCALE_IP> \
  'cd ~/cyanbridge_termux_server/_local_termux_server && ./logs-server.sh 200'
```

Health from PC:

```bash
curl -fsS http://<PHONE_TAILSCALE_IP>:8787/health
curl -fsS http://<PHONE_TAILSCALE_IP>:8787/capabilities
```

## 6) Token auth

Server token is controlled by `SHARED_TOKEN` in:

`~/cyanbridge_termux_server/_local_termux_server/.env`

When set, app clients must send `Authorization: Bearer <token>`.

## 7) Boot auto-start

Install Termux:Boot app, then on phone:

```bash
mkdir -p ~/.termux/boot
cp ~/cyanbridge_termux_server/_local_termux_server/termux-boot/start-on-boot.sh ~/.termux/boot/cyanbridge-termux-server.sh
chmod +x ~/.termux/boot/cyanbridge-termux-server.sh
```

Reboot phone and check:

```bash
cd ~/cyanbridge_termux_server/_local_termux_server
./status-server.sh
```

## 8) Public internet access for end users (not same LAN/Tailnet)

Use Cloudflare quick tunnel from Termux:

```bash
pkg install -y cloudflared
cd ~/cyanbridge_termux_server/_local_termux_server
./start-public-tunnel.sh
./tunnel-status.sh
```

Set the app relay base URL to the printed `https://...trycloudflare.com` URL.

Stop tunnel:

```bash
./stop-public-tunnel.sh
```

Important security note:
- If `SHARED_TOKEN` is set, app clients must send it.
- If your app is public and users cannot configure token, leave `SHARED_TOKEN=` empty.
- For production, move to a proper hosted backend with per-user auth/rate limits.

### Temporary mode while DNS/nameservers propagate

Use Cloudflare quick tunnel and put the generated URL in app settings.

On phone:

```bash
cd ~/cyanbridge_termux_server/_local_termux_server
./stop-named-tunnel.sh || true
./start-public-tunnel.sh
./tunnel-status.sh
```

From PC, verify quickly:

```bash
curl -fsS https://<TRYCLOUDFLARE_URL>/health
```

Current temporary URL (replace if it rotates):

`https://ten-nature-optimize-inventory.trycloudflare.com`

## 9) Stable Cloudflare named tunnel (recommended)

If Cloudflare gives you a token command like:

```bash
cloudflared service install <TOKEN>
```

On Termux, `service install` is not supported. Use token-run mode instead.

Set token in `.env`:

```bash
cd ~/cyanbridge_termux_server/_local_termux_server
grep -q '^CF_TUNNEL_TOKEN=' .env && \
  sed -i 's|^CF_TUNNEL_TOKEN=.*|CF_TUNNEL_TOKEN=<TOKEN>|' .env || \
  printf '\nCF_TUNNEL_TOKEN=<TOKEN>\n' >> .env
```

Start/stop/status:

```bash
./start-named-tunnel.sh
./named-tunnel-status.sh
./stop-named-tunnel.sh
```

Use your configured Cloudflare hostname (from Zero Trust dashboard) as app relay base URL.

## 10) Real browser charges + per-user usage token (Stripe)

The backend supports real subscription charges through Stripe Checkout.

Set these values in `~/cyanbridge_termux_server/_local_termux_server/.env` on phone:

```bash
STRIPE_SECRET_KEY=sk_live_or_test_...
STRIPE_PRICE_MONTHLY_ID=price_...
STRIPE_PRICE_YEARLY_ID=price_...
```

Then restart backend:

```bash
cd ~/cyanbridge_termux_server/_local_termux_server
./restart-server.sh
```

Behavior:
- `/web-subscribe` uses Stripe when those vars are set.
- The callback stores an `api_token` in the app.
- AI usage quota endpoints are tracked per `api_token` when the app sends bearer auth.

Useful test call (should return token):

```bash
curl -fsS -X POST https://<PUBLIC_BASE_URL>/auth/register \
  -H "Content-Type: application/json" \
  --data '{"email":"you@example.com"}'
```
