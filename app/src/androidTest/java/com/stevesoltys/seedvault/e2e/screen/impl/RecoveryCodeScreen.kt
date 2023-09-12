package com.stevesoltys.seedvault.e2e.screen.impl

import com.kaspersky.kaspresso.screens.KScreen
import com.stevesoltys.seedvault.R
import io.github.kakaocup.kakao.text.KButton

object RecoveryCodeScreen : KScreen<RecoveryCodeScreen>() {

    override val layoutId: Int? = null
    override val viewClass: Class<*>? = null

    val confirmCodeButton = KButton { withId(R.id.confirmCodeButton) }

    val verifyCodeButton = KButton { withId(R.id.doneButton) }
}
