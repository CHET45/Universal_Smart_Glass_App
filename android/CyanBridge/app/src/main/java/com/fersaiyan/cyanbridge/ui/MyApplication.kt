package com.fersaiyan.cyanbridge.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.oudmon.ble.base.bluetooth.BleAction
import com.oudmon.ble.base.bluetooth.BleBaseControl
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.localagent.daily.DailyFactsReminderScheduler
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultBootstrap
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCapturePrefs
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import kotlin.properties.Delegates

/**
 * @Author: Hzy
 * @CreateDate: 2021/6/25 11:50
 *
 * "Programs should be written for other people to read,
 * and only incidentally for machines to execute"
 */
class MyApplication : Application(){

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var hardwareVersion: String = ""
    var firmwareVersion:String =""

    override fun onCreate() {
        super.onCreate()
        application = this
        instance = this
        CONTEXT = applicationContext
        initBle()

        // Keep the BLE control channel connected while the app process is alive.
        // This matches user expectations from the official HeyCyan companion app.
        AutoPairManager.start(this)

        // Local Agent: ensure daily reminder schedule matches current prefs.
        DailyFactsReminderScheduler.scheduleIfEnabled(
            context = this,
            enabled = LocalAgentPrefs.isDailyFactsReminderEnabled(this),
        )

        // Auto audio capture (glasses recording loop)
        if (AutoAudioCapturePrefs.isEnabled(this) && !AutoAudioCaptureService.isRunning()) {
            AutoAudioCaptureService.start(this)
        }

        runCatching { MemoryVaultBootstrap.ensureInitialized(this) }
        maybePreloadLocalModel()
    }

    private fun maybePreloadLocalModel() {
        if (AiProviderPrefs.getProvider(this) != AiProviderType.LOCAL_MODELS) return

        appScope.launch {
            runCatching {
                LocalModelStorageRepository.cleanupMissingModels(this@MyApplication)
            }
        }
    }

    private fun initBle() {
        initReceiver()
        val intentFilter = BleAction.getIntentFilter()
        val myBleReceiver = MyBluetoothReceiver()
        LocalBroadcastManager.getInstance(CONTEXT)
            .registerReceiver(myBleReceiver, intentFilter)
        BleBaseControl.getInstance(CONTEXT).setmContext(this)
    }

    private fun initReceiver() {
        LargeDataHandler.getInstance()
        BleOperateManager.getInstance(this)
        BleOperateManager.getInstance().setApplication(this)
        BleOperateManager.getInstance().init()
        val deviceFilter: IntentFilter = BleAction.getDeviceIntentFilter()
        val deviceReceiver = BluetoothReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(deviceReceiver, deviceFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(deviceReceiver, deviceFilter)
        }

    }

    fun getDeviceIntentFilter(): IntentFilter? {
        val intentFilter = IntentFilter()
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        return intentFilter
    }

    fun getAppRootFile(context: Context): File {
        // /storage/emulated/0/Android/data/pack_name/files
        return if(context.getExternalFilesDir("")!=null){
            context.getExternalFilesDir("")!!
        }else{
            val externalSaveDir = context.externalCacheDir
            externalSaveDir ?: context.cacheDir
        }

    }


    companion object {
        private var application: Application? = null
        var CONTEXT: Context by Delegates.notNull()
            private set
        private lateinit var instance: MyApplication

        val database: com.fersaiyan.cyanbridge.data.local.AppDatabase by lazy {
            androidx.room.Room.databaseBuilder(
                CONTEXT,
                com.fersaiyan.cyanbridge.data.local.AppDatabase::class.java,
                "cyanbridge-db"
            )
                .addMigrations(
                    com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_1_2,
                    com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_2_3,
                    com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_3_4,
                    com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_4_5,
                    com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_5_6,
                    com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_6_7,
                )
                .addCallback(
                    object : androidx.room.RoomDatabase.Callback() {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Room doesn't (yet) support declaring FTS5 virtual tables via annotations.
                            // Ensure the FTS5 index exists for fresh installs.
                            runCatching {
                                com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_4_5.migrate(db)
                                com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_5_6.migrate(db)
                                com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_6_7.migrate(db)
                            }
                        }
                    }
                )
                .build()
        }

        val repository: com.fersaiyan.cyanbridge.data.repository.CyanBridgeRepository by lazy {
            com.fersaiyan.cyanbridge.data.repository.CyanBridgeRepository(database)
        }

        // Chapter 7: summarization + notes workflow
        val summarizationService: com.fersaiyan.cyanbridge.ai.summarization.SummarizationService by lazy {
            com.fersaiyan.cyanbridge.ai.summarization.RuleBasedSummarizationService()
        }

        val notesRepository: com.fersaiyan.cyanbridge.notes.NotesRepository by lazy {
            com.fersaiyan.cyanbridge.notes.RoomNotesRepository(
                noteDao = database.noteDao(),
                summarizationService = summarizationService,
            )
        }

        fun getApplication(): Application {
            return application
                ?: throw RuntimeException("Application not initialized. onCreate not yet called.")
        }

        fun getInstance(): MyApplication {
            return instance
        }
    }
}
