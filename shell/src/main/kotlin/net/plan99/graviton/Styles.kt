package net.plan99.graviton

import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.effect.DropShadow
import javafx.scene.paint.Color
import javafx.scene.paint.LinearGradient
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import tornadofx.*

class Styles : Stylesheet() {
    companion object {
        val shellArea by cssclass()
        val content by cssclass()
        val logoText by cssclass()
        val messageBox by cssclass()
        val historyEntry by cssclass()
        val historyTitle by cssclass()
        val historyDescription by cssclass()
        val primaryWebButton by cssclass()
    }

    private val wireFont: Font = loadFont("/net/plan99/graviton/art/Wire One regular.ttf", 25.0)!!

    init {
        val cornerRadius = multi(box(10.px))

        shellArea {
            fontFamily = "monospace"
            fontSize = 15.pt
            borderColor = multi(box(Color.gray(0.8, 1.0)))
            borderWidth = multi(box(3.px))
            borderRadius = cornerRadius
            backgroundColor = multi(Color.color(1.0, 1.0, 1.0, 0.95))
            backgroundRadius = cornerRadius
            scrollPane {
                content {
                    backgroundColor = multi(Color.TRANSPARENT)
                }
                viewport {
                    backgroundColor = multi(Color.TRANSPARENT)
                }
            }
        }

        logoText {
            font = wireFont
            fontSize = 120.pt
            effect = DropShadow(15.0, Color.WHITE)
        }

        messageBox {
            backgroundColor = multi(Color.color(1.0, 1.0, 1.0, 0.5))
            backgroundRadius = multi(box(5.px))
            borderWidth = multi(box(3.px))
            borderColor = multi(box(Color.LIGHTGREY))
            borderRadius = multi(box(5.px))
            fontSize = 25.pt
        }

        historyEntry {
            borderWidth = multi(box(2.px))
            borderColor = multi(box(Color.web("#dddddd")))
            borderRadius = cornerRadius
            backgroundColor = multi(LinearGradient.valueOf("white,#eeeeeedd"))
            backgroundRadius = cornerRadius
            padding = box(20.px)
        }

        historyEntry and hover {
            borderColor = multi(box(Color.web("#555555")))
            cursor = javafx.scene.Cursor.HAND
        }

        historyTitle {
            fontSize = 25.pt
            padding = box(0.px, 0.px, 15.pt, 0.px)
        }

        historyDescription {
            fontSize = 16.pt
        }


        primaryWebButton {
            backgroundColor = multi(LinearGradient.valueOf("#2fcb53 0%, #269f42 90%"))
            alignment = Pos.CENTER_RIGHT
            textFill = Color.WHITE
            fontWeight = FontWeight.EXTRA_BOLD
            borderColor = multi(box(Color.DARKSLATEGRAY))
            borderWidth = multi(box(1.px))
            borderRadius = multi(box(5.px))
            backgroundRadius = multi(box(5.px))
        }

        primaryWebButton and hover {
            backgroundColor = multi(LinearGradient.valueOf("#34d058 0%, #28a745 90%"))
            cursor = Cursor.HAND
        }
    }
}