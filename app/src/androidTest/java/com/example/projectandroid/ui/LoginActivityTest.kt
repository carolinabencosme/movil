package com.example.projectandroid.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.projectandroid.R
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.ktx.Firebase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginActivityTest {

    @Before
    fun setup() {
        Intents.init()
        mockkObject(Firebase)
    }

    @After
    fun tearDown() {
        unmockkAll()
        Intents.release()
    }

    @Test
    fun clickingRegisterNavigatesToRegisterActivity() {
        ActivityScenario.launch(LoginActivity::class.java)
        onView(withId(R.id.buttonRegister)).perform(click())
        intended(hasComponent(RegisterActivity::class.java.name))
    }

    @Test
    fun successfulLoginNavigatesToMainActivity() {
        val auth: FirebaseAuth = mockk()
        val task = mockk<com.google.android.gms.tasks.Task<AuthResult>>()
        every { Firebase.auth } returns auth
        every { auth.signInWithEmailAndPassword(any(), any()) } returns task
        every { task.addOnSuccessListener(any()) } answers {
            firstArg<com.google.android.gms.tasks.OnSuccessListener<AuthResult>>().onSuccess(mockk())
            task
        }
        every { task.addOnFailureListener(any()) } returns task

        ActivityScenario.launch(LoginActivity::class.java)
        onView(withId(R.id.editEmail)).perform(replaceText("user@test.com"), closeSoftKeyboard())
        onView(withId(R.id.editPassword)).perform(replaceText("123456"), closeSoftKeyboard())
        onView(withId(R.id.buttonLogin)).perform(click())
        intended(hasComponent(MainActivity::class.java.name))
    }
}
