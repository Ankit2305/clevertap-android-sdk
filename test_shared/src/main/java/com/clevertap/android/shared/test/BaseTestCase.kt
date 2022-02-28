package com.clevertap.android.shared.test

import android.os.Build.VERSION_CODES
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clevertap.android.sdk.CleverTapAPI
import com.clevertap.android.sdk.CleverTapInstanceConfig
import org.junit.*
import org.junit.runner.*
import org.mockito.*
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication


/**
 * Naming Convention for Testing
 * 1. Classes : <Name of Class to be Test> + Test.kt
 *      e.g CTProductConfigControllerTest.kt for CTProductConfigController.java
 *
 * 2. Methods : test_<methodName>_<inputCondition>_<expectedBehavior>
 *     e.g test_constructor_whenFeatureFlagIsNotSave_InitShouldReturnTrue
 */
@Config(manifest = Config.NONE, sdk = [VERSION_CODES.P], application = TestApplication::class)
@RunWith(AndroidJUnit4::class)
abstract class BaseTestCase {

    protected lateinit var application: TestApplication
    protected lateinit var shadowApplication: ShadowApplication
    protected lateinit var cleverTapAPI: CleverTapAPI
    protected lateinit var cleverTapInstanceConfig: CleverTapInstanceConfig
    protected lateinit var activityController: ActivityController<TestActivity>

    @Before
    open fun setUp() {
        application = TestApplication.application
        shadowApplication = Shadows.shadowOf(application)
        cleverTapAPI = Mockito.mock(CleverTapAPI::class.java)
        cleverTapInstanceConfig = CleverTapInstanceConfig.createInstance(application, Constant.ACC_ID, Constant.ACC_TOKEN)
        activityController = Robolectric.buildActivity(TestActivity::class.java)

    }
}