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
import com.chartboost.core.consent.*
import com.chartboost.core.error.ChartboostCoreError
import com.chartboost.core.error.ChartboostCoreException
import com.chartboost.core.initialization.Module
import com.chartboost.core.initialization.ModuleConfiguration
import com.usercentrics.ccpa.CCPAData
import com.usercentrics.sdk.*
import com.usercentrics.sdk.models.common.UsercentricsLoggerLevel
import com.usercentrics.sdk.models.settings.UsercentricsConsentType
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Main
import org.json.JSONObject
import kotlin.coroutines.resume

class UsercentricsAdapter() : ConsentAdapter, Module {

    companion object {
        const val CONSENT_MEDIATION: String = "consentMediation"
        const val CHARTBOOST_CORE_DPS_KEY: String = "coreDpsName"
        const val DEFAULT_CHARTBOOST_CORE_DPS = "ChartboostCore"
        const val DEFAULT_LANGUAGE_KEY: String = "defaultLanguage"
        const val LOGGER_LEVEL_KEY: String = "loggerLevel"
        const val OPTIONS_KEY: String = "options"
        const val PARTNER_ID_MAP_KEY: String = "partnerIdMap"
        const val RULE_SET_ID_KEY: String = "ruleSetId"
        const val SETTINGS_ID_KEY: String = "settingsId"
        const val TIMEOUT_MILLIS_KEY: String = "timeoutMillis"
        const val VERSION_KEY: String = "version"
        const val moduleId = "usercentrics"
        const val moduleVersion = BuildConfig.CHARTBOOST_CORE_USERCENTRICS_ADAPTER_VERSION

        /**
         * The default Usercentrics template ID to Chartboost partner ID map.
         */
        private val DEFAULT_TEMPLATE_ID_TO_PARTNER_ID =
            mapOf(
                "J64M6DKwx" to "adcolony",
                "r7rvuoyDz" to "admob",
                "IUyljv4X5" to "amazon_aps",
                "fHczTMzX8" to "applovin",
                "IEbRp3saT" to "chartboost",
                "H17alcVo_iZ7" to "fyber",
                "S1_9Vsuj-Q" to "google_googlebidding",
                "ROCBK21nx" to "hyprmx",
                "ykdq8j5a9MExGT" to "inmobi",
                "9dchbL797" to "ironsource",
                "ax0Nljnj2szF_r" to "facebook",
                "E6AgqirYV" to "mintegral",
                "HWSNU_LI1" to "pangle",
                "B1DLe54jui-X" to "tapjoy",
                "hpb62D82I" to "unity",
                "5bv4OvSwoXKh-G" to "verve",
                "jk3jF2tpw" to "vungle",
                "EMD3qUMa8" to "vungle",
            )

        /**
         * Use this to change the look and feel of the Usercentrics consent dialogs.
         * See https://docs.usercentrics.com/cmp_in_app_sdk/latest/features/customization/ for more
         * information.
         */
        var bannerSettings: BannerSettings? = null
    }

    constructor(
        options: UsercentricsOptions,
        templateIdToPartnerIdMap: Map<String, String> = mapOf(),
    ) : this() {
        this@UsercentricsAdapter.options = options
        this@UsercentricsAdapter.templateIdToPartnerIdMap.putAll(templateIdToPartnerIdMap)
    }

    override fun updateProperties(context: Context, configuration: JSONObject) {
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
        val partnerIdMapJsonObject = configuration.optJSONObject(PARTNER_ID_MAP_KEY)
        partnerIdMapJsonObject?.keys()?.forEach {
            templateIdToPartnerIdMap[it] = partnerIdMapJsonObject.optString(it)
        }

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

    override val moduleId: String = Companion.moduleId

    override val moduleVersion: String = Companion.moduleVersion

    override var shouldCollectConsent: Boolean = true
        private set

    override val consents: Map<ConsentKey, ConsentValue>
        get() = mutableConsents

    private val mutableConsents = mutableMapOf<ConsentKey, ConsentValue>()

    /**
     * This map is just to keep track of partner consents so we don't have to go from consent
     * status and String. All of these should also live in consents.
     */
    private val partnerConsentStatus: MutableMap<String, ConsentStatus> = mutableMapOf()

    override val sharedPreferencesIabStrings: MutableMap<String, String> = mutableMapOf()
    override val sharedPreferenceChangeListener: ConsentAdapter.IabSharedPreferencesListener =
        ConsentAdapter.IabSharedPreferencesListener(sharedPreferencesIabStrings)

    override var listener: ConsentAdapterListener? = null
        set(value) {
            field = value
            sharedPreferenceChangeListener.listener = value
        }

    /*
     * The name of the Usercentrics Data Processing Service (DPS) defined in the Usercentrics
     * dashboard for the Chartboost Core SDK.
     */
    var chartboostCoreDpsName: String = DEFAULT_CHARTBOOST_CORE_DPS

    /**
     * Options to initialize Usercentrics.
     */
    var options: UsercentricsOptions? = null

    private val templateIdToPartnerIdMap: MutableMap<String, String> = mutableMapOf<String, String>().apply {
        putAll(DEFAULT_TEMPLATE_ID_TO_PARTNER_ID)
    }

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
        context: Context, statusSource: ConsentSource
    ): Result<Unit> {
        return executeWhenUsercentricsInitialized(context) {
            Usercentrics.instance.acceptAll(
                consentStatusSourceToUsercentricsConsentType(
                    statusSource
                )
            )
            fetchConsentInfo(
                context, NotificationType.DIFFERENT_FROM_CURRENT_VALUE, consents, partnerConsentStatus,
            )
        }
    }

    override suspend fun denyConsent(
        context: Context, statusSource: ConsentSource
    ): Result<Unit> {
        return executeWhenUsercentricsInitialized(context) {
            Usercentrics.instance.denyAll(
                consentStatusSourceToUsercentricsConsentType(
                    statusSource
                )
            )
            fetchConsentInfo(
                context, NotificationType.DIFFERENT_FROM_CURRENT_VALUE, consents, partnerConsentStatus,
            )
        }
    }

    override suspend fun resetConsent(context: Context): Result<Unit> {
        val options = options ?: UsercentricsOptions()
        val oldConsents = mutableConsents.toMap()
        mutableConsents.clear()
        Usercentrics.reset()
        initializeUsercentrics(context, options)
        return fetchConsentInfo(
            context, NotificationType.DIFFERENT_FROM_CACHED_VALUE, oldConsents, partnerConsentStatus,
        )
    }

    private fun consentStatusSourceToUsercentricsConsentType(statusSource: ConsentSource): UsercentricsConsentType {
        return when(statusSource) {
            ConsentSource.USER -> UsercentricsConsentType.EXPLICIT
            ConsentSource.DEVELOPER -> UsercentricsConsentType.IMPLICIT
            else -> UsercentricsConsentType.IMPLICIT
        }
    }

    override suspend fun initialize(context: Context, moduleConfiguration: ModuleConfiguration): Result<Unit> {
        val options = options
            ?: return Result.failure(ChartboostCoreException(ChartboostCoreError.ConsentError.InitializationError))
        initializeUsercentrics(context, options)

        // This waits for Usercentrics.isReady()
        return fetchConsentInfo(context, NotificationType.NEVER, consents, partnerConsentStatus)
    }

    private fun initializeUsercentrics(context: Context, options: UsercentricsOptions) {
        Usercentrics.initialize(context.applicationContext, options)

        Usercentrics.isReady({
            UsercentricsEvent.onConsentUpdated {
                CoroutineScope(Main).launch {
                    fetchConsentInfo(
                        context,
                        NotificationType.DIFFERENT_FROM_CURRENT_VALUE,
                        consents,
                        partnerConsentStatus,
                    )
                }
            }
            startObservingSharedPreferencesIabStrings(context)
            mutableConsents.putAll(sharedPreferencesIabStrings)
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
        oldConsents: Map<ConsentKey, ConsentValue?>,
        oldPartnerConsents: Map<String, ConsentStatus>,
    ): Result<Unit> {
        return executeWhenUsercentricsInitialized(context) { usercentricsReadyStatus ->
            updateConsents(
                usercentricsReadyStatus, notify, oldConsents, oldPartnerConsents
            )
            Result.success(Unit)
        }
    }

    private suspend fun updateConsents(
        usercentricsReadyStatus: UsercentricsReadyStatus,
        notify: NotificationType,
        oldConsents: Map<ConsentKey, ConsentValue?>,
        oldPartnerConsents: Map<String, ConsentStatus>,
    ) {
        shouldCollectConsent = usercentricsReadyStatus.shouldCollectConsent

        updateTcf(notify, oldConsents)

        val uspData = Usercentrics.instance.getUSPData()
        updateCcpaOptIn(uspData, notify, oldConsents)
        updateUsp(uspData.uspString, notify, oldConsents)

        updatePartnerConsents(usercentricsReadyStatus.consents, notify, oldPartnerConsents)
    }

    private fun updateUsp(
        newUspString: String,
        notify: NotificationType,
        cachedConsents: Map<ConsentKey, ConsentValue?>
    ) {
        val nullableNewUspString = newUspString.ifEmpty { null }
        ChartboostCoreLogger.d("Setting USP to $nullableNewUspString")
        val previousUsp = consents[DefaultConsentKey.USP.value]?.ifEmpty { null }
        if (nullableNewUspString.isNullOrEmpty()) {
            mutableConsents.remove(DefaultConsentKey.USP.value)
        } else {
            mutableConsents[DefaultConsentKey.USP.value] = newUspString
        }
        when (notify) {
            NotificationType.DIFFERENT_FROM_CURRENT_VALUE -> if (previousUsp != nullableNewUspString) Utils.safeExecute {
                listener?.onConsentChange(
                    DefaultConsentKey.USP.value
                )
            }

            NotificationType.DIFFERENT_FROM_CACHED_VALUE -> if (cachedConsents[DefaultConsentKey.USP.value] != nullableNewUspString) Utils.safeExecute {
                listener?.onConsentChange(
                    DefaultConsentKey.USP.value
                )
            }

            else -> Unit
        }
    }

    private fun updateCcpaOptIn(
        ccpaData: CCPAData,
        notify: NotificationType,
        cachedConsents: Map<ConsentKey, ConsentValue?>
    ) {
        val newCcpaOptIn = when (ccpaData.optedOut) {
            true -> DefaultConsentValue.DENIED.value
            false -> DefaultConsentValue.GRANTED.value
            else -> null
        }
        val previousCcpaOptIn = consents[DefaultConsentKey.CCPA_OPT_IN.value]
        newCcpaOptIn?.let {
            mutableConsents[DefaultConsentKey.CCPA_OPT_IN.value] = it
        } ?: mutableConsents.remove(DefaultConsentKey.CCPA_OPT_IN.value)
        ChartboostCoreLogger.d("Setting CCPA opt in to $newCcpaOptIn")
        when (notify) {
            NotificationType.DIFFERENT_FROM_CURRENT_VALUE -> if (previousCcpaOptIn != newCcpaOptIn) Utils.safeExecute {
                listener?.onConsentChange(
                    DefaultConsentKey.CCPA_OPT_IN.value
                )
            }

            NotificationType.DIFFERENT_FROM_CACHED_VALUE -> if (cachedConsents[DefaultConsentKey.CCPA_OPT_IN.value] != newCcpaOptIn) Utils.safeExecute {
                listener?.onConsentChange(
                    DefaultConsentKey.CCPA_OPT_IN.value
                )
            }

            else -> Unit
        }
    }

    private suspend fun updateTcf(
        notify: NotificationType, cachedConsents: Map<ConsentKey, ConsentValue?>
    ) {
        return suspendCancellableCoroutine { continuation ->
            Usercentrics.instance.getTCFData {
                val previousTcfString = consents[DefaultConsentKey.TCF.value]
                val newTcfString = it.tcString.ifEmpty { null }

                ChartboostCoreLogger.d("Setting TCF to $newTcfString")
                if (newTcfString.isNullOrEmpty()) {
                    mutableConsents.remove(DefaultConsentKey.TCF.value)
                } else {
                    mutableConsents[DefaultConsentKey.TCF.value] = newTcfString
                }

                when (notify) {
                    NotificationType.DIFFERENT_FROM_CURRENT_VALUE -> if (newTcfString != previousTcfString) Utils.safeExecute {
                        listener?.onConsentChange(
                            DefaultConsentKey.TCF.value
                        )
                    }

                    NotificationType.DIFFERENT_FROM_CACHED_VALUE -> if (cachedConsents[DefaultConsentKey.TCF.value] != newTcfString) Utils.safeExecute {
                        listener?.onConsentChange(
                            DefaultConsentKey.TCF.value
                        )
                    }

                    else -> Unit
                }

                continuation.resume(Unit)
            }
        }
    }

    private fun updatePartnerConsents(
        consents: List<UsercentricsServiceConsent>,
        notify: NotificationType,
        oldPartnerConsents: Map<String, ConsentStatus>,
    ) {
        consents.forEach { consent ->
            val partnerId = templateIdToPartnerIdMap[consent.templateId] ?: consent.templateId
            val previousConsentStatus = partnerConsentStatus[partnerId] ?: ConsentStatus.UNKNOWN
            val newConsentStatus = toConsentStatus(consent.status)
            partnerConsentStatus[partnerId] = newConsentStatus
            mutableConsents[partnerId] = newConsentStatus.toString()
            when (notify) {
                NotificationType.DIFFERENT_FROM_CURRENT_VALUE -> if (previousConsentStatus != newConsentStatus) Utils.safeExecute {
                    listener?.onConsentChange(partnerId)
                }

                NotificationType.DIFFERENT_FROM_CACHED_VALUE -> if ((oldPartnerConsents[partnerId] ?: ConsentStatus.UNKNOWN) != newConsentStatus) Utils.safeExecute {
                    listener?.onConsentChange(partnerId)
                }

                else -> Unit
            }
        }
    }

    private fun toConsentStatus(status: Boolean): ConsentStatus =
        if (status) ConsentStatus.GRANTED else ConsentStatus.DENIED

    private fun stringToUsercentricsLoggerLevel(loggerLevel: String?): UsercentricsLoggerLevel {
        return try {
            UsercentricsLoggerLevel.valueOf(loggerLevel?.uppercase() ?: "")
        } catch (e: IllegalArgumentException) {
            UsercentricsLoggerLevel.DEBUG
        }
    }

    private fun resetConsentsAndNotify() {
        consents.forEach {
            Utils.safeExecute {
                listener?.onConsentChange(it.key)
            }
        }
        mutableConsents.clear()
        partnerConsentStatus.clear()
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
