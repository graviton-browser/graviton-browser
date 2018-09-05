package net.plan99.graviton

import javafx.geometry.Pos
import tornadofx.*

class LoginUI : View() {
    private val logo = find<LogoView>()
    private val shellView: ShellView by inject()

    override val root = vbox {
        children += logo.root

        textfield {
            promptText = "User name"
        }
        passwordfield {
            promptText = "Password"
        }
        button("Sign in") {
            addClass(Styles.primaryWebButton)
            setOnAction {
                shellView.switchToNextScreen()
            }
        }
        children.drop(1).style(append = true) {
            padding = box(20.px)
            fontSize = 20.pt
        }

        alignment = Pos.TOP_RIGHT
        maxWidth = 600.0
        maxHeight = Double.POSITIVE_INFINITY
        style {
            spacing = 20.px
        }
    }
}
