package com.nativeauto.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStateTest {
    @Test fun `an empty DataStore is a new install and shows the setup`() {
        assertTrue(setupPendingFor(storedPreferenceCount = 0))
    }

    @Test fun `any saved preference is an existing install that never sees it`() {
        assertFalse(setupPendingFor(storedPreferenceCount = 1))
        assertFalse(setupPendingFor(storedPreferenceCount = 40))
    }
}
