package app.meanwhile.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.meanwhile.R
import app.meanwhile.ui.common.SyncIndicator
import app.meanwhile.ui.nav.Routes
import app.meanwhile.ui.theme.LocalGlucoseColors
import app.meanwhile.ui.theme.forMgDl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onOpen: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meanwhile") },
                actions = {
                    SyncIndicator(onClick = { onOpen(Routes.SETTINGS) })
                    IconButton(onClick = { onOpen(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "—",
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = LocalGlucoseColors.current.forMgDl(null),
            )
            Text("mg/dL · waiting for CGM (arrives in M3)", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Meal, factor, dose or feedback…") },
                )
                FilledIconButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(64.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = "Speak")
                }
            }
        }
    }
}
