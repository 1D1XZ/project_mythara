package com.mythara.services

/**
 * Pattern-based classifier for "junk" notifications Mythara
 * auto-dismisses after ingesting whatever signal is worth keeping
 * (the metadata still flows into the audit log + autoprocess queue
 * — the user just doesn't get the visual noise in their shade).
 *
 * Three signal sources, OR-ed:
 *
 *  1. Package allowlist of always-promotional senders (marketing
 *     blasts, loyalty programs, browser push notifications, etc.).
 *
 *  2. Text patterns — case-insensitive substring match against the
 *     title + body for words like "% off", "deal", "credit card
 *     payment due", "rewards earned", "OTP".
 *
 *  3. Notification category — Android exposes CATEGORY_PROMO,
 *     CATEGORY_RECOMMENDATION, CATEGORY_TRANSPORT on apps that
 *     bother to tag.
 *
 * Conservative on purpose. False-positive (a real message gets
 * dismissed) is worse than false-negative (a promo survives in the
 * shade). When in doubt, leave it alone.
 *
 * Not class-loaded — pure top-level functions for testability and
 * zero-DI surface.
 */
object PromoNotificationClassifier {

    /**
     * @return true if this notification should be auto-dismissed
     *         after the listener has captured it for the agent's
     *         downstream pipelines (audit log, auto-reply triage,
     *         etc.). Caller still saves images + emits to the
     *         SharedFlow.
     */
    fun shouldAutoDismiss(
        packageName: String,
        category: String?,
        title: String?,
        text: String?,
    ): Boolean {
        if (packageName in ALWAYS_PROMO_PACKAGES) return true
        if (category != null && category in PROMO_CATEGORIES) return true
        val blob = "${title.orEmpty()} ${text.orEmpty()}".lowercase()
        if (blob.isBlank()) return false
        // Multi-pattern OR: any single match triggers dismissal.
        // Patterns are deliberately tight (whole phrases or specific
        // keyword combos) so a normal message that mentions one of
        // these words in passing doesn't get wiped.
        for (pat in PROMO_PHRASES) {
            if (blob.contains(pat)) return true
        }
        return false
    }

    /**
     * Apps Mythara has empirically seen do nothing but promo /
     * marketing pushes. Add to this list when a user reports recurring
     * shade noise from a new package. Keep it tight — anything that
     * occasionally sends real messages stays OFF this list.
     */
    private val ALWAYS_PROMO_PACKAGES = setOf(
        // Browser push subscriptions — almost always promotional.
        "com.android.chrome", // chrome push notif channel
        // Loyalty / shopping push channels (extend as needed)
        "com.zomato.android",
        "com.swiggy.android",
        "in.amazon.mShop.android.shopping",
        "com.flipkart.android",
        "com.myntra.android",
        // E-mail marketing pings
        "com.google.android.gm.lite",
    )

    private val PROMO_CATEGORIES = setOf(
        "promo",
        "recommendation",
    )

    /**
     * Text patterns. Tight matches only — full phrases or distinct
     * keyword combinations. A bare "credit" is NOT here because real
     * messages talk about credits all the time; "credit card payment
     * due" is specific enough to be junk-bill-pay reminder spam.
     *
     * Add patterns from real-world false-negatives the user reports.
     */
    private val PROMO_PHRASES = listOf(
        // Discount / sale spam
        "% off",
        "limited time offer",
        "flash sale",
        "ends tonight",
        "ends today",
        "ends in 24 hours",
        "deal of the day",
        "lightning deal",
        "exclusive offer",
        "claim your reward",
        "claim your gift",
        "you've earned",
        "rewards earned",
        "loyalty points",
        // Credit card / banking promo (NOT transactional)
        "credit card payment due",
        "credit card bill due",
        "credit card statement is ready",
        "your statement is ready",
        "loan offer",
        "personal loan",
        "pre-approved loan",
        "pre-approved offer",
        "upgrade to platinum",
        "free credit score",
        // OTP / verification (we don't need to clutter shade — the user
        // typically uses Mythara's auto-fill anyway; an OTP they DIDN'T
        // expect is still captured in the audit log)
        "otp ", // trailing space so we don't match "otp" inside a word
        " otp:",
        "is your otp",
        "is your verification code",
        // App engagement spam
        "miss you",
        "haven't logged in",
        "come back to",
        "your weekly summary",
        "your daily horoscope",
        // Newsletter / digest
        "your daily digest",
        "this week in",
    )
}
