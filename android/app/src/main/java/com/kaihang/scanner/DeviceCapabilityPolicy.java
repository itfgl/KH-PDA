package com.kaihang.scanner;

final class DeviceCapabilityPolicy {
    private DeviceCapabilityPolicy() {}

    static boolean shouldShowCameraScanButton(
        boolean pageHasScanAction,
        boolean capabilitiesResolved,
        boolean pdaScannerAvailable,
        boolean pdaPrinterAvailable,
        boolean cameraAvailable
    ) {
        return pageHasScanAction
            && cameraAvailable;
    }
}
