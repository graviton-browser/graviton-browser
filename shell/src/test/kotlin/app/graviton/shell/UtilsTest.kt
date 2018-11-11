package app.graviton.shell

import org.junit.Test
import kotlin.test.assertEquals

class UtilsTest {

    @Test
    fun reversedCoordinatesGroupIdOnly() {
        assertEquals("com.github.spotbugs", reversedCoordinates("spotbugs.github.com"))
    }

    @Test
    fun reversedCoordinatesWithArtifactId() {
        assertEquals("com.github.spotbugs:spotbugs", reversedCoordinates("spotbugs.github.com:spotbugs"))
    }

    @Test
    fun reversedCoordinatesWithArtifactIdAndVersion() {
        assertEquals("com.github.spotbugs:spotbugs:3.1.7", reversedCoordinates("spotbugs.github.com:spotbugs:3.1.7"))
    }

    @Test
    fun reversedCoordinatesWithComplexArguments() {
        assertEquals("com.github.ricksbrown:cowsay -f tux --cowthink moo!", reversedCoordinates("ricksbrown.github.com:cowsay -f tux --cowthink moo!"))
    }

    @Test
    fun reversedCoordinatesNoArtifactIdWithArguments() {
        assertEquals("com.github.spotbugs arguments", reversedCoordinates("spotbugs.github.com arguments"))
    }

    @Test
    fun reversedCoordinatesWithArtifactIdAndArguments() {
        assertEquals("com.github.spotbugs:spotbugs arguments", reversedCoordinates("spotbugs.github.com:spotbugs arguments"))
    }

    @Test
    fun reversedCoordinatesWithArtifactIdAndVersionAndArguments() {
        assertEquals("com.github.spotbugs:spotbugs:3.1.7 arguments", reversedCoordinates("spotbugs.github.com:spotbugs:3.1.7 arguments"))
    }
}
