package net.plan99.graviton

import javafx.animation.Animation
import javafx.animation.Interpolator
import javafx.beans.binding.Bindings
import javafx.beans.property.ReadOnlyDoubleProperty
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.value.ObservableBooleanValue
import javafx.scene.effect.BlendMode
import javafx.scene.layout.Pane
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.Stop
import javafx.scene.shape.Arc
import javafx.scene.shape.ArcType
import javafx.scene.transform.Rotate
import tornadofx.*
import java.util.concurrent.Callable

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

class ThreeDSpinner(private val color: Color) : Fragment() {
    private val fadeLevel = SimpleDoubleProperty(0.0)
    private val animTime = 0.5.seconds

    val visible = SimpleBooleanProperty()

    init {
        visible.onChange {
            if (it) fadeLevel.animate(1.0, animTime) else fadeLevel.animate(0.0, animTime)
        }
    }

    override val root = stackpane {
        slice(45.0, -35.0, 0.0, fadeLevel)
        slice(55.0, 0.0, -45.0, fadeLevel)
        slice(0.0, -60.0, 90.0, fadeLevel)
    }

    private fun StackPane.slice(yAngle: Double, xAngle: Double, zAngle: Double, fadeLevel: ReadOnlyDoubleProperty) {
        val backwardsGradient = Bindings.createObjectBinding(Callable {
            LinearGradient(0.0, 0.0, fadeLevel.value, 0.0, true, CycleMethod.NO_CYCLE,
                    Stop(0.0, color), Stop(1.0, Color.TRANSPARENT))
        }, fadeLevel)
        val gradient = Bindings.createObjectBinding(Callable {
            LinearGradient(1.0 - fadeLevel.value, 0.0, 1.0, 0.0, true, CycleMethod.NO_CYCLE,
                    Stop(0.0, Color.TRANSPARENT), Stop(1.0, color))
        }, fadeLevel)

        group {
            circle {
                fillProperty().bind(if (zAngle <= 0.0) backwardsGradient else gradient)
                strokeWidth = 0.0
                radiusProperty().bind(this@slice.widthProperty().divide(2))
            }
            circle {
                fill = Color.WHITE
                strokeWidth = 0.0
                radiusProperty().bind(this@slice.widthProperty().divide(2))
                translateY = 15.0
            }
            blendMode = BlendMode.MULTIPLY
            opacityProperty().bind(fadeLevel)

            transforms += Rotate(yAngle, Rotate.Y_AXIS)
            transforms += Rotate(xAngle, Rotate.X_AXIS)
            transforms += Rotate(zAngle, Rotate.Z_AXIS).apply {
                val adj = if (zAngle > 0) 1.0 else -1.0
                angleProperty().animate((360.0 * adj) + zAngle, 2.5.seconds) { cycleCount = Animation.INDEFINITE }
            }
        }
    }
}
