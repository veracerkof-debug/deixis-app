package app.deixis.desktop

import app.deixis.core.FileStore
import app.deixis.core.Mode
import app.deixis.core.QuizPhase
import app.deixis.core.quiz.QuizOption
import app.deixis.core.quiz.QuizQuestion
import app.deixis.core.quizContent
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class QuizRenderTest {
    init { System.setProperty("java.awt.headless", "true") }

    private val renderer = SlideRenderer(FileStore(createTempDirectory("q").toFile()))
    private val q = QuizQuestion("s", "single", "Какая из этих рек самая длинная?", options = listOf(
        QuizOption("a", "Волга", true), QuizOption("b", "Дон"), QuizOption("c", "Днепр"), QuizOption("d", "Урал")))

    private fun countNear(img: java.awt.image.BufferedImage, rgb: Int, tol: Int = 6): Int {
        var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            if (Math.abs(((p shr 16) and 255) - ((rgb shr 16) and 255)) <= tol && Math.abs(((p shr 8) and 255) - ((rgb shr 8) and 255)) <= tol && Math.abs((p and 255) - (rgb and 255)) <= tol) n++
        }
        return n
    }

    @Test
    fun questionShowsTextAndNoAnswerColors() {
        val c = quizContent(q, 0, 3, 20).copy(running = false)
        val img = renderer.render(1280, 720, c, Mode.NORMAL, null)
        System.getProperty("deixis.shots")?.let { ImageIO.write(img, "png", File(it, "quiz-question.png")) }
        assertTrue(countNear(img, 0xFFFFFF, 40) > 500, "текст вопроса не нарисован")
        assertTrue(countNear(img, 0x3F7D57) == 0, "верный ответ виден до результата")
    }

    @Test
    fun revealPaintsRightGreenAndWrongRed() {
        val c = quizContent(q, 0, 3, 20, QuizPhase.REVEAL, picked = setOf("b"))
        val img = renderer.render(1280, 720, c, Mode.NORMAL, null)
        System.getProperty("deixis.shots")?.let { ImageIO.write(img, "png", File(it, "quiz-reveal.png")) }
        assertTrue(countNear(img, 0x3F7D57) > 5000, "верный вариант не зелёный")
        assertTrue(countNear(img, 0xB96E65) > 5000, "выбранный неверный не красный")
    }

    @Test
    fun clearModeHidesTheQuestion() {
        val img = renderer.render(1280, 720, quizContent(q, 0, 3, 20), Mode.CLEAR, null)
        assertTrue(countNear(img, 0xFFFFFF, 40) == 0)
    }

    @Test
    fun portraitScreenRenders() {
        val img = renderer.render(720, 1280, quizContent(q, 0, 3, 20), Mode.NORMAL, null)
        assertTrue(countNear(img, 0xFFFFFF, 40) > 300)
    }
}
