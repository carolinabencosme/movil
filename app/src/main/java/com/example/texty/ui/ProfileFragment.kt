package com.example.texty.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.example.texty.R
import com.example.texty.model.User
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.userProfileChangeRequest
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage

class ProfileFragment : Fragment() {
    private var imageUri: Uri? = null
    private var currentProfile: User? = null

    private fun showToast(message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        val appContext = context?.applicationContext ?: return
        Toast.makeText(appContext, message, duration).show()
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            imageUri = uri
            view?.findViewById<ImageView>(R.id.imageProfile)?.let { imageView ->
                Glide.with(this).load(uri).into(imageView)
            }
        }
    }
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val user = FirebaseAuth.getInstance().currentUser

        val imageProfile = view.findViewById<ImageView>(R.id.imageProfile)
        val nameInput = view.findViewById<TextInputEditText>(R.id.editDisplayName)
        val emailText = view.findViewById<TextView>(R.id.textUserEmail)
        val aboutInput = view.findViewById<TextInputEditText>(R.id.editAbout)
        val phoneInput = view.findViewById<TextInputEditText>(R.id.editPhone)
        val buttonSave = view.findViewById<Button>(R.id.buttonSave)
        val buttonChangePassword = view.findViewById<Button>(R.id.buttonChangePassword)
        val buttonLogout = view.findViewById<Button>(R.id.buttonLogout)
        val buttonEdit = view.findViewById<MaterialButton>(R.id.buttonEdit)

        nameInput.setText(user?.displayName ?: "")
        emailText.text = "Correo: ${user?.email ?: ""}"
        Glide.with(this).load(user?.photoUrl).placeholder(R.drawable.ic_person).into(imageProfile)

        val uid = user?.uid
        if (uid != null) {
            Firebase.firestore.collection("users").document(uid).get()
                .addOnSuccessListener { doc ->
                    val profile = doc.toObject(User::class.java)
                    currentProfile = profile
                    aboutInput.setText(profile?.about ?: "")
                    phoneInput.setText(profile?.phone ?: "")
                }
        }

        imageProfile.setOnClickListener { pickImage.launch("image/*") }
        buttonEdit.setOnClickListener { pickImage.launch("image/*") }

        buttonSave.setOnClickListener {
            val currentUser = user ?: return@setOnClickListener
            val uid = currentUser.uid
            buttonSave.isEnabled = false

            val name = nameInput.text.toString().trim()
            val about = aboutInput.text.toString().trim()
            val phone = phoneInput.text.toString().trim()
            val storageRef = Firebase.storage.reference.child("profileImages/$uid.jpg")

            val previousProfile = currentProfile
            val previousDisplayName = previousProfile?.displayName ?: currentUser.displayName
            val previousAbout = previousProfile?.about
            val previousPhone = previousProfile?.phone
            val previousPhotoUri = previousProfile?.photoUrl?.takeIf { !it.isNullOrBlank() }?.let { Uri.parse(it) }
                ?: currentUser.photoUrl

            fun showErrorToast(message: String?) {
                showToast(message ?: getString(R.string.error_generic), Toast.LENGTH_LONG)
            }

            fun restorePreviousUI() {
                nameInput.setText(previousDisplayName ?: "")
                aboutInput.setText(previousAbout ?: "")
                phoneInput.setText(previousPhone ?: "")
                Glide.with(this)
                    .load(previousPhotoUri)
                    .placeholder(R.drawable.ic_person)
                    .into(imageProfile)
                imageUri = null
            }

            fun handleFailure(exception: Exception?) {
                showErrorToast(exception?.localizedMessage)
                restorePreviousUI()
                buttonSave.isEnabled = true
            }

            fun updateProfile(photoUri: Uri?) {
                val profileUpdates = userProfileChangeRequest {
                    displayName = name
                    this.photoUri = photoUri
                }
                currentUser.updateProfile(profileUpdates)
                    .addOnSuccessListener {
                        val baseProfile = previousProfile ?: User(uid = uid)
                        val profile = baseProfile.copy(
                            uid = uid,
                            displayName = name,
                            photoUrl = photoUri?.toString() ?: baseProfile.photoUrl,
                            isOnline = true,
                            about = about,
                            phone = phone,
                        )
                        Firebase.firestore.collection("users").document(uid).set(profile)
                            .addOnSuccessListener {
                                currentProfile = profile
                                imageUri = null
                                showToast(getString(R.string.profile_saved_success))
                            }
                            .addOnFailureListener { e ->
                                handleFailure(e)
                            }
                            .addOnCompleteListener {
                                buttonSave.isEnabled = true
                            }
                    }
                    .addOnFailureListener { e ->
                        handleFailure(e)
                    }
            }

            val localImage = imageUri
            if (localImage != null) {
                storageRef.putFile(localImage)
                    .continueWithTask { task ->
                        if (!task.isSuccessful) {
                            throw task.exception ?: Exception("Upload failed")
                        }
                        storageRef.downloadUrl
                    }
                    .addOnSuccessListener { downloadUri ->
                        updateProfile(downloadUri)
                    }
                    .addOnFailureListener { e ->
                        handleFailure(e)
                    }
            } else {
                updateProfile(currentUser.photoUrl)
            }
        }

        buttonChangePassword.setOnClickListener {
            val email = user?.email ?: return@setOnClickListener
            FirebaseAuth.getInstance().sendPasswordResetEmail(email)
                .addOnSuccessListener {
                    showToast(getString(R.string.password_reset_email_sent), Toast.LENGTH_LONG)
                }
                .addOnFailureListener { e ->
                    showToast(e.localizedMessage ?: getString(R.string.error_generic), Toast.LENGTH_LONG)
                }
        }

        buttonLogout.setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            requireActivity().finish()
        }
    }
}
