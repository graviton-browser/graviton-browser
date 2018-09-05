package net.plan99.graviton

import javafx.geometry.Pos
import tornadofx.*

class LogoView : Fragment() {
    @Suppress("ConstantConditionIf")
    override val root = hbox {
        alignment = Pos.CENTER
        imageview(appBrandLogo)
        if (!appBrandLogoIsName)
            label("graviton") { addClass(Styles.logoText) }
    }
}
