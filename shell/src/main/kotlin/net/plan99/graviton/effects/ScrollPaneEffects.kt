package net.plan99.graviton.effects

import javafx.beans.property.SimpleDoubleProperty
import javafx.scene.control.ScrollPane
import javafx.scene.paint.Color
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.Stop
import javafx.scene.shape.Rectangle
import tornadofx.animate
import tornadofx.runLater
import tornadofx.seconds

/**
 * Makes the top and bottom of the scroll pane fade to transparency. Only useful if the scroll pane is on top of
 * something interesting.
 */
fun ScrollPane.addTopBottomFades() {
    clip = Rectangle().apply {
        // We make the clip rect a bit wider than the scroll pane, to avoid accidentally clipping small overspill
        // from the children e.g. due to effects.
        widthProperty().bind(this@addTopBottomFades.widthProperty())
        heightProperty().bind(this@addTopBottomFades.heightProperty())
        fill = LinearGradient(
                0.5, 0.0, 0.5, 1.0, true, CycleMethod.NO_CYCLE,
                Stop(0.0, Color.color(1.0, 1.0, 1.0, 0.0)),
                Stop(0.04, Color.WHITE),
                Stop(0.96, Color.WHITE),
                Stop(1.0, Color.color(1.0, 1.0, 1.0, 0.0))
        )
    }
}

/**
 * Emulates the macOS scroll bars that appear and disappear as needed - the only way to scroll a pane with "mac style
 * scrolling" is to use a scroll wheel or touchpad finger movement, so, we have to be careful with this!
 */
fun ScrollPane.addMacStyleScrolling() {
    vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
    val opacity = SimpleDoubleProperty(0.0)
    runLater {
        // We have to wait for an event loop iteration because we can't do CSS lookups immediately.
        lookup(".scroll-bar:vertical").opacityProperty().bind(opacity)
    }
    setOnScrollStarted { opacity.animate(1.0, 0.3.seconds) }
    setOnScrollFinished {
        runLater(0.5.seconds) { opacity.animate(0.0, 0.3.seconds) }
    }
}