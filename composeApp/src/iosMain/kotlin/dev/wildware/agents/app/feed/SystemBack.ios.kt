package dev.wildware.agents.app.feed

/**
 * The iPhone's back swipe: UIKit's screen-edge pan from the left, through Compose's navigation
 * events. It gets a swipe that starts at the very edge of the screen, which iOS holds back from
 * the app's own touch handling. So it owns the left edge, and the feed opens its drawer from it too.
 */
internal actual val PlatformSystemBack: SystemBack = NavigationSystemBack(ownsLeftEdge = true)
