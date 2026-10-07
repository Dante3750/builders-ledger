@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.buildersledger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.buildersledger.domain.Village
import com.buildersledger.ui.components.VillageDialog
import com.buildersledger.ui.screens.HomeScreen
import com.buildersledger.ui.screens.ImportScreen
import com.buildersledger.ui.screens.InsightsScreen
import com.buildersledger.ui.screens.PlannerScreen
import com.buildersledger.ui.screens.ProgressScreen
import com.buildersledger.ui.screens.ResourcesScreen
import com.buildersledger.ui.screens.SettingsScreen

private object Route {
    const val Home = "home"
    const val Planner = "planner"
    const val Resources = "resources"
    const val Insights = "insights"
    const val Import = "import"
    const val Settings = "settings"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Route.Home, "Board", Icons.Default.Home),
    Tab(Route.Planner, "Planner", Icons.Default.DateRange),
    Tab(Route.Resources, "Resources", Icons.Default.ShoppingCart),
    Tab(Route.Insights, "Insights", Icons.Default.Star),
)
private val tabRoutes = tabs.map { it.route }.toSet()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerApp(vm: LedgerViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Route.Home
    val isTab = route in tabRoutes

    val villages by vm.villages.collectAsStateWithLifecycle()
    val village by vm.currentVillage.collectAsStateWithLifecycle()
    var showNewVillage by remember { mutableStateOf(false) }

    fun goTab(target: String) {
        nav.navigate(target) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isTab) {
                        VillageSwitcher(
                            villages = villages.orEmpty(),
                            current = village,
                            onSelect = { vm.selectVillage(it) },
                            onNew = { showNewVillage = true },
                        )
                    } else {
                        Text(
                            when (route) {
                                Route.Import -> "Sync from export"
                                Route.Settings -> "Settings"
                                else -> "Builder's Ledger"
                            }
                        )
                    }
                },
                navigationIcon = {
                    if (!isTab) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (isTab) {
                        IconButton(onClick = { nav.navigate(Route.Import) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Sync from export")
                        }
                        IconButton(onClick = { nav.navigate(Route.Settings) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (isTab) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { goTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController = nav, startDestination = Route.Home) {
            composable(Route.Home) {
                HomeScreen(
                    vm = vm,
                    padding = padding,
                    onCreateVillage = { showNewVillage = true },
                    onOpenImport = { nav.navigate(Route.Import) },
                )
            }
            composable(Route.Planner) { PlannerScreen(vm, padding) }
            composable(Route.Resources) { ResourcesScreen(vm, padding) }
            composable(Route.Insights) {
                InsightsHost(vm, padding, onOpenSettings = { nav.navigate(Route.Settings) })
            }
            composable(Route.Import) { ImportScreen(vm, padding, onDone = { goTab(Route.Home) }) }
            composable(Route.Settings) { SettingsScreen(vm, padding) }
        }
    }

    if (showNewVillage) {
        VillageDialog(
            initial = null,
            onDismiss = { showNewVillage = false },
            onSave = { name, townHall, builders ->
                vm.createVillage(name, townHall, builders)
                showNewVillage = false
            },
        )
    }
}

@Composable
private fun VillageSwitcher(
    villages: List<Village>,
    current: Village?,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.clickable { expanded = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = current?.name ?: "Builder's Ledger",
                style = MaterialTheme.typography.titleLarge,
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch village")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            villages.forEach { v ->
                DropdownMenuItem(
                    text = { Text("${v.name} · TH${v.townHall}") },
                    onClick = {
                        onSelect(v.id)
                        expanded = false
                    },
                )
            }
            if (villages.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text("New village…") },
                onClick = {
                    expanded = false
                    onNew()
                },
            )
        }
    }
}

/** Insights has two views behind a toggle, which keeps the bottom bar at four tabs. */
@Composable
private fun InsightsHost(vm: LedgerViewModel, padding: PaddingValues, onOpenSettings: () -> Unit) {
    var showProgress by remember { mutableStateOf(false) }
    val labels = listOf("Activity", "Progress")
    Column(Modifier.padding(padding)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = (index == 1) == showProgress,
                    onClick = { showProgress = index == 1 },
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                ) { Text(label) }
            }
        }
        Box(Modifier.weight(1f)) {
            if (showProgress) {
                ProgressScreen(vm, PaddingValues(0.dp), onOpenSettings)
            } else {
                InsightsScreen(vm, PaddingValues(0.dp))
            }
        }
    }
}
