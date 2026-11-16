package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.UserProfile
import com.example.data.repository.BladeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode { SIGN_IN, SIGN_UP, PHONE_OTP, FORGOT_PASSWORD }

data class AuthUiState(
    val mode: AuthMode = AuthMode.SIGN_IN,
    val isLoading: Boolean = false,
    val otpSent: Boolean = false,
    val suggestSignUp: Boolean = false,
    val infoMessage: String? = null,
    val errorMessage: String? = null
)

class AuthViewModel(private val repository: BladeRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun setMode(mode: AuthMode) {
        _uiState.update {
            it.copy(
                mode = mode,
                errorMessage = null,
                infoMessage = null,
                otpSent = false,
                suggestSignUp = false
            )
        }
    }

    fun signInWithEmail(email: String, password: String, onAuthenticated: (UserProfile) -> Unit) {
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter email and password.", suggestSignUp = false) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, suggestSignUp = false) }
            repository.signInWithEmail(cleanEmail, password).fold(
                onSuccess = { profile ->
                    _uiState.update { it.copy(isLoading = false, suggestSignUp = false) }
                    onAuthenticated(profile)
                },
                onFailure = { err ->
                    val msg = err.message ?: "Sign in failed"
                    val isInvalidCreds = msg.contains("invalid", ignoreCase = true) ||
                        msg.contains("create account", ignoreCase = true)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = msg,
                            suggestSignUp = isInvalidCreds
                        )
                    }
                }
            )
        }
    }

    fun signUpWithEmail(
        email: String,
        password: String,
        username: String = "",
        displayName: String = "",
        onAuthenticated: (UserProfile) -> Unit
    ) {
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            _uiState.update { it.copy(errorMessage = "Please enter a valid email address.", suggestSignUp = false) }
            return
        }
        if (password.length < 6) {
            _uiState.update { it.copy(errorMessage = "Password must be at least 6 characters.", suggestSignUp = false) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, suggestSignUp = false) }
            repository.signUpWithEmail(cleanEmail, password, username, displayName).fold(
                onSuccess = { profile ->
                    _uiState.update { it.copy(isLoading = false, suggestSignUp = false) }
                    onAuthenticated(profile)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Sign up failed") }
                }
            )
        }
    }

    fun signInWithGoogleToken(idToken: String, onAuthenticated: (UserProfile) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repository.signInWithGoogleIdToken(idToken).fold(
                onSuccess = { profile ->
                    _uiState.update { it.copy(isLoading = false) }
                    onAuthenticated(profile)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Google sign-in failed") }
                }
            )
        }
    }

    fun sendPhoneOtp(phoneE164: String) {
        if (!phoneE164.startsWith("+") || phoneE164.length < 8) {
            _uiState.update { it.copy(errorMessage = "Enter phone in international format (e.g. +88017...)") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repository.sendPhoneOtp(phoneE164).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(isLoading = false, otpSent = true, infoMessage = "OTP code sent to $phoneE164")
                    }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Failed to send OTP") }
                }
            )
        }
    }

    fun verifyPhoneOtp(phoneE164: String, code: String, onAuthenticated: (UserProfile) -> Unit) {
        if (code.length < 4) {
            _uiState.update { it.copy(errorMessage = "Enter the 6-digit verification code.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repository.verifyPhoneOtp(phoneE164, code).fold(
                onSuccess = { profile ->
                    _uiState.update { it.copy(isLoading = false) }
                    onAuthenticated(profile)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Verification failed") }
                }
            )
        }
    }

    fun sendPasswordReset(email: String) {
        if (email.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter your account email.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repository.sendPasswordReset(email).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(isLoading = false, infoMessage = "Password reset link sent to $email.")
                    }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Reset failed") }
                }
            )
        }
    }

    fun setError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }
}
