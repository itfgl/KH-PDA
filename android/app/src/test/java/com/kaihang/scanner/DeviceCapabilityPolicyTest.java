package com.kaihang.scanner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DeviceCapabilityPolicyTest {
    @Test
    public void showsCameraOnlyForScanPagesOnNonPdaDevices() {
        assertTrue(DeviceCapabilityPolicy.shouldShowCameraScanButton(true, true, false, false, true));
        assertFalse(DeviceCapabilityPolicy.shouldShowCameraScanButton(false, true, false, false, true));
        assertFalse(DeviceCapabilityPolicy.shouldShowCameraScanButton(true, true, true, false, true));
        assertFalse(DeviceCapabilityPolicy.shouldShowCameraScanButton(true, true, false, true, true));
        assertFalse(DeviceCapabilityPolicy.shouldShowCameraScanButton(true, false, false, false, true));
        assertFalse(DeviceCapabilityPolicy.shouldShowCameraScanButton(true, true, false, false, false));
    }
}
