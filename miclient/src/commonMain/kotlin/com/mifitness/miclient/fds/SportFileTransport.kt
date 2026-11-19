package com.mifitness.miclient.fds

/** Preparation is repeatable; body writes are reserved and reconciled individually by the caller. */
interface SportFileTransport {
    suspend fun prepareSportFile(request: FdsClient.SportFileRequest): FdsClient.UploadMeta
    suspend fun uploadPreparedSportFile(meta: FdsClient.UploadMeta, bytes: ByteArray)
    suspend fun downloadSportFile(request: FdsClient.SportFileRequest): ByteArray
}
