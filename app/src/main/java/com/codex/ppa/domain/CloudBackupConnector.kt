package com.codex.ppa.domain

interface CloudBackupConnector {
    val connectorId: String
    val displayName: String
    val isImplemented: Boolean
}

class GoogleDrivePlaceholderConnector : CloudBackupConnector {
    override val connectorId: String = "google-drive-placeholder"
    override val displayName: String = "Google Drive 연동 자리만 준비됨"
    override val isImplemented: Boolean = false
}
