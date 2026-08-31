package com.tannmenghong.tbchat.core.data.di

import android.content.Context
import androidx.room.Room
import com.tannmenghong.tbchat.core.data.database.BenchmarkDao
import com.tannmenghong.tbchat.core.data.database.ConversationDao
import com.tannmenghong.tbchat.core.data.database.DownloadJobDao
import com.tannmenghong.tbchat.core.data.database.InstalledModelDao
import com.tannmenghong.tbchat.core.data.database.MessageDao
import com.tannmenghong.tbchat.core.data.database.ModelCatalogDao
import com.tannmenghong.tbchat.core.data.database.NetworkEventDao
import com.tannmenghong.tbchat.core.data.database.TbChatDatabase
import com.tannmenghong.tbchat.core.data.repository.ConversationRepositoryImpl
import com.tannmenghong.tbchat.core.data.repository.DeviceRepositoryImpl
import com.tannmenghong.tbchat.core.data.repository.DownloadRepositoryImpl
import com.tannmenghong.tbchat.core.data.repository.ModelRepositoryImpl
import com.tannmenghong.tbchat.core.data.repository.NetworkLogRepositoryImpl
import com.tannmenghong.tbchat.core.data.repository.SettingsRepositoryImpl
import com.tannmenghong.tbchat.domain.repository.ConversationRepository
import com.tannmenghong.tbchat.domain.repository.DeviceRepository
import com.tannmenghong.tbchat.domain.repository.DownloadRepository
import com.tannmenghong.tbchat.domain.repository.ModelRepository
import com.tannmenghong.tbchat.domain.repository.NetworkLogRepository
import com.tannmenghong.tbchat.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TbChatDatabase =
        Room.databaseBuilder(context, TbChatDatabase::class.java, TbChatDatabase.NAME)
            // No fallbackToDestructiveMigration: conversations are the user's
            // data and a schema change must never silently delete them.
            .build()

    @Provides fun provideModelCatalogDao(db: TbChatDatabase): ModelCatalogDao = db.modelCatalogDao()
    @Provides fun provideInstalledDao(db: TbChatDatabase): InstalledModelDao = db.installedModelDao()
    @Provides fun provideDownloadDao(db: TbChatDatabase): DownloadJobDao = db.downloadJobDao()
    @Provides fun provideConversationDao(db: TbChatDatabase): ConversationDao = db.conversationDao()
    @Provides fun provideMessageDao(db: TbChatDatabase): MessageDao = db.messageDao()
    @Provides fun provideBenchmarkDao(db: TbChatDatabase): BenchmarkDao = db.benchmarkDao()
    @Provides fun provideNetworkEventDao(db: TbChatDatabase): NetworkEventDao = db.networkEventDao()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Generous, because a multi-gigabyte body over a weak mobile signal is
        // slow but not stalled; the read timeout only guards a dead connection.
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds abstract fun bindModelRepository(impl: ModelRepositoryImpl): ModelRepository
    @Binds abstract fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository
    @Binds abstract fun bindConversationRepository(impl: ConversationRepositoryImpl): ConversationRepository
    @Binds abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
    @Binds abstract fun bindDeviceRepository(impl: DeviceRepositoryImpl): DeviceRepository
    @Binds abstract fun bindNetworkLogRepository(impl: NetworkLogRepositoryImpl): NetworkLogRepository
}
