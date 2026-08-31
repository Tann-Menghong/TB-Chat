package com.tannmenghong.tbchat.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.tannmenghong.tbchat.domain.model.DownloadStatus
import com.tannmenghong.tbchat.inference.api.LicenseClass

class Converters {
    // Stored as names rather than ordinals: the DownloadJobDao queries match on
    // the string values, and an ordinal would silently break if the enum is
    // ever reordered.
    @TypeConverter
    fun downloadStatusToString(value: DownloadStatus): String = value.name

    @TypeConverter
    fun stringToDownloadStatus(value: String): DownloadStatus =
        runCatching { DownloadStatus.valueOf(value) }.getOrDefault(DownloadStatus.FAILED)

    @TypeConverter
    fun licenseClassToString(value: LicenseClass): String = value.name

    @TypeConverter
    fun stringToLicenseClass(value: String): LicenseClass =
        runCatching { LicenseClass.valueOf(value) }.getOrDefault(LicenseClass.USE_RESTRICTED)
}

@Database(
    entities = [
        ModelCatalogEntity::class,
        InstalledModelEntity::class,
        DownloadJobEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        BenchmarkEntity::class,
        NetworkEventEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class TbChatDatabase : RoomDatabase() {
    abstract fun modelCatalogDao(): ModelCatalogDao
    abstract fun installedModelDao(): InstalledModelDao
    abstract fun downloadJobDao(): DownloadJobDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun benchmarkDao(): BenchmarkDao
    abstract fun networkEventDao(): NetworkEventDao

    companion object {
        const val NAME = "tbchat.db"
    }
}
