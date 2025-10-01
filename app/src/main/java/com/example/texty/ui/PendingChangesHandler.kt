package com.example.texty.ui

interface PendingChangesHandler {
    fun hasPendingChanges(): Boolean
    fun onAttemptExit(onContinue: () -> Unit)
}
