package app.graviton.api.v1;

/**
 * This interface provides ways you can call back into Graviton and control it from within your app.
 */
public interface Graviton {
    /**
     * Returns the integer version of Graviton. This is not the same thing as an API version: it may increase any time
     * without affecting anything.
     */
    int getVersion();

    /**
     * Returns the width of the drawable area in either pixels or columns, for GUI and terminal apps respectively.
     * Size your {@link javafx.scene.Scene} to this width if you want it to fill the browser area, or leave it
     * smaller to allow the background art to show through. May return zero if there is no attached screen.
     */
    int getWidth();

    /**
     * Returns the height of the drawable area in either pixels or rows, for GUI and terminal apps respectively.
     * Size your {@link javafx.scene.Scene} to this height if you want it to fill the browser area, or leave it
     * smaller to allow the background art to show through. May return zero if there is no attached screen.
     */
    int getHeight();
}