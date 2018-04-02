package net.plan99.graviton

import javafx.animation.Interpolator
import javafx.beans.property.ReadOnlyDoubleProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.value.ObservableBooleanValue
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.shape.Arc
import javafx.scene.shape.ArcType
import tornadofx.*

class ProgressCircle(pane: Pane,
                     private val progress: ReadOnlyDoubleProperty,
                     isDownloading: ObservableBooleanValue,
                     private val circleRadius: Double) {
    private val expandAnimDuration = 500.millis
    private val smallerRadius = circleRadius * 0.9
    private val radius = SimpleDoubleProperty(smallerRadius)
    private lateinit var progressArc: Arc
    private lateinit var totalArc: Arc

    private fun start() {
        radius.animate(circleRadius, expandAnimDuration, Interpolator.EASE_IN)
        progressArc.opacityProperty().animate(1.0, expandAnimDuration)
        totalArc.opacityProperty().animate(0.3, expandAnimDuration)
    }

    private fun stop() {
        radius.animate(smallerRadius, expandAnimDuration, Interpolator.EASE_IN)
        progressArc.opacityProperty().animate(0.0, expandAnimDuration)
        totalArc.opacityProperty().animate(0.0, expandAnimDuration)
    }

    init {
        isDownloading.onChange { if (it) start() else stop() }
        pane.stackpane {
            val strokeWidth_ = 10.0
            val totalSize = radius * 2 + strokeWidth_ * 2
            group {
                rectangle {
                    fill = Color.TRANSPARENT
                    widthProperty().bind(totalSize)
                    heightProperty().bind(totalSize)
                }
                fun makeArc() = arc {
                    radiusXProperty().bind(radius)
                    radiusYProperty().bind(radius)
                    centerXProperty().bind(radius + strokeWidth_)
                    centerYProperty().bind(radius + strokeWidth_)
                    fill = Color.TRANSPARENT
                    stroke = Color.rgb(0, 0, 255, 0.5)
                    opacity = 0.0
                    strokeWidth = strokeWidth_
                    type = ArcType.OPEN
                    startAngle = 90.0
                }
                progressArc = makeArc().apply {
                    lengthProperty().bind(progress * -360.0)
                }
                // Now make a background circle that shows the expected path.
                totalArc = makeArc().apply {
                    length = 360.0
                }
            }
            translateY = -70.0
            minHeightProperty().bind(totalSize)
            minWidthProperty().bind(totalSize)
        }
    }
}