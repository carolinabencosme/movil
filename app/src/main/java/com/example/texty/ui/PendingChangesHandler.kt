package com.example.texty.ui

/**
 * Contrato para componentes que necesitan confirmar cambios antes de abandonar la vista.
 */
interface PendingChangesHandler {
    fun hasPendingChanges(): Boolean
    fun onAttemptExit(onContinue: () -> Unit)
}
