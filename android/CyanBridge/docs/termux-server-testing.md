# Termux Server Testing Guide

## A. Local host sanity check (before phone copy)

From repo root:

```bash
bash _local_termux_server/start-server.sh
bash _local_termux_server/status-server.sh
bash _local_termux_server/healthcheck.sh
curl -sS http://127.0.0.1:8787/capabilities
bash _local_termux_server/stop-server.sh
```

## B. Copy to spare Android phone (Termux)

Recommended target path in Termux home:

```bash
~/cyanbridge_termux_server
```

You can copy manually (USB/files app) or use helper:

```bash
bash _local_termux_server/copy-to-phone.sh
```

## C. Install and run on phone (Termux)

```bash
cd ~/cyanbridge_termux_server
chmod +x *.sh termux-boot/*.sh
./install-termux.sh
nano .env
./start-server.sh
./status-server.sh
./healthcheck.sh
./print-local-ip.sh
```

Use the IP + port in app config (for example `http://192.168.1.55:8787`).

## D. Wire app to Termux server

1. Open app settings for relay/pro configuration.
2. Enter your server root URL (for example `http://192.168.1.55:8787`).
3. Optional: add your shared token.
4. Save configuration and reopen the chat screen.

## E. App behavior validation

### 1) AI chat / voice / image

- Select a provider path that routes through relay.
- Run text query and image query.
- Expected:
  - If backend available: normal reply.
  - If backend missing (e.g., no gemini/codex CLI): explicit unavailable error.

### 2) Pro verification

- Open Pro subscription screen and trigger refresh.
- Expected with `PRO_VERIFY_MODE=accept_any`:
  - active=true from server for provided purchase token.
- Expected if verifier disabled:
  - clear fallback message to local status.

### 3) Transcription debug HTTP mode

- Launch debug activity (`TranscriptionDebugActivity`).
- Ensure endpoint points to `<server>/transcribe`.
- Expected:
  - `TRANSCRIPTION_MODE=stub` -> text response.
  - `TRANSCRIPTION_MODE=disabled` -> explicit failure (honest unsupported).

## F. Backup/restore test on phone

```bash
./backup-server.sh
ls data/backups
./restore-server.sh data/backups/<your_backup>.tar.gz
```

## G. Boot startup test

1. Install Termux:Boot app.
2. Run `./enable-termux-boot.sh` inside `~/cyanbridge_termux_server/_local_termux_server`.
3. Ensure battery optimization is disabled for Termux and Tailscale.
4. Reboot phone and verify with `./status-server.sh`.

## H. Known MVP limits

- No cloud memory sync/search backend implementation yet.
- No production-grade web checkout backend flow.
- Chat/image depends on locally installed CLI backend availability.
