package cc.novelia.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import cc.novelia.app.reader.IllustrationTransform
import cc.novelia.app.reader.transformIllustration
import org.junit.Assert.*
import org.junit.Test

class IllustrationTransformTest {
    @Test fun pinchKeepsTheTouchedPointAnchoredAndCanReturnToFit() {
        val viewport = Size(400f, 800f)
        val image = Size(800f, 1600f)
        val anchor = Offset(250f, 500f)
        val zoomed = transformIllustration(IllustrationTransform(), viewport, image, anchor, 2f)
        assertEquals(2f, zoomed.scale)
        assertEquals(Offset(-50f, -100f), zoomed.offset)
        assertEquals(IllustrationTransform(), transformIllustration(zoomed, viewport, image, anchor, .5f))
    }

    @Test fun letterboxedImagesStayCenteredOnAxesThatDoNotOverflow() {
        val portrait = transformIllustration(IllustrationTransform(), Size(800f, 400f), Size(400f, 800f), zoom = 2f, pan = Offset(500f, -500f))
        assertEquals(Offset(0f, -200f), portrait.offset)
        val landscape = transformIllustration(IllustrationTransform(), Size(400f, 800f), Size(800f, 400f), zoom = 2f, pan = Offset(-500f, 500f))
        assertEquals(Offset(-200f, 0f), landscape.offset)
    }

    @Test fun zoomAndPanAreBoundedAndInvalidLayoutReturnsFit() {
        val maximum = transformIllustration(IllustrationTransform(), Size(400f, 400f), Size(800f, 800f), zoom = 99f, pan = Offset(9999f, -9999f))
        assertEquals(5f, maximum.scale)
        assertEquals(Offset(800f, -800f), maximum.offset)
        assertEquals(IllustrationTransform(), transformIllustration(maximum, Size(400f, 400f), Size(800f, 800f), zoom = .01f))
        assertEquals(IllustrationTransform(), transformIllustration(maximum, Size.Zero, Size.Zero))
    }
}
