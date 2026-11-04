package com.example.ui.auth

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.example.data.model.UserProfile
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaBorderLight
import com.example.ui.theme.NovaPrimary
import com.example.ui.theme.NovaPrimarySubtle
import com.example.ui.theme.NovaSurfaceMuted
import com.example.ui.theme.NovaSurfaceWhite
import com.example.ui.theme.NovaTealAccent
import com.example.ui.theme.NovaTealSubtle
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextNavy
import com.example.ui.theme.NovaTextSlate
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.userProfileChangeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale
import java.util.UUID

private enum class AuthStep {
  ROLE_SELECTION,
  LOGIN
}

@Composable
fun AuthScreen(
  onAuthenticated: (selectedPortalRole: String) -> Unit,
  externalAuthError: String? = null,
  onClearExternalAuthError: () -> Unit = {}
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val auth = remember {
    try {
      FirebaseAuth.getInstance()
    } catch (_: Exception) {
      null
    }
  }

  var currentStep by remember { mutableStateOf(AuthStep.ROLE_SELECTION) }
  var selectedRole by remember { mutableStateOf("End User") } // "Admin" or "End User"

  var isRegisterMode by remember { mutableStateOf(false) }
  var fullName by remember { mutableStateOf("") }
  var nickname by remember { mutableStateOf("") }
  var email by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var confirmPassword by remember { mutableStateOf("") }
  var termsAccepted by remember { mutableStateOf(false) }
  var showForgotPasswordDialog by remember { mutableStateOf(false) }
  var resetEmail by remember { mutableStateOf("") }
  var resetMessage by remember { mutableStateOf<String?>(null) }
  var isResetLoading by remember { mutableStateOf(false) }
  var isLoading by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  val legacyGoogleSignInLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) { result ->
    scope.launch {
      isLoading = true
      try {
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        val account = task.getResult(ApiException::class.java)
        val idToken = account?.idToken
        if (idToken.isNullOrBlank()) {
          errorMessage = "Google Sign-In did not return an ID token. Please use Email & Password Sign-In below."
        } else {
          val firebaseCred = GoogleAuthProvider.getCredential(idToken, null)
          val authResult = auth?.signInWithCredential(firebaseCred)?.await()
          val uid = authResult?.user?.uid
          if (!uid.isNullOrBlank()) {
            onAuthenticated(selectedRole)
          }
        }
      } catch (e: ApiException) {
        Log.e("AuthScreen", "Legacy GoogleSignIn ApiException: code=${e.statusCode}", e)
        errorMessage = when (e.statusCode) {
          12501 -> "Google Sign-In was cancelled."
          10 -> "Google Sign-In configuration mismatch (Error 10). Please use Email & Password Sign-In."
          else -> "Google Sign-In failed (Code ${e.statusCode}). Please use Email & Password Sign-In."
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e("AuthScreen", "Legacy GoogleSignIn error", e)
        errorMessage = e.localizedMessage ?: "Google Sign-In failed. Please use Email & Password Sign-In."
      } finally {
        isLoading = false
      }
    }
  }

  Surface(
    modifier = Modifier
      .fillMaxSize()
      .testTag("auth_screen_root"),
    color = NovaBackground
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      val activeBannerError = externalAuthError?.takeIf { it.isNotBlank() } ?: errorMessage
      if (!activeBannerError.isNullOrBlank()) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.errorContainer,
          modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 460.dp)
            .padding(bottom = 16.dp)
            .testTag("auth_error_banner")
        ) {
          Text(
            text = activeBannerError,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(14.dp)
          )
        }
      }

      AnimatedVisibility(
        visible = currentStep == AuthStep.ROLE_SELECTION,
        enter = fadeIn(),
        exit = fadeOut()
      ) {
        RoleSelectionStep(
          onSelectRole = { role ->
            selectedRole = role
            errorMessage = null
            onClearExternalAuthError()
            currentStep = AuthStep.LOGIN
          }
        )
      }

      AnimatedVisibility(
        visible = currentStep == AuthStep.LOGIN,
        enter = fadeIn(),
        exit = fadeOut()
      ) {
        LoginCredentialsStep(
          selectedRole = selectedRole,
          isRegisterMode = isRegisterMode,
          fullName = fullName,
          nickname = nickname,
          email = email,
          password = password,
          isLoading = isLoading,
          errorMessage = errorMessage,
          onToggleRegisterMode = {
            isRegisterMode = !isRegisterMode
            confirmPassword = ""
            termsAccepted = false
            errorMessage = null
            onClearExternalAuthError()
          },
          confirmPassword = confirmPassword,
          onConfirmPasswordChange = {
            confirmPassword = it
            if (errorMessage != null) errorMessage = null
            onClearExternalAuthError()
          },
          termsAccepted = termsAccepted,
          onTermsAcceptedChange = {
            termsAccepted = it
            if (errorMessage != null) errorMessage = null
            onClearExternalAuthError()
          },
          onForgotPasswordClick = {
            resetEmail = email.trim()
            resetMessage = null
            showForgotPasswordDialog = true
          },
          onFullNameChange = {
            fullName = it
            if (errorMessage != null) errorMessage = null
            onClearExternalAuthError()
          },
          onNicknameChange = {
            nickname = it
            if (errorMessage != null) errorMessage = null
            onClearExternalAuthError()
          },
          onEmailChange = {
            email = it
            if (errorMessage != null) errorMessage = null
            onClearExternalAuthError()
          },
          onPasswordChange = {
            password = it
            if (errorMessage != null) errorMessage = null
            onClearExternalAuthError()
          },
          onBackToRoleSelection = {
            errorMessage = null
            onClearExternalAuthError()
            currentStep = AuthStep.ROLE_SELECTION
          },
          onGoogleSignInClick = {
            errorMessage = null
            onClearExternalAuthError()
            if (auth == null) {
              errorMessage = "Firebase Authentication is not configured in this build."
              return@LoginCredentialsStep
            }
            scope.launch {
              isLoading = true
              try {
                val webClientId = context.getString(R.string.default_web_client_id).trim()
                if (webClientId.isEmpty() || webClientId.startsWith("YOUR_")) {
                  errorMessage = "Web Client ID is not configured in strings.xml."
                  isLoading = false
                  return@launch
                }

                val credentialManager = CredentialManager.create(context)
                val googleIdOption = GetSignInWithGoogleOption.Builder(webClientId)
                  .setNonce(UUID.randomUUID().toString())
                  .build()

                val request = GetCredentialRequest.Builder()
                  .addCredentialOption(googleIdOption)
                  .build()

                val result = credentialManager.getCredential(
                  request = request,
                  context = context
                )

                val credential = result.credential
                if (credential is CustomCredential &&
                  credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                  val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                  val firebaseCred = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                  val authResult = auth.signInWithCredential(firebaseCred).await()
                  val uid = authResult.user?.uid
                  if (!uid.isNullOrBlank()) {
                    onAuthenticated(selectedRole)
                  }
                } else {
                  errorMessage = "Unexpected credential type returned from Google Sign-In."
                }
              } catch (e: NoCredentialException) {
                Log.w("AuthScreen", "NoCredentialException from CredentialManager; launching GoogleSignIn fallback", e)
                try {
                  val webClientId = context.getString(R.string.default_web_client_id).trim()
                  val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(webClientId)
                    .requestEmail()
                    .build()
                  val googleSignInClient = GoogleSignIn.getClient(context, gso)
                  legacyGoogleSignInLauncher.launch(googleSignInClient.signInIntent)
                } catch (_: Exception) {
                  errorMessage = "No Google Account signed in on this device. Please sign in with Email & Password below."
                }
              } catch (_: GetCredentialCancellationException) {
                errorMessage = "Google Sign-In was cancelled."
              } catch (e: GetCredentialException) {
                Log.w("AuthScreen", "GetCredentialException (${e.type}); launching GoogleSignIn fallback", e)
                try {
                  val webClientId = context.getString(R.string.default_web_client_id).trim()
                  val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(webClientId)
                    .requestEmail()
                    .build()
                  val googleSignInClient = GoogleSignIn.getClient(context, gso)
                  legacyGoogleSignInLauncher.launch(googleSignInClient.signInIntent)
                } catch (_: Exception) {
                  errorMessage = "Google Sign-In unavailable (${e.message}). Please use Email & Password below."
                }
              } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("AuthScreen", "Google Sign-In error", e)
                errorMessage = e.localizedMessage ?: "Google Sign-In failed. Please use Email & Password."
              } finally {
                isLoading = false
              }
            }
          },
          onEmailSubmitClick = {
            errorMessage = null
            onClearExternalAuthError()
            val trimmedEmail = email.trim()
            val trimmedPass = password.trim()
            val trimmedConfirmPass = confirmPassword.trim()
            val trimmedName = fullName.trim()

            if (trimmedEmail.isEmpty() || trimmedPass.isEmpty()) {
              errorMessage = "Email and password are required."
              return@LoginCredentialsStep
            }
            if (isRegisterMode && trimmedName.isEmpty()) {
              errorMessage = "Full Name is required when creating an account."
              return@LoginCredentialsStep
            }
            if (trimmedPass.length < 6) {
              errorMessage = "Password must be at least 6 characters."
              return@LoginCredentialsStep
            }
            if (isRegisterMode && trimmedPass != trimmedConfirmPass) {
              errorMessage = "Passwords do not match. Please re-enter confirm password."
              return@LoginCredentialsStep
            }
            if (isRegisterMode && !termsAccepted) {
              errorMessage = "You must agree to the Terms of Service & Privacy Policy to create an account."
              return@LoginCredentialsStep
            }
            if (auth == null) {
              errorMessage = "Firebase Authentication is not available."
              return@LoginCredentialsStep
            }

            scope.launch {
              isLoading = true
              try {
                val authResult = if (isRegisterMode) {
                  val res = auth.createUserWithEmailAndPassword(trimmedEmail, trimmedPass).await()
                  if (trimmedName.isNotEmpty()) {
                    val profileUpdates = userProfileChangeRequest {
                      displayName = trimmedName
                    }
                    res.user?.updateProfile(profileUpdates)?.await()
                  }
                  res
                } else {
                  auth.signInWithEmailAndPassword(trimmedEmail, trimmedPass).await()
                }

                val uid = authResult.user?.uid
                if (!uid.isNullOrBlank()) {
                  onAuthenticated(selectedRole)
                }
              } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("AuthScreen", "Email auth error", e)
                errorMessage = formatFriendlyFirebaseAuthError(e, isRegisterMode)
              } finally {
                isLoading = false
              }
            }
          }
        )
      }

      if (showForgotPasswordDialog) {
        AlertDialog(
          onDismissRequest = {
            if (!isResetLoading) {
              showForgotPasswordDialog = false
              resetMessage = null
            }
          },
          title = { Text("Reset Password", fontWeight = FontWeight.Bold) },
          text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              Text(
                text = "Enter your registered email address and we'll send you a secure password reset link.",
                style = MaterialTheme.typography.bodyMedium
              )
              OutlinedTextField(
                value = resetEmail,
                onValueChange = {
                  resetEmail = it
                  resetMessage = null
                },
                label = { Text("Email Address") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("reset_password_email_input"),
                shape = RoundedCornerShape(12.dp)
              )
              if (!resetMessage.isNullOrBlank()) {
                Text(
                  text = resetMessage!!,
                  color = if (resetMessage!!.contains("sent", ignoreCase = true)) NovaTealAccent else MaterialTheme.colorScheme.error,
                  style = MaterialTheme.typography.bodySmall
                )
              }
            }
          },
          confirmButton = {
            Button(
              onClick = {
                val targetResetEmail = resetEmail.trim()
                if (targetResetEmail.isEmpty()) {
                  resetMessage = "Please enter your email address."
                  return@Button
                }
                if (auth == null) {
                  resetMessage = "Firebase Authentication is not available."
                  return@Button
                }
                scope.launch {
                  isResetLoading = true
                  try {
                    auth.sendPasswordResetEmail(targetResetEmail).await()
                    resetMessage = "Password reset email sent! Check your inbox."
                  } catch (e: Exception) {
                    resetMessage = e.localizedMessage ?: "Failed to send reset email. Verify email is valid."
                  } finally {
                    isResetLoading = false
                  }
                }
              },
              enabled = !isResetLoading,
              modifier = Modifier.testTag("send_reset_password_button")
            ) {
              if (isResetLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
              } else {
                Text("Send Link")
              }
            }
          },
          dismissButton = {
            TextButton(
              onClick = {
                showForgotPasswordDialog = false
                resetMessage = null
              },
              enabled = !isResetLoading
            ) {
              Text("Cancel")
            }
          }
        )
      }
    }
  }
}

@Composable
private fun RoleSelectionStep(
  onSelectRole: (String) -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .widthIn(max = 460.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Surface(
      modifier = Modifier.size(64.dp),
      shape = RoundedCornerShape(18.dp),
      color = NovaPrimarySubtle
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = Icons.Default.VerifiedUser,
          contentDescription = "AssetFlow Logo",
          tint = NovaPrimary,
          modifier = Modifier.size(34.dp)
        )
      }
    }

    Spacer(modifier = Modifier.height(20.dp))

    Text(
      text = "AssetFlow",
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
      color = NovaTextNavy,
      textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(6.dp))

    Text(
      text = "Fixed Asset Verification System — Choose Account Type",
      style = MaterialTheme.typography.bodyMedium,
      color = NovaTextMuted,
      textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(32.dp))

    RoleSelectionCard(
      title = "Admin",
      subtitle = "Manage system, users and clients",
      icon = Icons.Default.AdminPanelSettings,
      accentColor = NovaPrimary,
      accentBgColor = NovaPrimarySubtle,
      testTag = "select_role_admin_card",
      onClick = { onSelectRole("Admin") }
    )

    Spacer(modifier = Modifier.height(16.dp))

    RoleSelectionCard(
      title = "End User (Auditor)",
      subtitle = "Count assets and submit sheets",
      icon = Icons.Default.Badge,
      accentColor = NovaTealAccent,
      accentBgColor = NovaTealSubtle,
      testTag = "select_role_end_user_card",
      onClick = { onSelectRole("End User") }
    )

    Spacer(modifier = Modifier.height(28.dp))

    Surface(
      shape = RoundedCornerShape(12.dp),
      color = NovaSurfaceMuted,
      border = BorderStroke(1.dp, NovaBorderLight),
      modifier = Modifier.fillMaxWidth()
    ) {
      Row(
        modifier = Modifier.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          imageVector = Icons.Default.Security,
          contentDescription = null,
          tint = NovaTextMuted,
          modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
          text = "Role access is verified against your registered account and enforced by Firebase Security Rules.",
          style = MaterialTheme.typography.bodySmall,
          color = NovaTextMuted
        )
      }
    }
  }
}

@Composable
private fun RoleSelectionCard(
  title: String,
  subtitle: String,
  icon: ImageVector,
  accentColor: Color,
  accentBgColor: Color,
  testTag: String,
  onClick: () -> Unit
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .testTag(testTag),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = NovaSurfaceWhite),
    border = BorderStroke(1.dp, NovaBorderLight),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(20.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Surface(
        modifier = Modifier.size(52.dp),
        shape = RoundedCornerShape(14.dp),
        color = accentBgColor
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = icon,
            contentDescription = title,
            tint = accentColor,
            modifier = Modifier.size(28.dp)
          )
        }
      }

      Spacer(modifier = Modifier.width(16.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          color = NovaTextNavy
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = NovaTextMuted
        )
      }

      Spacer(modifier = Modifier.width(8.dp))

      Surface(
        modifier = Modifier.size(36.dp),
        shape = CircleShape,
        color = NovaSurfaceMuted
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "Continue as $title",
            tint = NovaTextSlate,
            modifier = Modifier.size(18.dp)
          )
        }
      }
    }
  }
}

@Composable
private fun LoginCredentialsStep(
  selectedRole: String,
  isRegisterMode: Boolean,
  fullName: String,
  nickname: String,
  email: String,
  password: String,
  isLoading: Boolean,
  errorMessage: String?,
  confirmPassword: String = "",
  onConfirmPasswordChange: (String) -> Unit = {},
  termsAccepted: Boolean = false,
  onTermsAcceptedChange: (Boolean) -> Unit = {},
  onForgotPasswordClick: () -> Unit = {},
  onToggleRegisterMode: () -> Unit,
  onFullNameChange: (String) -> Unit,
  onNicknameChange: (String) -> Unit,
  onEmailChange: (String) -> Unit,
  onPasswordChange: (String) -> Unit,
  onBackToRoleSelection: () -> Unit,
  onGoogleSignInClick: () -> Unit,
  onEmailSubmitClick: () -> Unit
) {
  var isPasswordVisible by remember { mutableStateOf(false) }
  var isConfirmPasswordVisible by remember { mutableStateOf(false) }
  val isRoleAdmin = selectedRole == "Admin"
  val roleAccentColor = if (isRoleAdmin) NovaPrimary else NovaTealAccent
  val roleBadgeBg = if (isRoleAdmin) NovaPrimarySubtle else NovaTealSubtle

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .widthIn(max = 440.dp),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = NovaSurfaceWhite),
    border = BorderStroke(1.dp, NovaBorderLight),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        TextButton(
          onClick = onBackToRoleSelection,
          enabled = !isLoading,
          modifier = Modifier.testTag("back_to_role_selection_button")
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Change Role",
            tint = NovaTextSlate,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Change Role",
            color = NovaTextSlate,
            style = MaterialTheme.typography.labelLarge
          )
        }

        Surface(
          shape = RoundedCornerShape(20.dp),
          color = roleBadgeBg
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = if (isRoleAdmin) Icons.Default.AdminPanelSettings else Icons.Default.Badge,
              contentDescription = null,
              tint = roleAccentColor,
              modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = if (isRoleAdmin) "Admin Portal" else "End User (Auditor)",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              color = roleAccentColor
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      Text(
        text = if (isRegisterMode) "Create $selectedRole Account" else "Sign in as $selectedRole",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = NovaTextNavy,
        textAlign = TextAlign.Center
      )

      Spacer(modifier = Modifier.height(4.dp))

      Text(
        text = if (isRoleAdmin) {
          "Access client configuration, review queues, and administration."
        } else {
          "Count assets on assigned clients and submit daily sheets."
        },
        style = MaterialTheme.typography.bodySmall,
        color = NovaTextMuted,
        textAlign = TextAlign.Center
      )

      Spacer(modifier = Modifier.height(24.dp))

      Button(
        onClick = onGoogleSignInClick,
        enabled = !isLoading,
        modifier = Modifier
          .fillMaxWidth()
          .height(50.dp)
          .testTag("google_sign_in_button"),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = NovaPrimary,
          contentColor = Color.White
        )
      ) {
        Icon(
          imageVector = Icons.Default.AccountCircle,
          contentDescription = null,
          modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
          text = "Continue with Google",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold
        )
      }

      Spacer(modifier = Modifier.height(20.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = NovaBorderLight)
        Text(
          text = "  OR EMAIL & PASSWORD  ",
          style = MaterialTheme.typography.labelSmall,
          color = NovaTextMuted
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = NovaBorderLight)
      }

      Spacer(modifier = Modifier.height(16.dp))

      if (isRegisterMode) {
        OutlinedTextField(
          value = fullName,
          onValueChange = onFullNameChange,
          label = { Text("Full Name *") },
          leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("auth_name_input"),
          shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = nickname,
          onValueChange = onNicknameChange,
          label = { Text("Photo Nickname (e.g. rahim)") },
          supportingText = { Text("Used for photo filenames: {nickname}_{asset_id}.jpg") },
          leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null) },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("auth_nickname_input"),
          shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
      }

      OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        label = { Text("Email Address") },
        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        singleLine = true,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("auth_email_input"),
        shape = RoundedCornerShape(12.dp)
      )

      Spacer(modifier = Modifier.height(10.dp))

      OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("Password") },
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
        trailingIcon = {
          IconButton(
            onClick = { isPasswordVisible = !isPasswordVisible },
            modifier = Modifier.testTag("auth_password_visibility_toggle")
          ) {
            Icon(
              imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
              contentDescription = if (isPasswordVisible) "Hide password" else "Show password"
            )
          }
        },
        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("auth_password_input"),
        shape = RoundedCornerShape(12.dp)
      )

      if (isRegisterMode) {
        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = confirmPassword,
          onValueChange = onConfirmPasswordChange,
          label = { Text("Confirm Password") },
          leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
          trailingIcon = {
            IconButton(
              onClick = { isConfirmPasswordVisible = !isConfirmPasswordVisible },
              modifier = Modifier.testTag("auth_confirm_password_visibility_toggle")
            ) {
              Icon(
                imageVector = if (isConfirmPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = if (isConfirmPasswordVisible) "Hide password" else "Show password"
              )
            }
          },
          visualTransformation = if (isConfirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("auth_confirm_password_input"),
          shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("auth_terms_row")
        ) {
          Checkbox(
            checked = termsAccepted,
            onCheckedChange = onTermsAcceptedChange,
            modifier = Modifier.testTag("auth_terms_checkbox")
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = "I agree to the Terms of Service & Privacy Policy",
            style = MaterialTheme.typography.bodySmall,
            color = NovaTextSlate
          )
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End
        ) {
          TextButton(
            onClick = onForgotPasswordClick,
            enabled = !isLoading,
            modifier = Modifier.testTag("forgot_password_button")
          ) {
            Text(
              text = "Forgot Password?",
              style = MaterialTheme.typography.bodySmall,
              color = NovaPrimary,
              fontWeight = FontWeight.SemiBold
            )
          }
        }
      }

      if (errorMessage != null) {
        Spacer(modifier = Modifier.height(12.dp))
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = MaterialTheme.colorScheme.errorContainer,
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = errorMessage,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
              .padding(12.dp)
              .testTag("auth_error_text")
          )
        }
      }

      Spacer(modifier = Modifier.height(18.dp))

      OutlinedButton(
        onClick = onEmailSubmitClick,
        enabled = !isLoading,
        modifier = Modifier
          .fillMaxWidth()
          .height(48.dp)
          .testTag("email_auth_submit_button"),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.5.dp, NovaPrimary)
      ) {
        if (isLoading) {
          CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = NovaPrimary
          )
        } else {
          Text(
            text = if (isRegisterMode) "Create Account" else "Sign In",
            fontWeight = FontWeight.Bold,
            color = NovaPrimary
          )
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      TextButton(
        onClick = onToggleRegisterMode,
        enabled = !isLoading,
        modifier = Modifier.testTag("toggle_register_mode_button")
      ) {
        Text(
          text = if (isRegisterMode) {
            "Already have an account? Sign In"
          } else {
            "Need an account? Create Account"
          },
          color = NovaTextSlate
        )
      }
    }
  }
}

private fun formatFriendlyFirebaseAuthError(e: Exception, isRegisterMode: Boolean): String {
  val raw = e.message.orEmpty()
  return when {
    e is FirebaseAuthInvalidUserException || raw.contains("USER_NOT_FOUND", ignoreCase = true) ->
      "AUTH-01: Unregistered account — No account exists with this email. Tap 'Create Account' below to register."
    e is FirebaseAuthInvalidCredentialsException ||
      raw.contains("INVALID_LOGIN_CREDENTIALS", ignoreCase = true) ||
      raw.contains("ERROR_WRONG_PASSWORD", ignoreCase = true) ->
      if (isRegisterMode) {
        "Please enter a valid email address and password (6+ characters)."
      } else {
        "AUTH-01: Invalid email or wrong password."
      }
    e is FirebaseAuthUserCollisionException || raw.contains("EMAIL_EXISTS", ignoreCase = true) ->
      "This email is already registered. Switch to 'Sign In' below."
    else -> e.localizedMessage ?: "Authentication failed. Please check your credentials."
  }
}

@Composable
fun UserProfileDialog(
  userProfile: UserProfile,
  onSaveProfile: (displayName: String, role: String, nickname: String) -> Unit,
  onDismiss: () -> Unit
) {
  var nameInput by remember(userProfile.displayName, userProfile.fullName) {
    mutableStateOf(userProfile.effectiveDisplayName())
  }
  var nicknameInput by remember(userProfile.nickname) {
    mutableStateOf(userProfile.effectiveNickname())
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = "User Profile & Photo Nickname",
        fontWeight = FontWeight.Bold
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          text = "Your Full Name is automatically filled into 'Counted By'. Your Nickname is used for Google Drive photo filenames ({nickname}_{asset_id}.jpg).",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (userProfile.email.isNotBlank()) {
          Text(
            text = "Signed in as: ${userProfile.email}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
          )
        }

        Surface(
          shape = RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.secondaryContainer,
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = "Assigned Role: ${userProfile.role} (Managed by Administrator)",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
          )
        }

        OutlinedTextField(
          value = nameInput,
          onValueChange = { nameInput = it },
          label = { Text("Full Name *") },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_name_input")
        )

        OutlinedTextField(
          value = nicknameInput,
          onValueChange = { nicknameInput = it },
          label = { Text("Photo Nickname *") },
          supportingText = {
            val preview = UserProfile.sanitizeNickname(nicknameInput.ifBlank { nameInput })
            Text("Filename preview: ${preview}_FA1001.jpg")
          },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_nickname_input")
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (nameInput.isNotBlank()) {
            onSaveProfile(
              nameInput.trim(),
              userProfile.role,
              UserProfile.sanitizeNickname(nicknameInput.ifBlank { nameInput })
            )
            onDismiss()
          }
        },
        enabled = nameInput.isNotBlank(),
        modifier = Modifier.testTag("save_profile_button")
      ) {
        Text("Save Profile")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}

fun signOutUser(
  credentialManager: CredentialManager,
  onSignOutComplete: () -> Unit,
  scope: kotlinx.coroutines.CoroutineScope
) {
  scope.launch {
    try {
      FirebaseAuth.getInstance().signOut()
      credentialManager.clearCredentialState(androidx.credentials.ClearCredentialStateRequest())
    } catch (_: Exception) {
    } finally {
      onSignOutComplete()
    }
  }
}
