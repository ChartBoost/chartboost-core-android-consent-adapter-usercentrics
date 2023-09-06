/*
 * Copyright 2023 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.core.consent.usercentrics

import android.app.Activity
import android.content.Context
import com.chartboost.core.ChartboostCoreLogger
import com.chartboost.core.Utils
import com.chartboost.core.consent.ConsentAdapter
import com.chartboost.core.consent.ConsentAdapterListener
import com.chartboost.core.consent.ConsentDialogType
import com.chartboost.core.consent.ConsentStandard
import com.chartboost.core.consent.ConsentStatus
import com.chartboost.core.consent.ConsentStatusSource
import com.chartboost.core.consent.ConsentValue
import com.chartboost.core.consent.DefaultConsentStandard
import com.chartboost.core.consent.DefaultConsentValue
import com.chartboost.core.error.ChartboostCoreError
import com.chartboost.core.error.ChartboostCoreException
import com.chartboost.core.initialization.InitializableModule
import com.chartboost.core.initialization.ModuleInitializationConfiguration
import com.usercentrics.ccpa.CCPAData
import com.usercentrics.sdk.BannerSettings
import com.usercentrics.sdk.Usercentrics
import com.usercentrics.sdk.UsercentricsBanner
import com.usercentrics.sdk.UsercentricsEvent
import com.usercentrics.sdk.UsercentricsOptions
import com.usercentrics.sdk.UsercentricsReadyStatus
import com.usercentrics.sdk.models.common.UsercentricsLoggerLevel
import com.usercentrics.sdk.models.settings.UsercentricsConsentType
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.serialization.json.Json.Default.configuration
import org.json.JSONObject
import java.lang.IllegalArgumentException
import kotlin.coroutines.resume

class UsercentricsAdapter() : ConsentAdapter, InitializableModule {

    companion object {
        const val CONSENT_MEDIATION: String = "consentMediation"
        const val CHARTBOOST_CORE_DPS_KEY: String = "coreDpsName"
        const val DEFAULT_CHARTBOOST_CORE_DPS = "ChartboostCore"
        const val DEFAULT_LANGUAGE_KEY: String = "defaultLanguage"
        const val LOGGER_LEVEL_KEY: String = "loggerLevel"
        const val OPTIONS_KEY: String = "options"
        const val RULE_SET_ID_KEY: String = "ruleSetId"
        const val SETTINGS_ID_KEY: String = "settingsId"
        const val TIMEOUT_MILLIS_KEY: String = "timeoutMillis"
        const val VERSION_KEY: String = "version"

        /**
         * Use this to change the look and feel of the Usercentrics consent dialogs.
         * See https://docs.usercentrics.com/cmp_in_app_sdk/latest/features/customization/ for more
         * information.
         */
        var bannerSettings: BannerSettings? = null
    }

    constructor(
        options: UsercentricsOptions,
        chartboostCoreDpsName: String = DEFAULT_CHARTBOOST_CORE_DPS,
    ) : this(options) {
        this@UsercentricsAdapter.chartboostCoreDpsName = chartboostCoreDpsName
    }

    constructor(options: UsercentricsOptions) : this() {
        this@UsercentricsAdapter.options = options
    }

    override fun updateProperties(configuration: JSONObject) {
        chartboostCoreDpsName =
            configuration.optString(CHARTBOOST_CORE_DPS_KEY, DEFAULT_CHARTBOOST_CORE_DPS)
        val optionsJson = configuration.optJSONObject(OPTIONS_KEY) ?: JSONObject()
        val settingsId = optionsJson.optString(SETTINGS_ID_KEY, "")
        val defaultLanguage = optionsJson.optString(DEFAULT_LANGUAGE_KEY, "en")
        val version = optionsJson.optString(VERSION_KEY, "latest")
        val timeoutMillis = optionsJson.optLong(TIMEOUT_MILLIS_KEY, 5000)
        val loggerLevel =
            stringToUsercentricsLoggerLevel(optionsJson.optString(LOGGER_LEVEL_KEY, "debug"))
        val ruleSetId = optionsJson.optString(RULE_SET_ID_KEY, "")
        val consentMediation = optionsJson.optBoolean(CONSENT_MEDIATION, false)

        options = UsercentricsOptions(
            settingsId = settingsId,
            defaultLanguage = defaultLanguage,
            version = version,
            timeoutMillis = timeoutMillis,
            loggerLevel = loggerLevel,
            ruleSetId = ruleSetId,
            consentMediation = consentMediation,
        )
    }

    override val moduleId: String = "usercentrics"

    override val moduleVersion: String = BuildConfig.CHARTBOOST_CORE_USERCENTRICS_ADAPTER_VERSION

    override var shouldCollectConsent: Boolean = true
        private set

    override val consents: Map<ConsentStandard, ConsentValue>
        get() = mutableConsents

    private val mutableConsents = mutableMapOf<ConsentStandard, ConsentValue>()

    override var consentStatus: ConsentStatus = ConsentStatus.UNKNOWN
        private set

    override var listener: ConsentAdapterListener? = null

    /*
     * The name of the Usercentrics Data Processing Service (DPS) defined in the Usercentrics
     * dashboard for the Chartboost Core SDK.
     */
    var chartboostCoreDpsName: String = DEFAULT_CHARTBOOST_CORE_DPS

    /**
     * Options to initialize Usercentrics.
     */
    var options: UsercentricsOptions? = null

    override suspend fun showConsentDialog(
        activity: Activity, dialogType: ConsentDialogType
    ): Result<Unit> {
        return executeWhenUsercentricsInitialized(activity.applicationContext) {
            val banner = UsercentricsBanner(activity, bannerSettings)
            when (dialogType) {
                ConsentDialogType.CONCISE -> {
                    banner.showFirstLayer { userResponse ->
                        ChartboostCoreLogger.d("1st layer response: $userResponse")
                    }
                    Result.success(Unit)
                }

                ConsentDialogType.DETAILED -> {
                    banner.showSecondLayer { userResponse ->
                        ChartboostCoreLogger.d("2nd layer response: $userResponse")
                    }
                    Result.success(Unit)
                }

                else -> {
                    ChartboostCoreLogger.d("Unexpected consent dialog type: $dialogType")
                    Result.failure(ChartboostCoreException(ChartboostCoreError.ConsentError.DialogShowError))
                }
            }
        }
    }

    override suspend fun grantConsent(
        context: Context, statusSource: ConsentStatusSource
    ): Result<Unit> {
        return executeWhenUsercentricsInitialized(context) {
            Usercentrics.instance.acceptAll(
                consentStatusSourceToUsercentricsConsentType(
                    statusSource
                )
            )
            fetchConsentInfo(
                context, NotificationType.DIFFERENT_FROM_CURRENT_VALUE, consentStatus, consents
            )
        }
    }

    override suspend fun denyConsent(
        context: Context, statusSource: ConsentStatusSource
    ): Result<Unit> {
        return executeWhenUsercentricsInitialized(context) {
            Usercentrics.instance.denyAll(
                consentStatusSourceToUsercentricsConsentType(
                    statusSource
                )
            )
            fetchConsentInfo(
                context, NotificationType.DIFFERENT_FROM_CURRENT_VALUE, consentStatus, consents
            )
        }
    }

    override suspend fun resetConsent(context: Context): Result<Unit> {
        val options = options ?: UsercentricsOptions()
        val oldConsentStatus = consentStatus
        val oldConsents = mutableConsents.toMap()
        consentStatus = ConsentStatus.UNKNOWN
        mutableConsents.clear()
        Usercentrics.reset()
        initializeUsercentrics(context, options)
        return fetchConsentInfo(
            context, NotificationType.DIFFERENT_FROM_CACHED_VALUE, oldConsentStatus, oldConsents
        )
    }

    private fun consentStatusSourceToUsercentricsConsentType(statusSource: ConsentStatusSource): UsercentricsConsentType {
        return when(statusSource) {
            ConsentStatusSource.USER -> UsercentricsConsentType.EXPLICIT
            ConsentStatusSource.DEVELOPER -> UsercentricsConsentType.IMPLICIT
            else -> UsercentricsConsentType.IMPLICIT
        }
    }

    override suspend fun initialize(context: Context, moduleInitializationConfiguration: ModuleInitializationConfiguration): Result<Unit> {
        val options = options
            ?: return Result.failure(ChartboostCoreException(ChartboostCoreError.ConsentError.InitializationError))
        initializeUsercentrics(context, options)

        // This waits for Usercentrics.isReady()
        return fetchConsentInfo(context, NotificationType.NEVER, consentStatus, consents)
    }

    private fun initializeUsercentrics(context: Context, options: UsercentricsOptions) {
        Usercentrics.initialize(context.applicationContext, options)

        Usercentrics.isReady({
            UsercentricsEvent.onConsentUpdated {
                CoroutineScope(Main).launch {
                    fetchConsentInfo(
                        context,
                        NotificationType.DIFFERENT_FROM_CURRENT_VALUE,
                        consentStatus,
                        consents
                    )
                }
            }
        }, {
            ChartboostCoreLogger.d("Unable to attach onConsentUpdated listener")
        })
    }

    private suspend fun executeWhenUsercentricsInitialized(
        context: Context,
        dispatcher: CoroutineDispatcher = Main,
        block: suspend (usercentricsReadyStatus: UsercentricsReadyStatus) -> Result<Unit>
    ): Result<Unit> {
        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<Unit>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
            Usercentrics.isReady({ readyStatus ->
                CoroutineScope(dispatcher).launch(CoroutineExceptionHandler { _, exception ->
                    ChartboostCoreLogger.w("$exception when executing usercentrics action.")
                    resumeOnce(Result.failure(ChartboostCoreException(ChartboostCoreError.ConsentError.Unknown)))
                }) {
                    resumeOnce(block(readyStatus))
                }
            }, {
                options?.let { options ->
                    initializeUsercentrics(context, options)
                    Usercentrics.isReady({ readyStatus ->
                        CoroutineScope(dispatcher).launch {
                            resumeOnce(block(readyStatus))
                        }
                    }, { error ->
                        ChartboostCoreLogger.d("$error when retrying initialization. Clearing consents")
                        resetConsentsAndNotify()
                        resumeOnce(
                            Result.failure(
                                ChartboostCoreException(
                                    ChartboostCoreError.ConsentError.InitializationError
                                )
                            )
                        )
                    })
                } ?: run {
                    if (continuation.isActive) {
                        continuation.resume(
                            Result.failure(
                                ChartboostCoreException(
                                    ChartboostCoreError.ConsentError.InitializationError
                                )
                            )
                        )
                    }
                }
            })
        }
    }

    private suspend fun fetchConsentInfo(
        context: Context,
        notify: NotificationType,
        oldConsentStatus: ConsentStatus,
        oldConsents: Map<ConsentStandard, ConsentValue?>
    ): Result<Unit> {
        return executeWhenUsercentricsInitialized(context) { usercentricsReadyStatus ->
            updateConsents(
                usercentricsReadyStatus, notify, oldConsentStatus, oldConsents
            )
            Result.success(Unit)
        }
    }

    private suspend fun updateConsents(
        usercentricsReadyStatus: UsercentricsReadyStatus,
        notify: NotificationType,
        oldConsentStatus: ConsentStatus,
        oldConsents: Map<ConsentStandard, ConsentValue?>
    ) {
        shouldCollectConsent = usercentricsReadyStatus.shouldCollectConsent

        updateConsentStatus(usercentricsReadyStatus, notify, oldConsentStatus)

        updateTcf(notify, oldConsents)

        val uspData = Usercentrics.instance.getUSPData()
        updateCcpaOptIn(uspData, notify, oldConsents)
        updateUsp(uspData.uspString, notify, oldConsents)
    }

    private fun updateUsp(
        newUspString: String,
        notify: NotificationType,
        cachedConsents: Map<ConsentStandard, ConsentValue?>
    ) {
        val nullableNewUspString = newUspString.ifEmpty { null }
        ChartboostCoreLogger.d("Setting USP to $nullableNewUspString")
        val previousUsp = consents[DefaultConsentStandard.USP.value]?.ifEmpty { null }
        if (nullableNewUspString.isNullOrEmpty()) {
            mutableConsents.remove(DefaultConsentStandard.USP.value)
        } else {
            mutableConsents[DefaultConsentStandard.USP.value] = newUspString
        }
        when (notify) {
            NotificationType.DIFFERENT_FROM_CURRENT_VALUE -> if (previousUsp != nullableNewUspString) Utils.safeExecute {
                listener?.onConsentChangeForStandard(
                    DefaultConsentStandard.USP.value, newUspString
                )
            }

            NotificationType.DIFFERENT_FROM_CACHED_VALUE -> if (cachedConsents[DefaultConsentStandard.USP.value] != nullableNewUspString) Utils.safeExecute {
                listener?.onConsentChangeForStandard(
                    DefaultConsentStandard.USP.value, newUspString
                )
            }

            else -> Unit
        }
    }

    private fun updateCcpaOptIn(
        ccpaData: CCPAData,
        notify: NotificationType,
        cachedConsents: Map<ConsentStandard, ConsentValue?>
    ) {
        val newCcpaOptIn = when (ccpaData.optedOut) {
            true -> DefaultConsentValue.DENIED.value
            false -> DefaultConsentValue.GRANTED.value
            else -> null
        }
        val previousCcpaOptIn = consents[DefaultConsentStandard.CCPA_OPT_IN.value]
        newCcpaOptIn?.let {
            mutableConsents[DefaultConsentStandard.CCPA_OPT_IN.value] = it
        } ?: mutableConsents.remove(DefaultConsentStandard.CCPA_OPT_IN.value)
        ChartboostCoreLogger.d("Setting CCPA opt in to $newCcpaOptIn")
        when (notify) {
            NotificationType.DIFFERENT_FROM_CURRENT_VALUE -> if (previousCcpaOptIn != newCcpaOptIn) Utils.safeExecute {
                listener?.onConsentChangeForStandard(
                    DefaultConsentStandard.CCPA_OPT_IN.value, newCcpaOptIn
                )
            }

            NotificationType.DIFFERENT_FROM_CACHED_VALUE -> if (cachedConsents[DefaultConsentStandard.CCPA_OPT_IN.value] != newCcpaOptIn) Utils.safeExecute {
                listener?.onConsentChangeForStandard(
                    DefaultConsentStandard.CCPA_OPT_IN.value, newCcpaOptIn
                )
            }

            else -> Unit
        }
    }

    private suspend fun updateTcf(
        notify: NotificationType, cachedConsents: Map<ConsentStandard, ConsentValue?>
    ) {
        return suspendCancellableCoroutine { continuation ->
            Usercentrics.instance.getTCFData {
                val previousTcfString = consents[DefaultConsentStandard.TCF.value]
                val newTcfString = it.tcString.ifEmpty { null }

                ChartboostCoreLogger.d("Setting TCF to $newTcfString")
                if (newTcfString.isNullOrEmpty()) {
                    mutableConsents.remove(DefaultConsentStandard.TCF.value)
                } else {
                    mutableConsents[DefaultConsentStandard.TCF.value] = newTcfString
                }

                when (notify) {
                    NotificationType.DIFFERENT_FROM_CURRENT_VALUE -> if (newTcfString != previousTcfString) Utils.safeExecute {
                        listener?.onConsentChangeForStandard(
                            DefaultConsentStandard.TCF.value, newTcfString
                        )
                    }

                    NotificationType.DIFFERENT_FROM_CACHED_VALUE -> if (cachedConsents[DefaultConsentStandard.TCF.value] != newTcfString) Utils.safeExecute {
                        listener?.onConsentChangeForStandard(
                            DefaultConsentStandard.TCF.value, newTcfString
                        )
                    }

                    else -> Unit
                }

                continuation.resume(Unit)
            }
        }
    }

    private fun updateConsentStatus(
        usercentricsReadyStatus: UsercentricsReadyStatus,
        notify: NotificationType,
        cachedConsentStatus: ConsentStatus
    ) {
        val newConsentStatus =
            usercentricsReadyStatus.consents.find { it.dataProcessor == chartboostCoreDpsName }
                ?.let {
                    if (it.status) ConsentStatus.GRANTED else ConsentStatus.DENIED
                } ?: ConsentStatus.UNKNOWN

        val previousConsentStatus = consentStatus
        consentStatus = newConsentStatus
        ChartboostCoreLogger.d("Setting consent status to $newConsentStatus")
        when (notify) {
            NotificationType.DIFFERENT_FROM_CURRENT_VALUE -> if (previousConsentStatus != newConsentStatus) Utils.safeExecute {
                listener?.onConsentStatusChange(newConsentStatus)
            }

            NotificationType.DIFFERENT_FROM_CACHED_VALUE -> if (cachedConsentStatus != newConsentStatus) Utils.safeExecute {
                listener?.onConsentStatusChange(newConsentStatus)
            }

            else -> Unit
        }
    }

    private fun stringToUsercentricsLoggerLevel(loggerLevel: String?): UsercentricsLoggerLevel {
        return try {
            UsercentricsLoggerLevel.valueOf(loggerLevel?.uppercase() ?: "")
        } catch (e: IllegalArgumentException) {
            UsercentricsLoggerLevel.DEBUG
        }
    }

    private fun resetConsentsAndNotify() {
        consentStatus = ConsentStatus.UNKNOWN
        consents.forEach {
            Utils.safeExecute {
                listener?.onConsentChangeForStandard(it.key, null)
            }
        }
        Utils.safeExecute {
            listener?.onConsentStatusChange(ConsentStatus.UNKNOWN)
        }
        mutableConsents.clear()
    }

    /**
     * When to fire a notification.
     */
    private enum class NotificationType {
        /**
         * Fires the notification if the new and current are different.
         */
        DIFFERENT_FROM_CURRENT_VALUE,

        /**
         * Only fire the notification if the value has changed from the previous saved value and
         * is also different from the current value.
         */
        DIFFERENT_FROM_CACHED_VALUE,

        /**
         * Do not fire the listener.
         */
        NEVER,
    }
}
