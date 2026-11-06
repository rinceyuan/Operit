package com.ai.assistance.operit.ui.features.githubcopilot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.api.GitHubCopilotAuthManager
import com.ai.assistance.operit.data.api.GitHubCopilotDeviceCode
import com.ai.assistance.operit.data.api.GitHubCopilotProtocol
import com.ai.assistance.operit.data.preferences.GitHubCopilotAuthState
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException

private sealed interface LoginStep {
    data object InputDomain : LoginStep
    data class RequestingCode(val domain: String) : LoginStep
    data class WaitingForUser(val domain: String, val deviceCode: GitHubCopilotDeviceCode) : LoginStep
}

@Composable
fun GitHubCopilotLoginDialog(
    onDismissRequest: () -> Unit,
    onLoginSuccess: (GitHubCopilotAuthState) -> Unit,
) {
    val context = LocalContext.current
    val authManager = remember { GitHubCopilotAuthManager.getInstance(context) }
    var domainInput by remember { mutableStateOf(GitHubCopilotProtocol.DEFAULT_DOMAIN) }
    var domainError by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf<LoginStep>(LoginStep.InputDomain) }

    val currentStep = step
    if (currentStep is LoginStep.RequestingCode) {
        LaunchedEffect(currentStep) {
            try {
                val deviceCode = authManager.requestDeviceCode(currentStep.domain)
                copyToClipboard(context, deviceCode.userCode)
                openUrl(context, deviceCode.verificationUri)
                // 切换步骤会让本 LaunchedEffect 离开组合并被取消，因此放在最后。
                step = LoginStep.WaitingForUser(currentStep.domain, deviceCode)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLogger.e(TAG, "Failed to request GitHub device code", error)
                showLoginFailure(context, error)
                step = LoginStep.InputDomain
            }
        }
    }
    if (currentStep is LoginStep.WaitingForUser) {
        LaunchedEffect(currentStep) {
            try {
                val state = authManager.completeLogin(currentStep.domain, currentStep.deviceCode)
                Toast.makeText(
                    context,
                    context.getString(R.string.github_copilot_login_success, state.login),
                    Toast.LENGTH_LONG,
                ).show()
                onLoginSuccess(state)
                onDismissRequest()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLogger.e(TAG, "GitHub Copilot login failed", error)
                showLoginFailure(context, error)
                onDismissRequest()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.github_copilot_login_title)) },
        text = {
            when (currentStep) {
                LoginStep.InputDomain -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = domainInput,
                        onValueChange = {
                            domainInput = it
                            domainError = null
                        },
                        label = { Text(stringResource(R.string.github_copilot_domain)) },
                        singleLine = true,
                        isError = domainError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = domainError ?: stringResource(R.string.github_copilot_domain_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (domainError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                is LoginStep.RequestingCode -> Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.github_copilot_requesting_code))
                }
                is LoginStep.WaitingForUser -> Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(
                            R.string.github_copilot_device_code_hint,
                            currentStep.deviceCode.verificationUri,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SelectionContainer {
                        Text(
                            text = currentStep.deviceCode.userCode,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copyToClipboard(context, currentStep.deviceCode.userCode) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Text(stringResource(R.string.github_copilot_copy_code))
                        }
                        Button(onClick = { openUrl(context, currentStep.deviceCode.verificationUri) }) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                            Text(stringResource(R.string.github_copilot_open_github))
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.github_copilot_waiting_authorization),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (currentStep == LoginStep.InputDomain) {
                TextButton(
                    onClick = {
                        try {
                            step = LoginStep.RequestingCode(GitHubCopilotProtocol.normalizeDomain(domainInput))
                        } catch (error: IllegalArgumentException) {
                            domainError = context.getString(R.string.github_copilot_domain_invalid)
                        }
                    },
                ) {
                    Text(stringResource(R.string.github_copilot_continue))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel_action))
            }
        },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("GitHub device code", text))
    Toast.makeText(context, context.getString(R.string.github_copilot_code_copied), Toast.LENGTH_SHORT).show()
}

private fun openUrl(context: Context, url: String) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    )
}

private fun showLoginFailure(context: Context, error: Throwable) {
    Toast.makeText(
        context,
        context.getString(R.string.github_copilot_login_failed, error.message.orEmpty()),
        Toast.LENGTH_LONG,
    ).show()
}

private const val TAG = "GitHubCopilotLoginDialog"
