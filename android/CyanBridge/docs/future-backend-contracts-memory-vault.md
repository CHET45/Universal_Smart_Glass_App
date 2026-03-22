# Future Backend Contracts: Memory Vault

## Goal

Define mobile-side contracts now without faking backend availability.

## Implemented Contract Models

Located in `app/src/main/java/com/fersaiyan/cyanbridge/memoryvault/contracts/FutureBackendContracts.kt`:

- `WrappedKeyManifest`
- `EncryptedMemoryBlobManifest`
- `MemorySyncRecord`
- `MemorySyncBatchRequest` / `MemorySyncBatchResponse`
- `CloudMemorySearchRequest` / `CloudMemorySearchResponse`
- `DeviceEnrollmentRequest` / `DeviceEnrollmentResponse`

## Service Interfaces

- `MemorySyncApi`
- `CloudMemorySearchApi`
- `VaultDeviceEnrollmentApi`

## Default Stub Implementations

- `NotConfiguredMemorySyncApi`
- `NotConfiguredCloudMemorySearchApi`
- `NotConfiguredVaultDeviceEnrollmentApi`

Each stub explicitly returns a failure indicating backend is not configured.

## Local Queue/Manifest Preparation

`MemorySyncPreparationService` prepares:

- per-item payload manifest JSON
- checksum
- queue entries with `pending_backend` status

No upload is attempted until backend implementations are provided and wired.

## Expected Future Backend Responsibilities

- Accept encrypted sync batches.
- Return accepted/rejected refs with server cursor.
- Provide cloud retrieval endpoints for eligible memory classes.
- Support device enrollment and secure key exchange.
- Enforce policy revocation requests and retention controls across devices.
