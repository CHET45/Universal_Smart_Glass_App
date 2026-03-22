package com.fersaiyan.cyanbridge.memoryvault

enum class MemoryPrivacyMode(
    val title: String,
    val description: String,
) {
    PRIVATE_LOCAL(
        title = "Private Local",
        description = "All memory, indexes, and retrieval stay on-device.",
    ),
    ENCRYPTED_SYNC(
        title = "Encrypted Sync",
        description = "Client-side encrypted sync payloads are prepared locally. Backend pending.",
    ),
    FAST_CLOUD_MEMORY(
        title = "Fast Cloud Memory",
        description = "Future cloud memory mode. Unavailable until backend exists.",
    ),
    CONFIDENTIAL_CLOUD_BETA(
        title = "Confidential Cloud Beta",
        description = "Future confidential cloud mode. Unavailable until backend exists.",
    );

    companion object {
        fun fromRaw(raw: String?): MemoryPrivacyMode {
            val normalized = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.name == normalized } ?: PRIVATE_LOCAL
        }
    }
}
