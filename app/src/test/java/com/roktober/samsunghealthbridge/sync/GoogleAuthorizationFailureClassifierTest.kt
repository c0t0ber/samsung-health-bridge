package com.roktober.samsunghealthbridge.sync

import com.google.android.gms.common.api.CommonStatusCodes
import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleAuthorizationFailureClassifierTest {
    @Test
    fun `network failure retries without asking the user`() {
        assertEquals(
            GoogleAuthorizationFailureDisposition.RETRY_LATER,
            GoogleAuthorizationFailureClassifier.classify(CommonStatusCodes.NETWORK_ERROR),
        )
    }

    @Test
    fun `timeout and interruption retry without asking the user`() {
        assertEquals(
            GoogleAuthorizationFailureDisposition.RETRY_LATER,
            GoogleAuthorizationFailureClassifier.classify(CommonStatusCodes.TIMEOUT),
        )
        assertEquals(
            GoogleAuthorizationFailureDisposition.RETRY_LATER,
            GoogleAuthorizationFailureClassifier.classify(CommonStatusCodes.INTERRUPTED),
        )
    }

    @Test
    fun `sign in failure still requires user action`() {
        assertEquals(
            GoogleAuthorizationFailureDisposition.NEEDS_USER_ACTION,
            GoogleAuthorizationFailureClassifier.classify(CommonStatusCodes.SIGN_IN_REQUIRED),
        )
    }
}
