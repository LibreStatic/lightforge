package com.librestatic.lightforge

/** Recovery never interprets a failed database/provider lookup as permission to remove a copy. */
data class BackupRecoveryUiState(
    val working: Boolean = false,
    val removed: Int = 0,
    val retained: Int = 0,
    val failed: Boolean = false,
)
