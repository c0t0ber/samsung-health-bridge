package com.roktober.samsunghealthbridge.sync

import com.google.android.gms.common.api.CommonStatusCodes

internal enum class GoogleAuthorizationFailureDisposition {
    RETRY_LATER,
    NEEDS_USER_ACTION,
}

internal object GoogleAuthorizationFailureClassifier {
    fun classify(statusCode: Int): GoogleAuthorizationFailureDisposition =
        when (statusCode) {
            CommonStatusCodes.NETWORK_ERROR,
            CommonStatusCodes.TIMEOUT,
            CommonStatusCodes.INTERRUPTED,
            CommonStatusCodes.INTERNAL_ERROR,
            -> GoogleAuthorizationFailureDisposition.RETRY_LATER

            else -> GoogleAuthorizationFailureDisposition.NEEDS_USER_ACTION
        }
}
