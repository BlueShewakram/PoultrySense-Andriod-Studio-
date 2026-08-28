package com.example.poultrysense.ui

import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.example.poultrysense.R
import com.example.poultrysense.data.models.AlertSeverity
import com.example.poultrysense.data.models.FarmAlert
import com.example.poultrysense.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    private val tabOrder = listOf(
        R.id.navigation_home,
        R.id.navigation_monitor,
        R.id.navigation_alerts,
        R.id.navigation_history,
        R.id.navigation_profile
    )
    private var currentTabId = R.id.navigation_home

    val farmAlerts = mutableListOf(
        FarmAlert(
            id = "alert-1",
            severity = AlertSeverity.CRITICAL,
            title = "Chicken #3 Abnormal Posture",
            description = "Extended period of inactivity detected in Zone 1. Isolation recommended.",
            timeAgo = "2 mins ago",
            timestamp = "10:42 AM",
            chickenId = "ID-901",
            location = "Barn A - Zone 1"
        ),
        FarmAlert(
            id = "alert-2",
            severity = AlertSeverity.WARNING,
            title = "Coop B Temperature Spike",
            description = "Temperature exceeded 28°C threshold in Zone 2.",
            timeAgo = "15 mins ago",
            timestamp = "10:29 AM",
            location = "Coop B - Zone 2"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
        navController = navHostFragment.navController

        setupAnimatedBottomNavigation()

        // Notification Bell Click -> Navigate to Alerts tab with spring pop
        binding.btnNotificationBell.setOnClickListener { view ->
            playBellBounce(view) {
                navigateToTab(R.id.navigation_alerts)
            }
        }

        updateNotificationBadge()
    }

    private fun setupAnimatedBottomNavigation() {
        binding.navView.setOnItemSelectedListener { item ->
            if (item.itemId == currentTabId) return@setOnItemSelectedListener false

            val currentIndex = tabOrder.indexOf(currentTabId)
            val targetIndex = tabOrder.indexOf(item.itemId)
            val isForward = targetIndex >= currentIndex

            val navOptions = NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setPopUpTo(R.id.mobile_navigation, false)
                .setEnterAnim(if (isForward) R.anim.slide_in_right else R.anim.slide_in_left)
                .setExitAnim(if (isForward) R.anim.slide_out_left else R.anim.slide_out_right)
                .setPopEnterAnim(if (isForward) R.anim.slide_in_left else R.anim.slide_in_right)
                .setPopExitAnim(if (isForward) R.anim.slide_out_right else R.anim.slide_out_left)
                .build()

            try {
                navController.navigate(item.itemId, null, navOptions)
                currentTabId = item.itemId
                animateBottomNavItem(item.itemId)
                true
            } catch (e: Exception) {
                false
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id in tabOrder && destination.id != currentTabId) {
                currentTabId = destination.id
                binding.navView.menu.findItem(destination.id)?.isChecked = true
                animateBottomNavItem(destination.id)
            }
        }
    }

    private fun animateBottomNavItem(itemId: Int) {
        val itemView = binding.navView.findViewById<View>(itemId)
        if (itemView != null) {
            itemView.animate().cancel()
            itemView.scaleX = 0.78f
            itemView.scaleY = 0.78f
            itemView.animate()
                .scaleX(1.16f)
                .scaleY(1.16f)
                .setDuration(120)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    itemView.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(160)
                        .setInterpolator(OvershootInterpolator(2.4f))
                        .start()
                }
                .start()
        }

        // Tactile spring response on the whole floating dock container
        binding.cardBottomNavContainer.animate().cancel()
        binding.cardBottomNavContainer.scaleX = 0.98f
        binding.cardBottomNavContainer.scaleY = 0.98f
        binding.cardBottomNavContainer.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(1.8f))
            .start()
    }

    private fun playBellBounce(view: View, onEnd: () -> Unit) {
        view.animate()
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(80)
            .withEndAction {
                view.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .setDuration(120)
                    .withEndAction {
                        view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                        onEnd()
                    }
                    .start()
            }
            .start()
    }

    fun updateNotificationBadge() {
        val hasUnread = farmAlerts.any { !it.read }
        binding.notificationBadgeDot.visibility = if (hasUnread) View.VISIBLE else View.GONE
    }

    fun markAllAlertsRead() {
        farmAlerts.forEach { it.read = true }
        updateNotificationBadge()
    }

    fun navigateToTab(destinationId: Int) {
        binding.navView.selectedItemId = destinationId
    }
}
