package net.discdd.trick.navigation

/**
 * Sealed class defining app screen routes for NavHost.
 */
sealed class Screen(val route: String) {
    data object ContactsList : Screen("contacts")
    data object Chat : Screen("chat")
    data object KeyExchange : Screen("key_exchange")
}
