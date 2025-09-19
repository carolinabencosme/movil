package com.example.texty.ui

import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.example.texty.model.ChatRoom
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.ktx.Firebase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ChatListAdapterTest {

    private val currentUid = "current-user"

    @Before
    fun setUp() {
        mockkStatic("com.google.firebase.auth.ktx.FirebaseAuthKt")
        val auth = mockk<FirebaseAuth>()
        val user = mockk<FirebaseUser>()

        every { user.uid } returns currentUid
        every { auth.currentUser } returns user
        every { Firebase.auth } returns auth
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun unreadCountGreaterThanSixShowsCappedLabel() {
        val adapter = ChatListAdapter { }
        val holder = createViewHolder(adapter)
        val room = ChatRoom(
            id = "room-1",
            isGroup = true,
            groupName = "Group",
            unreadCounts = mapOf(currentUid to 7)
        )

        adapter.submitList(listOf(room))
        shadowOf(Looper.getMainLooper()).idle()

        adapter.onBindViewHolder(holder, 0)

        assertEquals(View.VISIBLE, holder.unreadCountText.visibility)
        assertEquals("6+", holder.unreadCountText.text.toString())
    }

    @Test
    fun unreadCountLessOrEqualToSixShowsExactValue() {
        val adapter = ChatListAdapter { }
        val holder = createViewHolder(adapter)
        val room = ChatRoom(
            id = "room-2",
            isGroup = true,
            groupName = "Group",
            unreadCounts = mapOf(currentUid to 3)
        )

        adapter.submitList(listOf(room))
        shadowOf(Looper.getMainLooper()).idle()

        adapter.onBindViewHolder(holder, 0)

        assertEquals(View.VISIBLE, holder.unreadCountText.visibility)
        assertEquals("3", holder.unreadCountText.text.toString())
    }

    private fun createViewHolder(adapter: ChatListAdapter): ChatListAdapter.ChatRoomViewHolder {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val parent = FrameLayout(context)
        return adapter.onCreateViewHolder(parent, 0)
    }
}
