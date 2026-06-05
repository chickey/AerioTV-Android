package com.aeriotv.android.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aeriotv.android.ui.adaptive.rememberViewport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairDispatcharrScreen(
    onBack: () -> Unit,
    onManualSetup: () -> Unit,
    onApproved: (com.aeriotv.android.core.sync.DispatcharrPairingStatusResponse) -> Unit,
    isConfiguring: Boolean = false,
    configurationError: String? = null,
    viewModel: PairDispatcharrViewModel = hiltViewModel(),
) {
    val vp = rememberViewport()
    val discoveryState by viewModel.state.collectAsState()
    val pairingState by viewModel.pairingState.collectAsState()
    var approvalConsumed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.discover()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Pair Dispatcharr",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = vp.gutter, vertical = 20.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = if (vp.onboardingMaxWidth != androidx.compose.ui.unit.Dp.Unspecified) {
                    Modifier.widthIn(max = vp.onboardingMaxWidth).fillMaxWidth()
                } else {
                    Modifier.fillMaxWidth()
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                PairingHero()

                when (val state = discoveryState) {
                    PairDispatcharrState.Idle,
                    PairDispatcharrState.Scanning -> {
                        DiscoveryStatusCard(
                            title = "Searching for Dispatcharr",
                            body = "Looking for an AerioTV Dispatcharr plugin on this network.",
                            endpoint = "/api/plugins/aeriotv/capabilities",
                        )
                    }
                    PairDispatcharrState.NotFound -> {
                        DiscoveryStatusCard(
                            title = "No AerioTV Dispatcharr plugin found",
                            body = "Install and enable the AerioTV plugin in Dispatcharr, then scan again. Manual setup still works without the plugin.",
                            endpoint = "/api/plugins/aeriotv/capabilities",
                        )
                        Button(
                            onClick = { viewModel.discover() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White,
                            ),
                        ) {
                            Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                            Spacer(Modifier.padding(horizontal = 4.dp))
                            Text("Scan again")
                        }
                    }
                    is PairDispatcharrState.Found -> {
                        FoundDispatcharrCard(
                            baseUrl = state.result.baseUrl,
                            pluginVersion = state.result.capabilities.pluginVersion,
                            endpoint = state.result.capabilities.pairingEndpoint,
                        )

                        LaunchedEffect(state.result.baseUrl) {
                            viewModel.startPairing(state.result)
                        }

                        when (val pairing = pairingState) {
                            PairingFlowState.Idle,
                            PairingFlowState.Starting -> {
                                DiscoveryStatusCard(
                                    title = "Starting pairing",
                                    body = "Asking the Dispatcharr plugin for a short pairing code.",
                                    endpoint = state.result.capabilities.pairingEndpoint,
                                )
                            }
                            is PairingFlowState.Waiting -> {
                                PairingCodeCard(code = pairing.pairing.code)
                                Text(
                                    text = "Enter this code in the AerioTV Dispatcharr plugin, then run Approve pairing. AerioTV will continue checking automatically.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            is PairingFlowState.Approved -> {
                                ApprovedCard(approved = pairing.approved)
                                LaunchedEffect(pairing.approved.deviceId, pairing.approved.deviceToken) {
                                    if (!approvalConsumed) {
                                        approvalConsumed = true
                                        onApproved(pairing.approved)
                                    }
                                }
                                if (isConfiguring) {
                                    DiscoveryStatusCard(
                                        title = "Configuring AerioTV",
                                        body = "Saving the paired Dispatcharr server and loading channels.",
                                        endpoint = "/api/channels/channels/",
                                    )
                                }
                                if (!configurationError.isNullOrBlank()) {
                                    DiscoveryStatusCard(
                                        title = "Configuration needs attention",
                                        body = configurationError,
                                        endpoint = "/api/channels/channels/",
                                    )
                                }
                            }
                            is PairingFlowState.Failed -> {
                                DiscoveryStatusCard(
                                    title = "Pairing could not continue",
                                    body = pairing.message,
                                    endpoint = state.result.capabilities.pairingEndpoint,
                                )
                                Button(
                                    onClick = { viewModel.startPairing(state.result) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = Color.White,
                                    ),
                                ) {
                                    Icon(imageVector = Icons.Filled.Sync, contentDescription = null)
                                    Spacer(Modifier.padding(horizontal = 4.dp))
                                    Text("Try pairing again")
                                }
                            }
                        }
                    }
                }

                OutlinedButton(onClick = onManualSetup) {
                    Text("Use manual setup instead")
                }
            }
        }
    }
}

@Composable
private fun PairingHero() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
                .padding(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Hub,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Connect without typing passwords",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "AerioTV will pair with the Dispatcharr plugin and receive a scoped device token.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DiscoveryStatusCard(
    title: String,
    body: String,
    endpoint: String,
) {
    PairingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.padding(horizontal = 5.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Probe: $endpoint",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FoundDispatcharrCard(
    baseUrl: String,
    pluginVersion: String,
    endpoint: String,
) {
    PairingCard {
        Text(
            text = "Dispatcharr plugin found",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Server: $baseUrl",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Plugin: $pluginVersion",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Endpoint: $endpoint",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PairingCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(18.dp),
        content = content,
    )
}

@Composable
private fun PairingCodeCard(code: String) {
    PairingCard {
        Text(
            text = "Enter this code in the Dispatcharr AerioTV plugin",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = code,
            style = MaterialTheme.typography.displayMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.38f))
                .padding(vertical = 18.dp),
        )
    }
}

@Composable
private fun ApprovedCard(approved: com.aeriotv.android.core.sync.DispatcharrPairingStatusResponse) {
    PairingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Sync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.padding(horizontal = 5.dp))
            Text(
                text = "Pairing approved",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Server: ${approved.serverBaseUrl.orEmpty()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Device: ${approved.deviceId.orEmpty()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
