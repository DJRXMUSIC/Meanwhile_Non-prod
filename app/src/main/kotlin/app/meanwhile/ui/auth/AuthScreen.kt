package app.meanwhile.ui.auth

import app.meanwhile.ui.common.rememberSafeScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.meanwhile.ui.common.LocalAppContainer
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(onSkip: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberSafeScope()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> String?) {
        busy = true
        message = null
        scope.launch {
            message = block()
            busy = false
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Meanwhile", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Sign in to back up every record to your Supabase project and restore it on a new phone. " +
                    "First time: enter an email and password and tap Create account.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
                    onClick = { run { c.auth.signIn(email, password).exceptionOrNull()?.let { "Sign-in failed: ${it.message}" } } },
                ) { Text("Sign in") }
                OutlinedButton(
                    enabled = !busy && email.isNotBlank() && password.length >= 6,
                    onClick = {
                        run {
                            c.auth.signUp(email, password).fold(
                                onSuccess = { signedIn -> if (signedIn) null else "Account created. Confirm the email Supabase sent, then sign in." },
                                onFailure = { "Couldn't create account: ${it.message}" },
                            )
                        }
                    },
                ) { Text("Create account") }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onSkip) { Text("Use without an account for now (local only)") }
        }
    }
}
