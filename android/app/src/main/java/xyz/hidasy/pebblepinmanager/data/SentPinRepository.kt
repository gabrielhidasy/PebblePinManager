package xyz.hidasy.pebblepinmanager.data

import kotlinx.coroutines.flow.Flow

/**
 * Local storage contract for previously sent timeline pins.
 */
interface SentPinRepository {
    /** Emits sent pins ordered newest-first by [SentPinRecord.sentAtMillis]. */
    fun observeSentPins(): Flow<List<SentPinRecord>>

    /** Inserts or replaces a sent pin record by [SentPinRecord.id]. */
    suspend fun saveSentPin(record: SentPinRecord)

    suspend fun getPinById(id: String): SentPinRecord?

    suspend fun getPinBySourceKey(sourceKey: String): SentPinRecord?

    suspend fun deletePinRecord(id: String)

    suspend fun clearAll()
}
