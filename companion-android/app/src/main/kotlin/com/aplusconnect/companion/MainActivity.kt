package com.aplusconnect.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aplusconnect.companion.data.CompanionSettings
import com.aplusconnect.companion.service.CompanionService

/**
 * The setup screen: where the computer is, and the pairing code it showed.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = CompanionSettings(this)

        // The computer's address can arrive as an intent, which is how a
        // future QR code or a link would set it up.
        intent?.getStringExtra(EXTRA_HOST)?.let { settings.host = it }
        intent?.getIntExtra(EXTRA_PORT, 0)?.takeIf { it > 0 }?.let { settings.port = it }
        intent?.getStringExtra(EXTRA_CODE)?.let { settings.pairingCode = it }
        if (intent?.getBooleanExtra(EXTRA_CONNECT, false) == true) {
            CompanionService.start(applicationContext)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    var host by remember { mutableStateOf(settings.host) }
                    var port by remember { mutableStateOf(settings.port.toString()) }
                    var code by remember { mutableStateOf(settings.pairingCode) }
                    var status by remember { mutableStateOf(CompanionService.Status.state) }

                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("A+ Connect Companion", style = MaterialTheme.typography.headlineSmall)

                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("Computer address") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter(Char::isDigit) },
                            label = { Text("Port") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter(Char::isDigit).take(6) },
                            label = { Text("Pairing code") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Button(
                            onClick = {
                                settings.host = host.trim()
                                settings.port = port.toIntOrNull() ?: settings.port
                                settings.pairingCode = code.trim()
                                CompanionService.start(applicationContext)
                                status = "Connecting to ${settings.host}:${settings.port}…"
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Connect") }

                        LaunchedEffect(Unit) {
                            while (true) {
                                status = CompanionService.Status.state
                                delay(500L)
                            }
                        }
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    private companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_CODE = "code"
        const val EXTRA_CONNECT = "connect"
    }
}
