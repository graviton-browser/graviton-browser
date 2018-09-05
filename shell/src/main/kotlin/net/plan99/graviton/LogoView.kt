package net.plan99.graviton

import javafx.geometry.Pos
import tornadofx.*

class LogoView : Fragment() {
    override val root = hbox {
        alignment = Pos.CENTER
        imageview(appBrandLogo)
        label("graviton") {
            addClass(Styles.logoText)
        }
    }
}
