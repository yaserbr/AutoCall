package com.yaser8541.autocallapp

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.*
import com.facebook.react.uimanager.ViewManager

class DirectCallPackage : ReactPackage {
    override fun createNativeModules(reactContext: ReactApplicationContext)
        = listOf(
            DirectCallModule(reactContext),
            AutoCallNativeModule(reactContext),
            ScreenMirrorModule(reactContext)
        )

    override fun createViewManagers(reactContext: ReactApplicationContext)
        = emptyList<ViewManager<*, *>>()
}
