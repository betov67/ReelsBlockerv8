package com.example.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ReelsBlockerAccessibilityService — v8
 *
 * v8 vs v7 — WHAT ACTUALLY CHANGED
 * -------------------------------------
 * Nothing behavioral. v7 already implemented exactly the policy v8's spec
 * asks for: allow the first Reel in a session unconditionally, block any
 * scroll/swipe/page-change the instant it tries to reach a second one,
 * reset only on leaving the Reel viewer for a real non-Reel screen. v8's
 * prompt restates that same policy and asks for two things:
 *
 *   1. Renamed logcat tags: `REEL_OPENED_INDEX_1` / `REEL_SCROLL_BLOCKED_
 *      INDEX_2` (v7) are now `REEL_INITIAL_OPEN_ALLOWED` /
 *      `REEL_SCROLL_ATTEMPT_BLOCKED` (v8). Done below — same call sites,
 *      new strings.
 *   2. An explicit callout that an APP-INITIATED auto-advance to the next
 *      video must be blocked exactly like a manual swipe, not just a
 *      user-dragged gesture. No code change was needed for this: on the
 *      Android accessibility tree there is no separate signal for "the
 *      ViewPager paged because the user swiped" vs. "the ViewPager paged
 *      on its own" — both surface as the same TYPE_VIEW_SCROLLED/page-
 *      transition event on the Reel viewer, which handleScroll() already
 *      intercepts unconditionally. So auto-advance was already covered;
 *      this version just documents that explicitly instead of leaving it
 *      implicit.
 *
 * Because the underlying policy is unchanged, so is everything v7 already
 * removed: no DM/Profile "origin chrome" ids, no reelOriginAllowed/
 * currentSessionLabel/currentSessionSource bookkeeping, no bottom-nav
 * Reels-tab immediate-block rule, no REELS_TAB_IDS/containsIdFragment().
 * Origin still doesn't matter — only how far into the session you are.
 *
 * WHAT IS DELIBERATELY UNCHANGED
 * ---------------------------------
 *  • Story bypass (rule 1 below) — STORY_EXCLUSIVE_IDS / STORY_CLASS_HINTS
 *    / isStoryScreen() are byte-for-byte what v5/v6/v7 shipped, still
 *    checked first, before anything else, every event.
 *  • classifyScreen()'s priority order — DM_THREAD and PROFILE are still
 *    checked BEFORE REEL_VIEWER (the original "Bug A" fix). This still
 *    matters even though origin no longer drives any allow/block decision:
 *    without it, a DM thread or Profile screen that happens to also match
 *    a Reel viewer id could get misclassified as REEL_VIEWER, which would
 *    start a bogus Reel session and then treat ordinary message-list/grid
 *    scrolling as a swipe to a second Reel — firing BACK on completely
 *    normal navigation. DM_THREAD_IDS/PROFILE_IDS therefore stay exactly
 *    as they were.
 *  • All v4 try/catch crash-hardening, and BlockerForegroundService's
 *    watchdog contract via the `isConnected` flag (Watchdog/ForegroundService
 *    stability fixes) — untouched.
 *
 * RULES IMPLEMENTED
 * ---------------------
 *  1. Stories are 100% exempt — checked first, before anything else, on
 *     every single event, with zero side effects.
 *  2. The first Reel in a brand-new viewer session (Index #1 / first view)
 *     is always allowed to play, regardless of entry point.
 *  3. Any scroll/swipe/page-transition event inside that same session,
 *     once the first video has been shown — whether a manual swipe or the
 *     player auto-advancing on its own — is blocked immediately via
 *     fireBack(). Includes a defensive fail-safe path for a scroll seen
 *     with no recorded session start.
 *  4. The reelIndex counter/session state resets to 0 only when the user
 *     exits the Reel player back to any other screen (Feed, Profile,
 *     Direct Chat, Search, etc).
 *  5. Logging under the `ReelsBlockerA11y` tag marks the two key
 *     transitions explicitly: `REEL_INITIAL_OPEN_ALLOWED` and
 *     `REEL_SCROLL_ATTEMPT_BLOCKED` (the latter logged on every blocked
 *     attempt in a session, including the rare fallback path where BACK
 *     doesn't exit the viewer and another scroll is observed before it
 *     does).
 */
class ReelsBlockerAccessibilityService : AccessibilityService() {

    private enum class ScreenType { REEL_VIEWER, DM_THREAD, PROFILE, OTHER }

    companion object {
        private const val TAG = "ReelsBlockerA11y"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"

        /** Minimum gap between two performGlobalAction(BACK) calls. */
        private const val BACK_ACTION_COOLDOWN_MS = 600L

        /**
         * True while the system is actively bound to this service. Read by
         * BlockerForegroundService's watchdog.
         */
        @Volatile
        var isConnected: Boolean = false
            private set

        // ── STORY signals — unchanged since v5 (confirmed working) ─────
        // Instagram's internal name for a Story has, for years, been
        // "Reel" (singular) — predating the separate short-video "Reels"
        // feature. These ids/classes are Stories' own, not a shared/
        // ambiguous container.
        private val STORY_EXCLUSIVE_IDS = setOf(
            "reel_viewer", "reel_viewer_image_view", "story_feed_holder",
            "reel_tag",
            "story_reply_composer", "reply_composer", "story_progress_bar",
            "story_duration_bar", "story_progress_container", "stories_tray",
            "story_viewer_swipe_container", "story_viewer_reply_box",
            "quick_reaction_emoji_picker", "stories_header",
            "story_viewer_fragment_container"
        )

        private val STORY_CLASS_HINTS = listOf("reelviewer", "stor")

        // ── REEL/CLIPS PLAYER signals — unchanged since v5 ──────────────
        private val REEL_VIEWER_IDS = setOf(
            "clips_viewer_view_pager", "clips_swipe_refresh_container",
            "clips_video_container", "clips_fragment_root",
            "clips_caption_text_view", "clips_tab_fragment_container",
            "reels_viewer_fragment_root",
            "ad_unit_root", "sponsored_reel_root"
        )

        private val REEL_CLASS_HINTS = listOf("clips", "reelsviewer")

        // ── DM thread (conversation LIST) screen — used by classifyScreen's
        // priority routing (Bug A fix). Kept EXCLUSIVE to the thread/list
        // screen on purpose — a live message composer or message-recycler
        // view doesn't make sense showing over a full-screen video, so
        // these ids won't fire once the Reel viewer actually opens, and the
        // screen correctly falls through to REEL_VIEWER at that point.
        private val DM_THREAD_IDS = setOf(
            "direct_thread_fragment", "direct_thread_recycler_view",
            "thread_view_messages_recycler_view", "row_thread_composer_edittext",
            "direct_text_message_text_view", "direct_private_share", "direct_thread"
        )

        private val DM_CLASS_HINTS = listOf("direct")

        // ── Profile grid screen — used by classifyScreen's priority
        // routing. Kept exclusive to the header/grid screen for the same
        // reason as DM_THREAD_IDS above.
        private val PROFILE_IDS = setOf(
            "profile_header", "profile_tab_layout", "action_bar_profile_name",
            "profile_coordinator_layout", "user_detail_header",
            "profile_header_bio_text"
        )

        private val PROFILE_CLASS_HINTS = listOf("profile")

        // Only structural tree changes can possibly mean a Reel viewer
        // just appeared or disappeared. A single integer AND against this
        // mask — no timer, no debounce.
        private val STRUCTURAL_CHANGE_MASK: Int = run {
            var mask = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mask = mask or AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED
                mask = mask or AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE
            }
            mask
        }
    }

    // ── Mutable session state ──
    // inReelSession: whether we're currently inside a Reel-viewer screen,
    // for ANY origin — v7 no longer cares which.
    @Volatile private var inReelSession = false

    // reelIndex: 0 when not in a session; 1 for the first (always-allowed)
    // video; bumped on every blocked scroll/auto-advance attempt
    // thereafter purely for internal bookkeeping (every one of those is
    // logged under the same REEL_SCROLL_ATTEMPT_BLOCKED tag) — in practice
    // a single fireBack() normally exits the viewer and resets this to 0
    // before a second blocked attempt could ever happen.
    @Volatile private var reelIndex = 0

    @Volatile private var lastBackActionMs = 0L

    // ─── Lifecycle ─────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            inReelSession = false
            reelIndex = 0
            isConnected = true
            Log.i(TAG, "onServiceConnected — fresh state, isConnected=true")
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected failed", e)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        Log.w(TAG, "onUnbind — isConnected=false")
        return try {
            super.onUnbind(intent)
        } catch (e: Exception) {
            Log.e(TAG, "super.onUnbind failed", e)
            false
        }
    }

    override fun onDestroy() {
        isConnected = false
        Log.w(TAG, "onDestroy — isConnected=false")
        try {
            super.onDestroy()
        } catch (e: Exception) {
            Log.e(TAG, "super.onDestroy failed", e)
        }
    }

    override fun onInterrupt() { /* no-op */ }

    // ─── Top-level event dispatch ───────────────────────────────────────
    //
    // Everything below runs inside ONE outer try/catch. No exception
    // thrown anywhere in this class is allowed to escape this method.

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            handleEvent(event)
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled exception in onAccessibilityEvent — swallowed to protect the service", e)
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        val pkg = try {
            event.packageName?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read event.packageName", e)
            null
        }
        if (pkg != INSTAGRAM_PACKAGE) return

        val root = safeRoot()

        // ── RULE 1, HIGHEST PRIORITY: Stories are 100% exempt. ──────────
        if (root != null && isStoryScreen(root)) {
            Log.i(TAG, "Classified: STORY -> bypass, zero side effects (className='${safeClassName(root)}')")
            return
        }

        val eventType = try {
            event.eventType
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read event.eventType", e)
            return
        }

        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                evaluateCurrentScreen(root)

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isStructuralChange(event)) evaluateCurrentScreen(root)
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleScroll(root)

            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClick(root)

            else -> { /* not relevant to any rule */ }
        }
    }

    private fun safeRoot(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (e: Exception) {
        Log.w(TAG, "rootInActiveWindow threw", e)
        null
    }

    private fun safeClassName(root: AccessibilityNodeInfo): String = try {
        root.className?.toString() ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun isStructuralChange(event: AccessibilityEvent): Boolean = try {
        (event.contentChangeTypes and STRUCTURAL_CHANGE_MASK) != 0
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read event.contentChangeTypes", e)
        false
    }

    // ─── Core evaluation — window-state changes and structural content changes ──

    private fun evaluateCurrentScreen(root: AccessibilityNodeInfo?) {
        try {
            if (root == null) return
            val screenType = classifyScreen(root) ?: return // couldn't classify safely; leave state untouched
            val cls = safeClassName(root)

            when (screenType) {
                ScreenType.REEL_VIEWER -> {
                    if (!inReelSession) {
                        // Brand-new Reel session. v7: the first video
                        // (Index #1) is ALWAYS allowed, no matter where it
                        // was opened from (Feed, Reels tab, DM, Profile, a
                        // direct link). No fireBack() here.
                        inReelSession = true
                        reelIndex = 1
                        Log.i(TAG, "REEL_INITIAL_OPEN_ALLOWED — new session, first video allowed, className='$cls'")
                    } else {
                        // Same session re-rendering (ad swap, caption
                        // change, like-button animation, pane title
                        // update, …). The index hasn't moved — only an
                        // explicit scroll/swipe/page-change event
                        // (handleScroll) represents moving to the next
                        // video, so there's nothing to do here.
                        Log.d(TAG, "REEL_VIEWER re-render inside existing session (index=$reelIndex) — no state change, className='$cls'")
                    }
                }

                else -> {
                    // DM_THREAD, PROFILE, or OTHER: a genuine non-Reel
                    // screen. This is the only place session state resets,
                    // per spec: reset only on exiting back to a non-Reel
                    // screen.
                    if (inReelSession) {
                        Log.i(TAG, "Exited Reel viewer to $screenType — resetting session state (was index=$reelIndex)")
                    } else {
                        Log.i(TAG, "Classified: $screenType (non-reel screen) className='$cls'")
                    }
                    inReelSession = false
                    reelIndex = 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "evaluateCurrentScreen failed", e)
        }
    }

    /** The only way to move to the NEXT Reel — this is what rule 3 blocks. */
    private fun handleScroll(root: AccessibilityNodeInfo?) {
        try {
            if (root == null) return

            // Defense in depth: the top-level dispatcher already excluded
            // Stories for this event, but re-check independently.
            if (isStoryScreen(root)) return

            val type = classifyScreen(root) ?: return
            if (type != ScreenType.REEL_VIEWER) return // scrolling somewhere else entirely (e.g. a DM thread's message list, a profile grid)

            if (!inReelSession) {
                // Scrolled inside a Reel viewer but never saw a window-
                // state/content event marking its start (a coalesced
                // event). We can't tell for certain this isn't already an
                // attempt to move past the first video, so fail safe
                // toward blocking rather than risk letting an unverified
                // scroll reach Index #2 unblocked.
                inReelSession = true
                reelIndex = 1
                Log.w(TAG, "Scroll detected with no prior session state — failing safe, treating session as already at index 1")
            }

            reelIndex += 1
            Log.i(TAG, "REEL_SCROLL_ATTEMPT_BLOCKED — swipe/page-transition/auto-advance detected inside Reel viewer (attempted index=$reelIndex), blocking immediately")
            fireBack()
        } catch (e: Exception) {
            Log.e(TAG, "handleScroll failed", e)
        }
    }

    /**
     * Taps. v7 has no "block this specific button" rule anymore — the
     * bottom-nav Reels tab is just another entry point now, and its first
     * Reel is allowed exactly like any other. This remains only as a
     * defensive fallback for entry points that register solely as a click
     * with no accompanying window-state change (e.g. an inline thumbnail in
     * the feed).
     */
    private fun handleClick(root: AccessibilityNodeInfo?) {
        try {
            if (!inReelSession) evaluateCurrentScreen(root)
        } catch (e: Exception) {
            Log.e(TAG, "handleClick failed", e)
        }
    }

    // ─── Screen classification ───────────────────────────────────────────

    /**
     * Returns null (meaning "skip this cycle, don't touch any state") if
     * classification itself throws.
     *
     * Priority order is deliberate: DM_THREAD and PROFILE are checked
     * BEFORE REEL_VIEWER (Bug A, see class doc comment) — this still
     * matters in v7 even though origin no longer drives any allow/block
     * decision, because a misclassified DM/Profile screen would otherwise
     * start a bogus Reel session and treat normal list/grid scrolling as a
     * swipe to the next Reel.
     */
    private fun classifyScreen(root: AccessibilityNodeInfo): ScreenType? = try {
        when {
            isDmThreadScreen(root) -> ScreenType.DM_THREAD
            isProfileScreen(root) -> ScreenType.PROFILE
            isReelViewerScreen(root) -> ScreenType.REEL_VIEWER
            else -> ScreenType.OTHER
        }
    } catch (e: Exception) {
        Log.e(TAG, "classifyScreen failed", e)
        null
    }

    /**
     * Fails TOWARD "yes, it's a Story" on any internal error — rule 1 says
     * Stories must never be touched, so an error here must never result in
     * a wrongful BACK.
     */
    private fun isStoryScreen(root: AccessibilityNodeInfo): Boolean = try {
        hasAnyId(root, STORY_EXCLUSIVE_IDS, "STORY") || classNameContainsAny(root, STORY_CLASS_HINTS, "STORY")
    } catch (e: Exception) {
        Log.w(TAG, "isStoryScreen failed — defaulting to 'is a Story' to avoid ever blocking one", e)
        true
    }

    private fun isReelViewerScreen(root: AccessibilityNodeInfo): Boolean = try {
        hasAnyId(root, REEL_VIEWER_IDS, "REEL_VIEWER") || classNameContainsAny(root, REEL_CLASS_HINTS, "REEL_VIEWER")
    } catch (e: Exception) {
        Log.w(TAG, "isReelViewerScreen failed", e)
        false
    }

    private fun isDmThreadScreen(root: AccessibilityNodeInfo): Boolean = try {
        hasAnyId(root, DM_THREAD_IDS, "DM_THREAD") || classNameContainsAny(root, DM_CLASS_HINTS, "DM_THREAD")
    } catch (e: Exception) {
        Log.w(TAG, "isDmThreadScreen failed", e)
        false
    }

    private fun isProfileScreen(root: AccessibilityNodeInfo): Boolean = try {
        hasAnyId(root, PROFILE_IDS, "PROFILE") || classNameContainsAny(root, PROFILE_CLASS_HINTS, "PROFILE")
    } catch (e: Exception) {
        Log.w(TAG, "isProfileScreen failed", e)
        false
    }

    /**
     * Uses the platform's own indexed id lookup instead of a manual
     * recursive tree walk. Each id is looked up in its OWN try/catch: a
     * failure on one id logs a warning and moves on to the next instead of
     * aborting the whole check. Logs the specific id that matched, tagged
     * with `category`, so a real device run shows exactly which signal
     * fired.
     */
    private fun hasAnyId(root: AccessibilityNodeInfo, shortIds: Set<String>, category: String): Boolean {
        for (shortId in shortIds) {
            try {
                val fullId = "$INSTAGRAM_PACKAGE:id/$shortId"
                val matches = root.findAccessibilityNodeInfosByViewId(fullId)
                if (matches != null && matches.isNotEmpty()) {
                    Log.d(TAG, "MATCH[$category] viewId='$shortId'")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "hasAnyId[$category]: lookup failed for id='$shortId'", e)
                // Keep checking the remaining ids.
            }
        }
        return false
    }

    private fun classNameContainsAny(root: AccessibilityNodeInfo, fragments: List<String>, category: String): Boolean {
        return try {
            val cls = root.className?.toString() ?: return false
            val hit = fragments.firstOrNull { cls.contains(it, ignoreCase = true) }
            if (hit != null) {
                Log.d(TAG, "MATCH[$category] className='$cls' matchedFragment='$hit'")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "classNameContainsAny[$category] failed", e)
            false
        }
    }

    // ─── Back action ──────────────────────────────────────────────────────

    private fun fireBack() {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastBackActionMs < BACK_ACTION_COOLDOWN_MS) return
            lastBackActionMs = now
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            Log.e(TAG, "performGlobalAction(GLOBAL_ACTION_BACK) failed", e)
        }
    }
}
