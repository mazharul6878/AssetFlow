package com.example

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.ImageVector

enum class AdminNavTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Dashboard),
    RESOURCES("Resources", Icons.AutoMirrored.Filled.ListAlt),
    REVIEW("Review", Icons.AutoMirrored.Filled.FactCheck),
    CONTROL_PANEL("Control Panel", Icons.Default.Settings)
}
