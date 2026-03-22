# Memory Privacy Modes

## 1) Private Local

- Fully implemented now.
- Memory content, OCR artifacts, indexes, and retrieval stay on-device.
- No sync upload attempts are made.

## 2) Encrypted Sync (Future-Ready)

- Fully implemented client-side preparation now.
- App creates encrypted sync-ready payload manifests and queue entries.
- No backend upload is attempted yet.
- UI explicitly states backend is not configured.
- Local retrieval behavior remains usable (same as local/private search path).

## 3) Fast Cloud Memory (Future-Ready)

- Mode, policy plumbing, and contracts are implemented.
- Backend-dependent runtime is intentionally unavailable.
- UI explicitly shows this mode as coming later.
- Local fallback retrieval does not include local-only memory items.

## 4) Confidential Cloud Beta (Future-Ready)

- Mode, policy plumbing, and contracts are implemented.
- No enclave/confidential backend is implemented in mobile repo.
- UI explicitly shows this mode as unavailable until backend exists.

## OCR Defaults and Retention

- Passive OCR memories are classified as local-only by default.
- OCR sync eligibility is off by default.
- Retention is configurable (days) and enforced locally.
- "Delete all passive OCR capture" is supported from Settings.
