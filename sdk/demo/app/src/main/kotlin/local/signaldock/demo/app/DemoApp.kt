package local.signaldock.demo.app

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import local.signaldock.demo.core.data.SignalDockClient
import local.signaldock.demo.feature.diagnostics.DiagnosticsRoute
import local.signaldock.demo.feature.diagnostics.DiagnosticsViewModel
import local.signaldock.demo.feature.shop.ShopRoute
import local.signaldock.demo.feature.shop.ShopViewModel

private const val SHOP = "shop"
private const val DIAGNOSTICS = "diagnostics"

/** Navigation and manual composition: feature modules know nothing about each other. */
@Composable
fun DemoApp(client: SignalDockClient) {
    val nav = rememberNavController()
    val current = nav.currentBackStackEntryAsState().value?.destination?.route ?: SHOP
    Scaffold(
        topBar = {
            PrimaryTabRow(selectedTabIndex = if (current == DIAGNOSTICS) 1 else 0, modifier = Modifier.statusBarsPadding()) {
                listOf(SHOP to "Shop", DIAGNOSTICS to "Diagnostics").forEach { (route, label) ->
                    Tab(selected = current == route, onClick = {
                        nav.navigate(route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }, text = { Text(label) })
                }
            }
        },
    ) { inner ->
        NavHost(nav, startDestination = SHOP, modifier = Modifier.padding(inner)) {
            composable(SHOP) {
                val vm: ShopViewModel = viewModel(factory = viewModelFactory {
                    initializer { ShopViewModel(client, createSavedStateHandle()) }
                })
                ShopRoute(vm)
            }
            composable(DIAGNOSTICS) {
                val vm: DiagnosticsViewModel = viewModel(factory = viewModelFactory {
                    initializer { DiagnosticsViewModel(client) }
                })
                DiagnosticsRoute(vm)
            }
        }
    }
}
